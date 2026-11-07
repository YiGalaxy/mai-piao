package com.maipiao.pay.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.channel.PaymentChannelFactory;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.entity.Refund;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 创建退款意图、调用渠道并协调 G3 结算；渠道结果未知时保留同一退款单供重试。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final RefundIntentService refundIntents;
    private final PaymentChannelFactory channelFactory;
    private final PaymentTxService paymentTxService;
    private final com.maipiao.pay.feign.OrderClient orderClient;

    @Value("${maipiao.pay.refund-batch:50}")
    private int refundBatch;

    // ------------------------------------------------------------

    /** 校验退款申请，按支付单唯一键创建或复用退款意图，再提交渠道退款。 */
    public String refund(String orderNo, BigDecimal amount) {
        Payment payment = paymentMapper.selectLatestByOrderNo(orderNo);
        if (payment == null || payment.getStatus() != Payment.STATUS_SUCCESS) {
            throw new BizException(ErrorCode.PAYMENT_NOT_FOUND, "订单没有成功的支付记录");
        }

        if (amount == null) amount = payment.getAmount();
        if (amount.signum() < 0 || amount.compareTo(payment.getAmount()) != 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "当前仅支持按实付金额整单退款");
        }
        var snapshot =
                com.maipiao.common.core.result.RemoteResult.requireSuccess(
                        orderClient.summary(orderNo), "核验退款申请");
        if (snapshot == null
                || snapshot.status() == null
                || (snapshot.status() != 5 && snapshot.status() != 6)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单未申请退款");
        }
        String refundNo = refundIntents.createRefund(payment, amount);

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

    /** 查询渠道退款结果；仅渠道明确未创建时才用原退款号发起退款。 */
    public void send(Refund refund) {
        if (refund == null
                || (refund.getStatus() != Refund.STATUS_PENDING
                        && refund.getStatus() != Refund.STATUS_REFUNDING)) return;
        PaymentChannel channel = channelFactory.get(refund.getChannel());

        // 原始渠道交易号保存在支付单中。
        Payment payment = paymentMapper.selectByPaymentNo(refund.getPaymentNo());
        String channelTradeNo = payment == null ? null : payment.getChannelTradeNo();

        PaymentChannel.RefundResult result;
        try {
            result = channel.queryRefund(refund.getRefundNo());
            // 仅明确未创建时发起；UNKNOWN 保留原退款号并继续查单。
            if (result != null && "NOT_FOUND".equals(result.status())) {
                result =
                        channel.refund(
                                new PaymentChannel.RefundCommand(
                                        refund.getRefundNo(),
                                        refund.getPaymentNo(),
                                        channelTradeNo,
                                        refund.getRefundAmount(),
                                        refund.getReason()));
            }
        } catch (Exception e) {
            log.error("refund call failed: refundNo={}", refund.getRefundNo(), e);
            recordFailure(refund, e.getMessage());
            return;
        }

        if (result == null
                || !"SUCCESS".equals(result.status())
                || result.channelRefundNo() == null) {
            if (result != null && "FAILED".equals(result.status())) {
                refundMapper.recordFailure(
                        refund.getRefundNo(), Refund.STATUS_FAILED, result.message());
            } else {
                recordFailure(refund, result == null ? "退款结果未知" : result.message());
            }
            return;
        }

        // G3 原子结清退款并更新订单和数据库库存账本。
        paymentTxService.settleRefund(refund, result.channelRefundNo());

        // 全局事务提交后再清 Redis；失败由 SeatSyncJob 补偿。
        if (Integer.valueOf(1).equals(refund.getReleaseSeat())) clearSeatHold(refund.getOrderNo());
    }

    /**
     * 清掉退款订单在 Redis 里的座位占用，失败由持久化任务继续补偿。
     *
     * <p>失败记录日志，seat_released 保持未完成，交由 SeatSyncJob 重试。 已完成的退款不能因为 Redis 暂不可用而重新发起渠道退款。
     */
    private void clearSeatHold(String orderNo) {
        try {
            com.maipiao.common.core.result.RemoteResult.requireSuccess(
                    orderClient.releaseRefundedSeats(orderNo), "释放退款座位");
        } catch (Exception e) {
            log.error("退款成功但未能清掉座位占用：orderNo={}，" + "座位在对账任务跑到之前无法再次售出", orderNo, e);
        }
    }

    /** 重试到期的退款；结果未知时沿用原退款号继续查询。 */
    public int retryPending() {
        List<Refund> pending = refundMapper.selectRetryable(refundBatch);

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

    /** 记录待核实结果并按 SQL 设置下次重试时间。 */
    private void recordFailure(Refund refund, String message) {
        // 结果未知保持处理中；渠道最终失败才转为 FAILED，不回退订单状态。
        refundMapper.recordFailure(
                refund.getRefundNo(),
                Refund.STATUS_REFUNDING,
                message == null ? "渠道结果未知" : message.substring(0, Math.min(500, message.length())));
        log.warn(
                "refund awaiting confirmation: refundNo={}, message={}",
                refund.getRefundNo(),
                message);
    }
}
