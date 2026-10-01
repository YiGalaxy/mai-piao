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

/** 支付回调中的 Seata 事务操作，独立成 Bean 以确保调用经过 AOP 代理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final OrderClient orderClient;

    /** 应用成功回调；重复回调按已处理返回。 */
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

    /** G3：原子更新退款单、订单状态和座位账本；已结清时幂等返回。 */
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

    /** 区分重复回调、过期支付、金额不一致和未知支付。 */
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
