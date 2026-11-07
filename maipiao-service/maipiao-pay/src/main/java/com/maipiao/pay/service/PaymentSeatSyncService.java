package com.maipiao.pay.service;

import static com.maipiao.common.core.result.RemoteResult.requireSuccess;

import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Confirms Redis seat ownership after payment and records successful synchronization. */
@Service
@RequiredArgsConstructor
public class PaymentSeatSyncService {

    private static final int ORDER_PAID = 2;
    private static final int ORDER_COMPLETED = 3;
    private static final int ORDER_REFUNDING = 5;
    private static final int ORDER_REFUNDED = 6;

    private final PaymentMapper payments;
    private final OrderClient orders;

    public void synchronize(String paymentNo) {
        Payment payment = payments.selectByPaymentNo(paymentNo);
        if (payment == null || payment.getStatus() != Payment.STATUS_SUCCESS) {
            throw new IllegalStateException("payment is not successful: " + paymentNo);
        }
        if (Integer.valueOf(1).equals(payment.getSeatSynced())) {
            return;
        }

        var order = requireSuccess(orders.summary(payment.getOrderNo()), "核验已支付订单");
        if (order == null || order.status() == null) {
            throw new IllegalStateException("order snapshot is missing: " + payment.getOrderNo());
        }

        if (order.status() == ORDER_PAID || order.status() == ORDER_COMPLETED) {
            requireSuccess(orders.confirmSeats(payment.getOrderNo()), "确认已售座位");
        } else if (order.status() != ORDER_REFUNDING && order.status() != ORDER_REFUNDED) {
            throw new IllegalStateException("order status does not allow seat confirmation");
        }

        if (payments.markSeatSynced(paymentNo) != 1) {
            Payment latest = payments.selectByPaymentNo(paymentNo);
            if (latest == null || !Integer.valueOf(1).equals(latest.getSeatSynced())) {
                throw new IllegalStateException("could not mark payment seats as synchronized");
            }
        }
    }
}
