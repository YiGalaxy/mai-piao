package com.maipiao.pay.job;

import static org.mockito.Mockito.*;

import com.maipiao.common.core.result.R;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import com.maipiao.pay.service.PaymentSeatSyncService;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeatSyncJobTest {
    PaymentMapper payments = mock(PaymentMapper.class);
    RefundMapper refunds = mock(RefundMapper.class);
    OrderClient orders = mock(OrderClient.class);
    PaymentSeatSyncService seatSync = mock(PaymentSeatSyncService.class);
    SeatSyncJob job = new SeatSyncJob(payments, refunds, seatSync, orders);

    Payment payment(String id) {
        Payment p = new Payment();
        p.setPaymentNo(id);
        p.setOrderNo(id);
        return p;
    }

    @Test
    void failedConfirmationRemainsPendingAndDoesNotBlockOtherPayments() {
        when(payments.selectSeatSyncPending(any()))
                .thenReturn(List.of(payment("bad"), payment("good")));
        doThrow(new IllegalStateException("offline")).when(seatSync).synchronize("bad");
        job.synchronizeSeats();
        verify(seatSync).synchronize("bad");
        verify(seatSync).synchronize("good");
    }

    @Test
    void refundingPaymentNeverConfirmsSeatsAgain() {
        when(payments.selectSeatSyncPending(any())).thenReturn(List.of(payment("refund")));
        job.synchronizeSeats();
        verify(seatSync).synchronize("refund");
    }

    @Test
    void onlySuccessfulReleaseCompletesRefundCompensation() {
        Refund r = new Refund();
        r.setRefundNo("refund");
        r.setOrderNo("order");
        when(refunds.selectSeatReleasePending(any())).thenReturn(List.of(r));
        when(orders.releaseRefundedSeats("order")).thenReturn(R.fail(503, "offline"), R.ok());
        job.synchronizeSeats();
        verify(refunds, never()).markSeatReleased(anyString());
        job.synchronizeSeats();
        verify(refunds).markSeatReleased("refund");
    }
}
