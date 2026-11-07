package com.maipiao.pay.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.maipiao.common.core.dto.PaymentOrderSnapshot;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.R;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentSeatSyncServiceTest {
    PaymentMapper payments = mock(PaymentMapper.class);
    OrderClient orders = mock(OrderClient.class);
    PaymentSeatSyncService service = new PaymentSeatSyncService(payments, orders);

    Payment payment(int seatSynced) {
        Payment payment = new Payment();
        payment.setPaymentNo("payment");
        payment.setOrderNo("order");
        payment.setStatus(Payment.STATUS_SUCCESS);
        payment.setSeatSynced(seatSynced);
        return payment;
    }

    R<PaymentOrderSnapshot> snapshot(int status) {
        return R.ok(
                new PaymentOrderSnapshot("order", 7L, BigDecimal.TEN, LocalDateTime.now(), status));
    }

    @BeforeEach
    void setup() {
        when(payments.selectByPaymentNo("payment")).thenReturn(payment(0));
        when(orders.summary("order")).thenReturn(snapshot(2));
        when(orders.confirmSeats("order")).thenReturn(R.ok());
        when(payments.markSeatSynced("payment")).thenReturn(1);
    }

    @Test
    void confirmsSeatsBeforeMarkingSynchronizationComplete() {
        service.synchronize("payment");

        var order = inOrder(orders, payments);
        order.verify(orders).confirmSeats("order");
        order.verify(payments).markSeatSynced("payment");
    }

    @Test
    void remoteFailureLeavesSynchronizationPending() {
        when(orders.confirmSeats("order")).thenReturn(R.fail(503, "offline"));

        assertThrows(BizException.class, () -> service.synchronize("payment"));
        verify(payments, never()).markSeatSynced("payment");
    }

    @Test
    void refundingOrdersAreMarkedSyncedWithoutReconfirmingSeats() {
        when(orders.summary("order")).thenReturn(snapshot(5));

        service.synchronize("payment");

        verify(orders, never()).confirmSeats(anyString());
        verify(payments).markSeatSynced("payment");
    }

    @Test
    void alreadySynchronizedPaymentsSkipRemoteCalls() {
        when(payments.selectByPaymentNo("payment")).thenReturn(payment(1));

        service.synchronize("payment");

        verifyNoInteractions(orders);
        verify(payments, never()).markSeatSynced(anyString());
    }

    @Test
    void concurrentSuccessfulSynchronizationIsIdempotent() {
        when(payments.markSeatSynced("payment")).thenReturn(0);
        when(payments.selectByPaymentNo("payment")).thenReturn(payment(0), payment(1));

        service.synchronize("payment");

        verify(orders).confirmSeats("order");
        verify(payments).markSeatSynced("payment");
    }
}
