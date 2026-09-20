package com.maipiao.pay.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.channel.PaymentChannelFactory;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Refunds: recording them, sending them, and retrying the ones that fail.
 *
 * <p>G3, and the counterpart to {@link PaymentTxService}. Same shape, same
 * reason: the local write and the remote writes have to commit or fail
 * together, so the transactional part lives in a bean of its own rather than
 * in a method that would be called on {@code this} and lose its proxy.
 *
 * <p>The order of operations is the opposite of a payment's, deliberately. A
 * payment is recorded before it is acted on; a refund is acted on before it is
 * recorded as done, because the provider is the one who decides. What is
 * written first is the <b>intent</b> - the refund row - so a crash between the
 * two leaves something to retry from rather than a refund the provider knows
 * about and we do not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final PaymentChannelFactory channelFactory;
    private final PaymentTxService paymentTxService;
    private final com.maipiao.pay.feign.OrderClient orderClient;

    /** After this many attempts a human has to look at it. */
    private static final int MAX_RETRIES = 5;

    @Value("${maipiao.pay.refund-batch:50}")
    private int refundBatch;

    // ------------------------------------------------------------

    /**
     * Records a refund and sends it to the provider.
     *
     * <p>Idempotent per order. A second request returns the existing refund
     * rather than paying the customer twice - the single worst thing this
     * class could do, and the reason the uniqueness lives in a database
     * constraint rather than in a check-then-act.
     */
    public String refund(String orderNo, BigDecimal amount) {
        Payment payment = paymentMapper.selectLatestByOrderNo(orderNo);
        if (payment == null || payment.getStatus() != Payment.STATUS_SUCCESS) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND, "订单没有成功的支付记录");
        }

        String refundNo = createRefund(payment, amount);

        Refund refund = refundMapper.selectByRefundNo(refundNo);
        if (refund != null && refund.getStatus() == Refund.STATUS_SUCCESS) {
            // Already settled - by an earlier request, or by the retry sweep.
            // Sending it again would pay the customer a second time.
            log.debug("refund already settled: orderNo={}, refundNo={}", orderNo, refundNo);
            return refundNo;
        }

        send(refund);
        return refundNo;
    }

    /**
     * Writes the refund row before anything else happens.
     *
     * <p>L4 of the idempotency design: the unique key on {@code payment_no}
     * means a repeated request collides instead of creating a second refund.
     * The collision is the answer, not a failure - the existing row is what
     * the caller wanted.
     */
    @Transactional(rollbackFor = Exception.class)
    public String createRefund(Payment payment, BigDecimal amount) {
        String refundNo = SnowflakeIdGenerator.nextString();
        int created = refundMapper.insertIfAbsent(
                SnowflakeIdGenerator.next(),
                refundNo,
                payment.getPaymentNo(),
                payment.getOrderNo(),
                payment.getUserId(),
                payment.getChannel(),
                amount == null ? payment.getAmount() : amount,
                Refund.REASON_USER_APPLY,
                // releaseSeat = 1: a normal refund puts the seats back on sale.
                // The LATE_PAY path passes 0, because those seats were released
                // when the order was cancelled and doing it again would take
                // the sold counter below zero.
                1);

        if (created == 0) {
            Refund existing = refundMapper.selectByPaymentNo(payment.getPaymentNo());
            log.info("refund already exists: orderNo={}, refundNo={}",
                    payment.getOrderNo(), existing.getRefundNo());
            return existing.getRefundNo();
        }
        return refundNo;
    }

    /**
     * Sends one refund and applies the result.
     *
     * <p>{@code paymentTxService.settleRefund} is a separate bean because it
     * carries {@code @GlobalTransactional}: called from here it would be
     * self-invocation, the proxy would be bypassed, and the annotation would
     * silently do nothing. That exact mistake is what {@link PaymentTxService}
     * exists to prevent, and it is worth not making twice.
     */
    public void send(Refund refund) {
        PaymentChannel channel = channelFactory.get(refund.getChannel());

        // The original trade number is not on the refund row - it belongs to
        // the payment, and copying it would be a second version of a value the
        // provider issued. Looked up here because this is the only place that
        // needs it.
        Payment payment = paymentMapper.selectByPaymentNo(refund.getPaymentNo());
        String channelTradeNo = payment == null ? null : payment.getChannelTradeNo();

        PaymentChannel.RefundResult result;
        try {
            result = channel.refund(new PaymentChannel.RefundCommand(
                    refund.getRefundNo(),
                    refund.getPaymentNo(),
                    channelTradeNo,
                    refund.getRefundAmount(),
                    refund.getReason()));
        } catch (Exception e) {
            log.error("refund call failed: refundNo={}", refund.getRefundNo(), e);
            recordFailure(refund, e.getMessage());
            return;
        }

        if (!result.accepted()) {
            log.warn("refund refused by the provider: refundNo={}, message={}",
                    refund.getRefundNo(), result.message());
            recordFailure(refund, result.message());
            return;
        }

        // G3：退款结清、订单转已退款、账本座位回到可选 —— 三件一起，或者都不做。
        paymentTxService.settleRefund(refund, result.channelRefundNo());

        // 事务提交之后再清 Redis。放在外面而不是里面，是因为 Redis 回滚不了：
        // 事务里清掉的位会在回滚后留下来，座位就挂到市场上去了，
        // 而订单还读作已支付 —— 那意味着同一个座位被卖两次。
        clearSeatHold(refund.getOrderNo());
    }

    /**
     * 清掉退款订单在 Redis 里的座位占用。尽力而为。
     *
     * <p>失败只记日志不上抛：订单和账本都已经正确了，座位图慢一拍是次要问题；
     * 而抛出去会让调用方以为退款本身失败了 —— 钱已经退了，那是个更糟的误导。
     */
    private void clearSeatHold(String orderNo) {
        try {
            orderClient.releaseRefundedSeats(orderNo);
        } catch (Exception e) {
            log.error("退款成功但未能清掉座位占用：orderNo={}，"
                    + "座位在对账任务跑到之前无法再次售出", orderNo, e);
        }
    }

    /**
     * Sends the refunds that have not gone through yet.
     *
     * <p>Task 9 of the design. Backoff is exponential and computed in SQL by
     * {@code recordFailure}, so this only has to ask which ones are due. A
     * refund that has failed its way through every attempt is marked failed
     * and left alone: a loop that retried forever would hide the one case that
     * needs a person.
     */
    public int retryPending() {
        List<Refund> pending = refundMapper.selectRetryable(MAX_RETRIES, refundBatch);

        int sent = 0;
        for (Refund refund : pending) {
            try {
                send(refund);
                sent++;
            } catch (Exception e) {
                // One bad refund must not stop the sweep - the others are just
                // as owed.
                log.error("refund retry failed: refundNo={}", refund.getRefundNo(), e);
            }
        }

        if (sent > 0) {
            log.info("refund retry sweep: attempted {}", sent);
        }
        return sent;
    }

    /** Refunds for an order, for the console and the order page. */
    public List<Refund> ofOrder(String orderNo) {
        return refundMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Refund>lambdaQuery()
                        .eq(Refund::getOrderNo, orderNo)
                        .orderByDesc(Refund::getCreateTime));
    }

    // ------------------------------------------------------------

    /**
     * Records a failed attempt.
     *
     * <p>The next attempt's time is computed by the statement itself, so every
     * caller gets the same schedule rather than each one reimplementing it.
     * The status passed in is what it becomes: still retrying, or failed for
     * good once the attempts are used up.
     */
    private void recordFailure(Refund refund, String message) {
        int attempt = (refund.getRetryCount() == null ? 0 : refund.getRetryCount()) + 1;
        boolean giveUp = attempt >= MAX_RETRIES;

        refundMapper.recordFailure(refund.getRefundNo(),
                giveUp ? Refund.STATUS_FAILED : Refund.STATUS_REFUNDING,
                message);

        if (giveUp) {
            log.error("refund failed permanently after {} attempts and needs a human: "
                            + "refundNo={}, orderNo={}, message={}",
                    attempt, refund.getRefundNo(), refund.getOrderNo(), message);
        } else {
            log.warn("refund attempt {} failed, will retry: refundNo={}, message={}",
                    attempt, refund.getRefundNo(), message);
        }
    }
}
