package com.maipiao.pay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.maipiao.common.core.dto.PaymentOrderSnapshot;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.R;
import com.maipiao.pay.channel.*;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentCreationTest {
    PaymentMapper payments = mock(PaymentMapper.class);
    OrderClient orders = mock(OrderClient.class);
    PaymentChannelFactory channels = mock(PaymentChannelFactory.class);
    PaymentChannel channel = mock(PaymentChannel.class);
    PaymentIntentService intents = mock(PaymentIntentService.class);
    PaymentService service =
            new PaymentService(
                    payments,
                    mock(RefundMapper.class),
                    channels,
                    mock(NotifyLogService.class),
                    orders,
                    mock(PaymentTxService.class),
                    intents,
                    mock(PaymentSeatSyncService.class));
    LocalDateTime deadline = LocalDateTime.now().plusMinutes(2);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "paymentMinutes", 15);
        snapshot(7L, 0, deadline);
        when(intents.getOrCreate(anyString(), anyLong(), any(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            Payment payment = new Payment();
                            payment.setPaymentNo("payment");
                            payment.setOrderNo(call.getArgument(0));
                            payment.setUserId(call.getArgument(1));
                            payment.setAmount(call.getArgument(2));
                            payment.setChannel(call.getArgument(3));
                            payment.setExpireTime(call.getArgument(4));
                            payment.setStatus(Payment.STATUS_PENDING);
                            return payment;
                        });
        when(payments.bindChannel(anyString(), anyString(), anyString())).thenReturn(1);
        when(channels.get("MOCK")).thenReturn(channel);
        when(channel.type()).thenReturn(PaymentChannel.ChannelType.MOCK);
        when(channel.prepay(any()))
                .thenReturn(new PaymentChannel.PrepayResult("trade", "url", "{}"));
    }

    void snapshot(Long user, int status, LocalDateTime expires) {
        when(orders.summary("token"))
                .thenReturn(
                        R.ok(
                                new PaymentOrderSnapshot(
                                        "token", user, new BigDecimal("100.00"), expires, status)));
    }

    @Test
    void amountAndDeadlineComeFromOrderEvenWhenClientChangesAmount() {
        var payment = service.createForOrder("token", 7L, new BigDecimal("0.01"), "MOCK");
        assertEquals(new BigDecimal("100.00"), payment.getAmount());
        assertEquals(deadline, payment.getExpireTime());
        verify(channel)
                .prepay(argThat(command -> command.amount().compareTo(new BigDecimal("100")) == 0));
    }

    @Test
    void wrongOwnerExpiredOrCancelledOrdersNeverReachChannel() {
        assertThrows(
                BizException.class,
                () -> service.createForOrder("token", 8L, BigDecimal.ONE, "MOCK"));
        snapshot(7L, 4, deadline);
        assertThrows(
                BizException.class,
                () -> service.createForOrder("token", 7L, BigDecimal.ONE, "MOCK"));
        snapshot(7L, 0, LocalDateTime.now().minusSeconds(1));
        assertThrows(
                BizException.class,
                () -> service.createForOrder("token", 7L, BigDecimal.ONE, "MOCK"));
        verifyNoInteractions(payments, channels);
    }

    @Test
    void unavailableOrderSummaryCannotCreatePayment() {
        when(orders.summary("token")).thenReturn(null);
        assertThrows(
                BizException.class,
                () -> service.createForOrder("token", 7L, BigDecimal.ONE, "MOCK"));
        verifyNoInteractions(payments, channels);
    }

    @Test
    void existingPaymentBeyondReservationDeadlineCannotBeReusedOrSilentlyReplaced() {
        Payment existing = new Payment();
        existing.setUserId(7L);
        existing.setStatus(Payment.STATUS_PENDING);
        existing.setAmount(new BigDecimal("100.00"));
        existing.setExpireTime(deadline.plusMinutes(10));
        when(payments.selectLatestByOrderNo("token")).thenReturn(existing);
        assertThrows(
                BizException.class,
                () -> service.createForOrder("token", 7L, BigDecimal.ONE, "MOCK"));
        verify(payments, never()).insert(any(Payment.class));
        verifyNoInteractions(channels);
    }
}
