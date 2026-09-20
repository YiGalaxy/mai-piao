package com.maipiao.pay.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.channel.PaymentChannelFactory;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Payment creation, callbacks and refunds.
 *
 * <p>The callback path is where the interesting failures live, and each guard
 * there exists because of a specific one:
 *
 * <ul>
 *   <li>the same callback twice</li>
 *   <li>a failure then a success, out of order</li>
 *   <li>a success after the order was already cancelled</li>
 *   <li>a callback whose signature does not check out</li>
 *   <li>a callback quoting a different amount</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final PaymentChannelFactory channelFactory;
    private final NotifyLogService notifyLogService;
    private final OrderClient orderClient;
    private final PaymentTxService paymentTxService;

    @Value("${maipiao.pay.payment-minutes:15}")
    private int paymentMinutes;

    // ============================================================
    // creating a payment
    // ============================================================

    /**
     * Creates (or reuses) the payment for an order and asks the provider to
     * open it.
     *
     * <p>Idempotent by order: a user who reloads the payment page gets the
     * same payment order and the same provider trade rather than a second one
     * that could be paid in parallel.
     */
    @Transactional(rollbackFor = Exception.class)
    public Payment createForOrder(String orderNo, Long userId, BigDecimal amount, String channelType) {

        Payment existing = paymentMapper.selectLatestByOrderNo(orderNo);
        if (existing != null && existing.getStatus() == Payment.STATUS_PENDING) {
            return existing;
        }
        if (existing != null && existing.getStatus() == Payment.STATUS_SUCCESS) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单已支付");
        }

        PaymentChannel channel = channelFactory.get(channelType);

        Payment payment = new Payment();
        payment.setPaymentNo(SnowflakeIdGenerator.nextString());
        payment.setOrderNo(orderNo);
        payment.setUserId(userId);
        payment.setChannel(channel.type().name());
        payment.setAmount(amount);
        payment.setStatus(Payment.STATUS_PENDING);
        payment.setExpireTime(LocalDateTime.now().plusMinutes(paymentMinutes));
        paymentMapper.insert(payment);

        PaymentChannel.PrepayResult prepay = channel.prepay(new PaymentChannel.PrepayCommand(
                payment.getPaymentNo(), orderNo, amount, "麦票订单 " + orderNo));

        // The provider's trade number is written back now so a callback that
        // arrives before this method returns can still be matched.
        Payment update = new Payment();
        update.setId(payment.getId());
        update.setChannelTradeNo(prepay.channelTradeNo());
        paymentMapper.updateById(update);
        payment.setChannelTradeNo(prepay.channelTradeNo());

        log.info("payment created: paymentNo={}, orderNo={}, amount={}, channel={}",
                payment.getPaymentNo(), orderNo, amount, channel.type());

        return payment;
    }

    // ============================================================
    // the callback
    // ============================================================

    /**
     * Handles a provider callback.
     *
     * <p>Runs as the G2 global transaction when the payment succeeds: marking
     * the order paid, issuing the tickets and confirming the seat holds all
     * have to happen together or not at all.
     *
     * @return the body to send back to the provider
     */
    public String handleNotify(String channelType, String rawBody, Map<String, String> headers) {

        PaymentChannel channel = channelFactory.get(channelType);

        // ---- verify ----
        PaymentChannel.NotifyResult notify = channel.parseNotify(rawBody, headers);
        if (!notify.signVerified()) {
            // Recorded with the failed verification so it is visible, but not
            // processed. The provider is told to retry, which will not help -
            // that is why it is logged rather than silently accepted.
            log.warn("rejected callback with bad signature: channel={}", channelType);
            notifyLogService.record(channelType, notify.channelTradeNo(), "PAY", rawBody, false);
            return channel.failResponse();
        }

        if (notify.paymentNo() == null) {
            log.warn("callback carried no payment number: {}", rawBody);
            return channel.failResponse();
        }

        // ---- L1: log dedup, which only tells us whether to reprocess ----
        NotifyLogService.RecordResult record =
                notifyLogService.record(channelType, notify.channelTradeNo(), "PAY", rawBody, true);

        if (record.alreadySucceeded()) {
            log.debug("callback already handled: paymentNo={}", notify.paymentNo());
            return channel.successResponse();
        }

        try {
            // The transactional half lives in PaymentTxService, reached through
            // its proxy. Calling it here directly would bypass the proxy and
            // quietly drop the global transaction - see that class.
            boolean success = notify.isSuccess()
                    ? paymentTxService.markPaid(notify)
                    : paymentTxService.markFailed(notify);
            notifyLogService.markProcessed(record.logId(),
                    success ? 1 : 0, success ? "handled" : "ignored");

            if (success && notify.isSuccess()) {
                // After the transaction, never inside it: Redis cannot be
                // rolled back, so a marker written within would outlive a
                // rollback and pin the seat as sold with nothing behind it.
                // Best-effort - a failure here is logged, not fatal, because
                // the payment and the order are already correct.
                confirmSeatHold(notify.orderNo());
            }

            return channel.successResponse();
        } catch (Exception e) {
            log.error("callback handling failed: paymentNo={}", notify.paymentNo(), e);
            notifyLogService.markFailed(record.logId(), e.getMessage());
            return channel.failResponse();
        }
    }

    /**
     * Marks the Redis seat hold as sold once the money has landed.
     *
     * <p>Kept out of the global transaction on purpose. The seat bit is already
     * set, so a missed marker does not oversell anything; what it would lose is
     * the distinction between a hold and a sale, which is what stops a later
     * release from putting a paid-for seat back on sale.
     */
    private void confirmSeatHold(String orderNo) {
        try {
            orderClient.confirmSeats(orderNo);
        } catch (Exception e) {
            log.error("could not confirm seat hold after payment: orderNo={}", orderNo, e);
        }
    }

    // ============================================================
    // refunds
    // ============================================================

    /**
     * Requests a refund for an order.
     *
     * <p>Creates the refund row before calling the provider, so a crash between
     * the two leaves a record to retry from rather than a refund the provider
     * knows about and we do not.
     */
    @Transactional(rollbackFor = Exception.class)
    public String applyRefund(String orderNo, BigDecimal amount, String reason) {
        Payment payment = paymentMapper.selectLatestByOrderNo(orderNo);
        if (payment == null || payment.getStatus() != Payment.STATUS_SUCCESS) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND, "订单没有成功的支付记录");
        }

        // L4: a repeated request collides on payment_no and returns the
        // existing refund instead of issuing a second one at the provider.
        String refundNo = SnowflakeIdGenerator.nextString();
        int created = refundMapper.insertIfAbsent(
                SnowflakeIdGenerator.next(), refundNo, payment.getPaymentNo(), orderNo,
                payment.getUserId(), payment.getChannel(),
                amount == null ? payment.getAmount() : amount,
                reason, 1);

        if (created == 0) {
            Refund existing = refundMapper.selectByPaymentNo(payment.getPaymentNo());
            log.info("refund already exists: orderNo={}, refundNo={}", orderNo, existing.getRefundNo());
            return existing.getRefundNo();
        }

        return refundNo;
    }

    /** Sends a pending refund to the provider. Driven by the retry sweep. */
    @Transactional(rollbackFor = Exception.class)
    public void submitRefund(Refund refund) {
        PaymentChannel channel = channelFactory.get(refund.getChannel());

        PaymentChannel.RefundResult result = channel.refund(new PaymentChannel.RefundCommand(
                refund.getRefundNo(),
                refund.getPaymentNo(),
                null,
                refund.getRefundAmount(),
                refund.getReason()));

        if (result.accepted()) {
            refundMapper.casRefundSuccess(refund.getRefundNo(),
                    result.channelRefundNo(), refund.getRefundAmount());
        } else {
            refundMapper.recordFailure(refund.getRefundNo(), Refund.STATUS_REFUNDING,
                    result.message());
        }
    }

    // ============================================================

    public Payment getByPaymentNo(String paymentNo) {
        Payment payment = paymentMapper.selectByPaymentNo(paymentNo);
        if (payment == null) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        return payment;
    }

    public Payment getByOrderNo(String orderNo) {
        return paymentMapper.selectLatestByOrderNo(orderNo);
    }
}
