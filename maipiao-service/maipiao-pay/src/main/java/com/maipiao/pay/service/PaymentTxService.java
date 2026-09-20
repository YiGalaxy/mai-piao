package com.maipiao.pay.service;

import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 支付回调里带事务的那一半。
 *
 * <p>它被拆成一个独立的 bean，而不是 {@link PaymentService} 上的私有方法，这就是它存在的
 * 全部理由。{@code @GlobalTransactional} 是由 Spring AOP 代理施加的，而一个 bean 内部
 * 方法之间的调用永远到不了代理 —— 于是这个注解就被悄悄忽略了。在这里，那意味着 G2 实际
 * 上是三个互不相干的本地事务：支付可能提交了而订单没有，也没有任何东西会去回滚。没有报错，
 * 也没有日志；唯一的症状是一行没人会去找的 "Begin new global transaction" 不见了。
 *
 * <p>跨 bean 边界的调用才能让这次调用重新经过代理。任何把这些方法合并回
 * {@code PaymentService} 的重构，都会把这个 bug 带回来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OrderClient orderClient;

    /**
     * 应用一个「成功」回调。
     *
     * <p>无事可做时返回 {@code false} —— 从渠道方的角度看这就是成功，也必须按成功
     * 答复它，否则它会一直重试一个早就应用过的回调。
     */
    @GlobalTransactional(name = "pay-success-ticket", rollbackFor = Exception.class, timeoutMills = 30000)
    public boolean markPaid(PaymentChannel.NotifyResult notify) {

        // ---- L2：状态 CAS ----
        int rows = paymentMapper.casPaySuccess(
                notify.paymentNo(), notify.channelTradeNo(), notify.amount(),
                LocalDateTime.now());

        if (rows == 0) {
            return handleCasMiss(notify);
        }

        // ---- G2：订单转已支付、账本转已售，然后出票 ----
        // markPaid 在改订单状态的同时，也把座位账本从「锁定」推到了「已售」；
        // 少了这一步，就是之前让付过钱的座位一直被算作仅仅是占用的原因。
        orderClient.markPaid(notify.orderNo(), LocalDateTime.now());
        orderClient.issueTickets(notify.orderNo(), notify.paymentNo());

        log.info("payment succeeded: paymentNo={}, orderNo={}, amount={}",
                notify.paymentNo(), notify.orderNo(), notify.amount());
        return true;
    }

    /**
     * G3：渠道方已经把退的钱还回去了，订单那边也要如实反映。
     *
     * <p>三处写入描述的是同一个事实，它们要么一起提交，要么一起失败：
     *
     * <ol>
     *   <li>退款单转入已结清，并以金额为守卫，这样一笔金额对不上的确认
     *       就没法把另一笔退款给结掉；</li>
     *   <li>订单从 REFUNDING 走到 REFUNDED，它的座位账本行从已售退回可选；</li>
     *   <li>已售计数减下来 —— 这一步才真正把座位放回市场，而不只是把它们标记成空闲。</li>
     * </ol>
     *
     * <p>这里不碰 Redis。清掉占用是调用方的事，在这一步提交之后 —— 见
     * {@code OrderRefundService.clearSeatHold}。在事务里清掉的位图会在回滚后残留下来，
     * 让座位挂出去卖，而订单却还读作已支付。
     *
     * <p>幂等：已经结清的退款直接返回、什么都不做，因为重试扫描和最初的请求都
     * 可能走到这里。
     */
    @GlobalTransactional(name = "refund-settle", rollbackFor = Exception.class, timeoutMills = 30000)
    public void settleRefund(Refund refund, String channelRefundNo) {
        int rows = refundMapper.casRefundSuccess(
                refund.getRefundNo(), channelRefundNo, refund.getRefundAmount());

        if (rows == 0) {
            log.info("refund already settled, nothing to do: refundNo={}", refund.getRefundNo());
            return;
        }

        // 订单那一侧。只调一次，因为「订单变成 REFUNDED」和「座位重新可售」
        // 本来就是同一个事实 —— 把它们拆开，就是一笔已支付订单的座位被别人买走的原因。
        orderClient.markRefunded(refund.getOrderNo(), refund.getRefundAmount());

        log.info("refund settled: refundNo={}, orderNo={}, amount={}",
                refund.getRefundNo(), refund.getOrderNo(), refund.getRefundAmount());
    }

    /** 应用一个「失败」回调。绝不覆盖已有的成功。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(PaymentChannel.NotifyResult notify) {
        int rows = paymentMapper.casPayFailed(notify.paymentNo());
        if (rows == 0) {
            log.debug("failure callback ignored, payment not open: paymentNo={}", notify.paymentNo());
        }
        return true;
    }

    /**
     * 判断 CAS 一行都没匹配上，到底是哪种情况。
     *
     * <p>「改了 0 行」背后藏着五种不同的境况，而它们需要四种不同的应对。最要紧的是
     * {@code LATE_PAY}：钱在订单取消之后才到，这笔钱必须退回去，不能留下。
     */
    private boolean handleCasMiss(PaymentChannel.NotifyResult notify) {
        Payment payment = paymentMapper.selectByPaymentNo(notify.paymentNo());

        if (payment == null) {
            // 一笔我们毫无记录的支付却来了回调。值得大声报出来：要么是个 bug，
            // 要么是有人在伪造回调。
            log.error("callback for unknown payment: paymentNo={}", notify.paymentNo());
            return false;
        }

        if (payment.getStatus() == Payment.STATUS_SUCCESS) {
            boolean sameTrade = notify.channelTradeNo() != null
                    && notify.channelTradeNo().equals(payment.getChannelTradeNo());
            boolean sameAmount = notify.amount() != null
                    && notify.amount().compareTo(payment.getAmount()) == 0;

            if (sameTrade && sameAmount) {
                // 一次单纯的重复。无事可做，而且渠道方应当停止重试，
                // 所以这里按「已处理」上报。
                log.debug("duplicate callback: paymentNo={}", notify.paymentNo());
                return true;
            }
            // 已经是成功状态，但渠道交易号或金额和这次上报的对不上。
            // 上游出了问题。
            log.error("payment already succeeded with different details: paymentNo={}, "
                            + "recordedTrade={}, notifiedTrade={}, recordedAmount={}, notifiedAmount={}",
                    notify.paymentNo(), payment.getChannelTradeNo(), notify.channelTradeNo(),
                    payment.getAmount(), notify.amount());
            return false;
        }

        if (payment.getStatus() == Payment.STATUS_CLOSED) {
            // 最危险的那种：支付窗口过期了，订单被取消、座位也重新放出去卖了，
            // 用户却还是把钱付了。这笔钱必须退 —— 留下它就等于让人为一堆
            // 根本不存在的票付了钱。
            log.warn("payment arrived after close, refunding: paymentNo={}, orderNo={}",
                    notify.paymentNo(), payment.getOrderNo());
            refundMapper.insertIfAbsent(
                    SnowflakeIdGenerator.next(),
                    SnowflakeIdGenerator.nextString(),
                    payment.getPaymentNo(),
                    payment.getOrderNo(),
                    payment.getUserId(),
                    payment.getChannel(),
                    payment.getAmount(),
                    Refund.REASON_TIME_OUT_PAID,
                    // 绝不放座位：订单超时的时候它们就已经被释放过了，
                    // 再放一次会让一个早就不指向它们的计数再减一遍。
                    0);
            return true;
        }

        // 金额对不上，或者处在预期外的状态。不能接受。
        log.warn("callback could not be applied: paymentNo={}, status={}",
                notify.paymentNo(), payment.getStatus());
        return false;
    }
}
