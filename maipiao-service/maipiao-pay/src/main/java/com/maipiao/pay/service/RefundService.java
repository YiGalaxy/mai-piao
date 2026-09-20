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
 * 退款：记录、发出、以及把失败的再退一次。
 *
 * <p>G3，也是 {@link PaymentTxService} 的对称面。形状一样，原因也一样：本地写入和
 * 远程写入必须一起提交或一起失败，所以带事务的那部分单独放在一个 bean 里，而不是放在
 * 一个会被 {@code this} 调用、从而丢掉代理的方法里。
 *
 * <p>操作顺序与支付刻意相反。支付是先落记录再动作；退款是先动作、再记录成已完成，
 * 因为拿主意的是渠道方。先写下来的是<b>意图</b> —— 那行退款单 —— 这样两者之间一旦
 * 崩溃，留下的是一个可以重试的起点，而不是一笔渠道方知道、我们却不知道的退款。
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

    /** 重试到这么多次之后，就必须有人来看一眼了。 */
    private static final int MAX_RETRIES = 5;

    @Value("${maipiao.pay.refund-batch:50}")
    private int refundBatch;

    // ------------------------------------------------------------

    /**
     * 记下一笔退款，然后发给渠道方。
     *
     * <p>幂等，而且保证幂等的是数据库上的唯一键（{@code payment_no}），不是这里
     * 任何一段判断。一笔订单只会有一笔成功的支付，所以「按支付单唯一」在实践中
     * 就等于「按订单唯一」—— 但机制在支付单上，只说「按订单幂等」会把这条链说丢。
     *
     * <p>约束放在数据库而不是写成「先查后做」，是因为后者在并发下会漏：两个请求
     * 同时查、都查到没有、都去插入。给用户退两次钱是这个类能干出的最坏的一件事。
     */
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

    /**
     * 在任何别的事情发生之前，先把退款行写下去。
     *
     * <p>幂等设计里的 L4：{@code payment_no} 上的唯一键让重复请求发生冲突，
     * 而不是造出第二笔退款。冲突本身就是答案，不是失败 —— 已经存在的那一行，
     * 正是调用方想要的。
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

    /**
     * 发出一笔退款，并根据结果做相应处理。
     *
     * <p>{@code paymentTxService.settleRefund} 之所以是另一个 bean，是因为它带着
     * {@code @GlobalTransactional}：从这里调它就属于自调用，代理会被绕过，注解会
     * 悄无声息地失效。正是这个错误造就了 {@link PaymentTxService} 这个类，
     * 不值得再犯第二次。
     */
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
     * 把还没退成功的退款再发一遍。
     *
     * <p>设计里的任务 9。退避是指数式的，而且由 {@code recordFailure} 在 SQL 里算好，
     * 所以这里只需要问「哪些到期了」。一笔把每次重试都用光的退款会被标记为失败并就此
     * 搁置：一个永远重试的循环，会把那唯一一件需要人来处理的事藏起来。
     */
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
