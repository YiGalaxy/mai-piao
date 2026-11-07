package com.maipiao.pay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.maipiao.common.core.dto.PaymentOrderSnapshot;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.R;
import com.maipiao.pay.channel.*;
import com.maipiao.pay.entity.*;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.*;

class TradeReliabilityTest {
    PaymentMapper payments = mock(PaymentMapper.class);
    RefundMapper refunds = mock(RefundMapper.class);
    OrderClient orders = mock(OrderClient.class);
    PaymentChannelFactory channels = mock(PaymentChannelFactory.class);
    PaymentChannel channel = mock(PaymentChannel.class);
    PaymentTxService tx = new PaymentTxService(payments, refunds, orders);
    Payment payment;

    @BeforeEach
    void setup() {
        payment = new Payment();
        payment.setPaymentNo("pay");
        payment.setOrderNo("order");
        payment.setChannel("MOCK");
        payment.setChannelTradeNo("trade");
        payment.setUserId(7L);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setStatus(Payment.STATUS_PENDING);
        when(payments.selectForUpdate("pay")).thenReturn(payment);
        when(payments.selectByPaymentNo("pay")).thenReturn(payment);
        when(channels.get("MOCK")).thenReturn(channel);
        when(channel.type()).thenReturn(PaymentChannel.ChannelType.MOCK);
        when(channel.successResponse()).thenReturn("success");
        when(channel.failResponse()).thenReturn("failure");
        orderStatus(0);
        when(payments.casPaySuccess(eq("pay"), eq("trade"), any(), any())).thenReturn(1);
        when(orders.markPaid(eq("order"), any())).thenReturn(R.ok());
        when(orders.issueTickets("order", "pay")).thenReturn(R.ok());
    }

    void orderStatus(int status) {
        when(orders.summary("order"))
                .thenReturn(
                        R.ok(
                                new PaymentOrderSnapshot(
                                        "order",
                                        7L,
                                        payment.getAmount(),
                                        LocalDateTime.now().plusMinutes(1),
                                        status)));
    }

    PaymentChannel.NotifyResult notify(String status) {
        return new PaymentChannel.NotifyResult(
                true, "pay", "order", "trade", payment.getAmount(), status, "");
    }

    Refund refund(int release) {
        Refund r = new Refund();
        r.setRefundNo("refund");
        r.setPaymentNo("pay");
        r.setOrderNo("order");
        r.setRefundAmount(payment.getAmount());
        r.setChannel("MOCK");
        r.setReleaseSeat(release);
        r.setStatus(Refund.STATUS_PENDING);
        return r;
    }

    @Test
    void businessFailureStopsTicketIssuance() {
        when(orders.markPaid(eq("order"), any())).thenReturn(R.fail(503, "not committed"));
        assertThrows(BizException.class, () -> tx.markPaid(notify("SUCCESS")));
        verify(orders, never()).issueTickets(anyString(), anyString());
    }

    @Test
    void failedPaymentCanSucceedAndDuplicateSuccessDoesNotIssueAgain() {
        tx.markFailed(notify("FAILED"));
        payment.setStatus(Payment.STATUS_FAILED);
        assertTrue(tx.markPaid(notify("SUCCESS")));
        payment.setStatus(Payment.STATUS_SUCCESS);
        assertTrue(tx.markPaid(notify("SUCCESS")));
        tx.markFailed(notify("FAILED"));
        verify(orders, times(1)).issueTickets("order", "pay");
        verify(payments, times(1)).casPaySuccess(eq("pay"), eq("trade"), any(), any());
    }

    @Test
    void cancelledOrderOnlyCreatesMoneyRefund() {
        orderStatus(4);
        assertTrue(tx.markPaid(notify("SUCCESS")));
        verify(refunds)
                .insertIfAbsent(
                        anyLong(),
                        anyString(),
                        eq("pay"),
                        eq("order"),
                        eq(7L),
                        eq("MOCK"),
                        eq(payment.getAmount()),
                        eq(Refund.REASON_TIME_OUT_PAID),
                        eq(0));
        verify(orders, never()).issueTickets(anyString(), anyString());
        verify(orders, never()).markPaid(anyString(), any());
    }

    @Test
    void wrongBindingIsRejectedBeforeAnyWrite() {
        var bad =
                new PaymentChannel.NotifyResult(
                        true, "pay", "other", "trade", BigDecimal.ONE, "SUCCESS", "");
        assertThrows(BizException.class, () -> tx.markPaid(bad));
        verifyNoInteractions(orders, refunds);
        verify(payments, never()).casPaySuccess(anyString(), anyString(), any(), any());
    }

    @Test
    void latePaymentRefundDoesNotChangeCancelledOrder() {
        when(refunds.casRefundSuccess(anyString(), anyString(), any())).thenReturn(1);
        tx.settleRefund(refund(0), "channel-refund");
        verifyNoInteractions(orders);
        when(orders.markRefunded(anyString(), any())).thenReturn(R.ok());
        tx.settleRefund(refund(1), "channel-refund");
        verify(orders).markRefunded("order", payment.getAmount());
    }

    @Test
    void refundBusinessFailureIsNotAcknowledged() {
        when(refunds.casRefundSuccess(anyString(), anyString(), any())).thenReturn(1);
        when(orders.markRefunded(anyString(), any())).thenReturn(R.fail(503, "ledger failed"));
        assertThrows(BizException.class, () -> tx.settleRefund(refund(1), "channel-refund"));
    }

    @Test
    void refundCasConflictIsNotSilentlySuccessful() {
        assertThrows(BizException.class, () -> tx.settleRefund(refund(1), "channel-refund"));
        verifyNoInteractions(orders);
    }

    @Test
    void auditSuccessForFailedEventCannotHideLaterSuccess() {
        NotifyLogService logs = mock(NotifyLogService.class);
        PaymentTxService transactions = mock(PaymentTxService.class);
        PaymentService service =
                new PaymentService(
                        payments,
                        refunds,
                        channels,
                        logs,
                        orders,
                        transactions,
                        mock(PaymentIntentService.class),
                        mock(PaymentSeatSyncService.class));
        when(logs.record(anyString(), anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(new NotifyLogService.RecordResult(1L, true, 0));
        when(channel.parseNotify(anyString(), any()))
                .thenReturn(notify("FAILED"), notify("SUCCESS"));
        when(transactions.markFailed(any())).thenReturn(true);
        when(transactions.markPaid(any())).thenReturn(true);
        assertEquals("success", service.handleNotify("MOCK", "first", Map.of()));
        assertEquals("success", service.handleNotify("MOCK", "second", Map.of()));
        verify(transactions).markPaid(any());
        // 模拟迟到到账：本地支付仍未成功，不能确认已取消订单的座位。
        verify(orders, never()).confirmSeats(anyString());
    }

    @Test
    void unknownRefundNeverSendsAnotherRequestOrSettles() {
        RefundService service =
                new RefundService(
                        payments, refunds, mock(RefundIntentService.class), channels, tx, orders);
        when(channel.queryRefund("refund"))
                .thenReturn(new PaymentChannel.RefundResult(false, null, "timeout", "UNKNOWN"));
        service.send(refund(1));
        verify(channel, never()).refund(any());
        verify(refunds).recordFailure(eq("refund"), eq(Refund.STATUS_REFUNDING), anyString());
        verify(refunds, never()).casRefundSuccess(anyString(), anyString(), any());
    }

    @Test
    void acceptedRefundWaitsForFinalSuccessUsingOriginalIdentity() {
        RefundService service =
                new RefundService(
                        payments, refunds, mock(RefundIntentService.class), channels, tx, orders);
        when(channel.queryRefund("refund"))
                .thenReturn(
                        new PaymentChannel.RefundResult(false, null, "missing", "NOT_FOUND"),
                        new PaymentChannel.RefundResult(true, "channel-refund", "done", "SUCCESS"));
        when(channel.refund(any()))
                .thenReturn(
                        new PaymentChannel.RefundResult(
                                true, "channel-refund", "accepted", "PROCESSING"));
        when(refunds.casRefundSuccess(anyString(), anyString(), any())).thenReturn(1);
        Refund r = refund(0);
        service.send(r);
        verify(refunds, never()).casRefundSuccess(anyString(), anyString(), any());
        service.send(r);
        verify(channel, times(1)).refund(argThat(c -> c.refundNo().equals("refund")));
        verify(refunds).casRefundSuccess("refund", "channel-refund", payment.getAmount());
        verifyNoInteractions(orders);
    }
}
