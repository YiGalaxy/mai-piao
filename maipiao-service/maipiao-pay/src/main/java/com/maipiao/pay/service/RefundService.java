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

/** 退款记录、发起和失败重试。G3 结算由 {@link PaymentTxService} 执行。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final PaymentChannelFactory channelFactory;
    private final PaymentTxService paymentTxService;
    private final com.maipiao.pay.feign.OrderClient orderClient;

    /** 重试到这么多次之后，就必须有人来看一眼了。 */
    private static final int MAX_RETRIES = 5;

    @Value("${maipiao.pay.refund-batch:50}")
    private int refundBatch;

    // ------------------------------------------------------------

    /** 创建退款单并发起渠道退款；幂等由 payment_no 唯一键保证。 */
    public String refund(String orderNo, BigDecimal amount) {
        Payment payment = paymentMapper.selectLatestByOrderNo(orderNo);
        if (payment == null || payment.getStatus() != Payment.STATUS_SUCCESS) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND, "订单没有成功的支付记录");
        }

        String refundNo = createRefund(payment, amount);

        Refund refund = refundMapper.selectByRefundNo(refundNo);
        if (refund != null && refund.getStatus() == Refund.STATUS_SUCCESS) {
            // 已经结清了 —— 被更早的一次请求，或者被重试扫描结掉的。
            // 再发一次就等于给用户退了第二遍钱。
            log.debug("refund already settled: orderNo={}, refundNo={}", orderNo, refundNo);
            return refundNo;
        }

        send(refund);
        return refundNo;
    }

    /** 先写入退款意图，利用唯一键避免重复退款单。 */
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
                // releaseSeat = 1：普通退款会把座位放回去重新开卖。
                // LATE_PAY 那条路径传 0，因为那些座位在订单取消时就已经释放过了，
                // 再放一次会把已售计数减成负数。
                1);

        if (created == 0) {
            Refund existing = refundMapper.selectByPaymentNo(payment.getPaymentNo());
            log.info("refund already exists: orderNo={}, refundNo={}",
                    payment.getOrderNo(), existing.getRefundNo());
            return existing.getRefundNo();
        }
        return refundNo;
    }

    /** 调用渠道退款，并在成功后进入 G3 结算。 */
    public void send(Refund refund) {
        PaymentChannel channel = channelFactory.get(refund.getChannel());

        // 原始交易号不在退款单上 —— 它属于支付单，复制一份就等于给渠道方发出的
        // 值造了第二个版本。在这里查出来，因为只有这一处需要它。
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

    /** 清理退款订单的 Redis 座位占用；失败只记录日志。 */
    private void clearSeatHold(String orderNo) {
        try {
            orderClient.releaseRefundedSeats(orderNo);
        } catch (Exception e) {
            log.error("退款成功但未能清掉座位占用：orderNo={}，"
                    + "座位在对账任务跑到之前无法再次售出", orderNo, e);
        }
    }

    /** 扫描到期退款并按退避策略重试。 */
    public int retryPending() {
        List<Refund> pending = refundMapper.selectRetryable(MAX_RETRIES, refundBatch);

        int sent = 0;
        for (Refund refund : pending) {
            try {
                send(refund);
                sent++;
            } catch (Exception e) {
                // 一笔坏掉的退款不能把整轮扫描停下来 —— 其余的同样欠着人家的钱。
                log.error("refund retry failed: refundNo={}", refund.getRefundNo(), e);
            }
        }

        if (sent > 0) {
            log.info("refund retry sweep: attempted {}", sent);
        }
        return sent;
    }

    /** 某个订单的退款列表，给控制台和订单页用。 */
    public List<Refund> ofOrder(String orderNo) {
        return refundMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Refund>lambdaQuery()
                        .eq(Refund::getOrderNo, orderNo)
                        .orderByDesc(Refund::getCreateTime));
    }

    // ------------------------------------------------------------

    /**
     * 记录一次失败的尝试。
     *
     * <p>下一次重试的时间由这条语句自己算出来，这样每个调用方拿到的是同一套节奏，
     * 而不是各写一遍。传进来的状态就是它随后变成的状态：要么还在重试，要么在重试
     * 次数用尽之后彻底失败。
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
