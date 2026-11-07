package com.maipiao.pay.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.pay.channel.PaymentChannel;
import com.maipiao.pay.channel.PaymentChannelFactory;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 支付的创建、回调与退款。
 *
 * <p>真正有意思的失败都集中在回调这条路径上，那里的每一道防线都是被某个具体的失败逼出来的：
 *
 * <ul>
 *   <li>同一个回调来了两次
 *   <li>先失败后成功，顺序颠倒
 *   <li>订单已经取消之后才成功
 *   <li>验签不通过的回调
 *   <li>金额与订单对不上的回调
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
    private final PaymentIntentService intents;
    private final PaymentSeatSyncService seatSync;

    @Value("${maipiao.pay.payment-minutes:15}")
    private int paymentMinutes;

    // ============================================================
    // 创建支付
    // ============================================================

    /**
     * 为一个订单创建（或复用）支付单，并请渠道方把它开出来。
     *
     * <p>按订单幂等：用户刷新收银台页面拿到的还是同一个支付单、同一笔渠道交易， 而不是又开一笔可以并行付款的单子。
     */
    public Payment createForOrder(
            String orderNo, Long userId, BigDecimal amount, String channelType) {

        var response = orderClient.summary(orderNo);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "无法确认订单，请稍后重试");
        }
        var order = response.getData();
        if (!userId.equals(order.userId())) throw new BizException(ErrorCode.FORBIDDEN);
        if (!orderNo.equals(order.orderNo())
                || order.status() == null
                || (order.status() != 0 && order.status() != 1)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态不允许支付");
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.lockExpireTime() == null || !order.lockExpireTime().isAfter(now)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单支付期限已过");
        }
        if (order.payAmount() == null || order.payAmount().signum() < 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单金额异常");
        }
        // 保留旧接口参数以兼容客户端，但前端金额不参与计费。
        amount = order.payAmount();
        LocalDateTime expires = now.plusMinutes(paymentMinutes);
        if (expires.isAfter(order.lockExpireTime())) expires = order.lockExpireTime();

        Payment existing = paymentMapper.selectLatestByOrderNo(orderNo);
        if (existing != null)
            validateReusable(existing, userId, amount, order.lockExpireTime(), now);
        PaymentChannel channel = channelFactory.get(channelType);
        Payment payment =
                existing != null
                        ? existing
                        : intents.getOrCreate(
                                orderNo, userId, amount, channel.type().name(), expires);
        validateReusable(payment, userId, amount, order.lockExpireTime(), now);
        if (!channel.type().name().equals(payment.getChannel())) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "请使用原支付渠道核对原支付单");
        }
        if (payment.getChannelTradeNo() == null || payment.getCashierUrl() == null) {
            // 渠道必须按 paymentNo 幂等；本地意图已经提交，响应丢失后仍然使用原身份恢复。
            PaymentChannel.PrepayResult prepay =
                    channel.prepay(
                            new PaymentChannel.PrepayCommand(
                                    payment.getPaymentNo(),
                                    orderNo,
                                    amount,
                                    "麦票订单 " + orderNo,
                                    payment.getExpireTime()));
            if (prepay.channelTradeNo() == null
                    || prepay.channelTradeNo().isBlank()
                    || prepay.payUrl() == null
                    || prepay.payUrl().isBlank()
                    || paymentMapper.bindChannel(
                                    payment.getPaymentNo(),
                                    prepay.channelTradeNo(),
                                    prepay.payUrl())
                            != 1) {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "渠道支付身份异常，请核对原支付结果");
            }
            payment.setChannelTradeNo(prepay.channelTradeNo());
            payment.setCashierUrl(prepay.payUrl());
        }

        return payment;
    }

    // ============================================================
    // 回调
    // ============================================================

    /**
     * 处理渠道方发来的回调。
     *
     * <p>支付记录、订单已支付状态与出票经 G2 全局事务提交， Redis 座位确认在提交后执行，由持久化任务补偿失败。
     *
     * @return 要回给渠道方的响应体
     */
    public String handleNotify(String channelType, String rawBody, Map<String, String> headers) {

        PaymentChannel channel = channelFactory.get(channelType);

        // ---- 验签 ----
        PaymentChannel.NotifyResult notify = channel.parseNotify(rawBody, headers);
        if (!notify.signVerified()) {
            // 照实记录下「验签失败」，让它看得见，但不做任何业务处理。回给渠道方的是
            // 「请重试」，而重试也不会有用 —— 这正是要记日志而不是默默放过的原因。
            log.warn("rejected callback with bad signature: channel={}", channelType);
            notifyLogService.record(channelType, notify.channelTradeNo(), "PAY", rawBody, false);
            return channel.failResponse();
        }

        if (notify.paymentNo() == null) {
            log.warn("callback carried no payment number: {}", rawBody);
            return channel.failResponse();
        }

        // 日志用于审计，不能用一次 FAILED 的处理记录挡住后续 SUCCESS。
        NotifyLogService.RecordResult record =
                notifyLogService.record(channelType, notify.channelTradeNo(), "PAY", rawBody, true);

        try {
            Payment local = paymentMapper.selectByPaymentNo(notify.paymentNo());
            if (local == null
                    || !channel.type().name().equals(local.getChannel())
                    || !java.util.Objects.equals(local.getOrderNo(), notify.orderNo())
                    || local.getChannelTradeNo() == null
                    || !local.getChannelTradeNo().equals(notify.channelTradeNo())
                    || notify.amount() == null
                    || local.getAmount().compareTo(notify.amount()) != 0
                    || !(notify.isSuccess() || "FAILED".equalsIgnoreCase(notify.status()))) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "支付通知与本地记录不一致");
            }
            // 带事务的那一半在 PaymentTxService 里，必须经由它的代理调过去。
            // 在这里直接调会把代理绕开，全局事务就悄无声息地没了 —— 见那个类的说明。
            boolean success =
                    notify.isSuccess()
                            ? paymentTxService.markPaid(notify)
                            : paymentTxService.markFailed(notify);
            notifyLogService.markProcessed(
                    record.logId(), success ? 1 : 0, success ? "handled" : "ignored");

            Payment settled =
                    success && notify.isSuccess()
                            ? paymentMapper.selectByPaymentNo(notify.paymentNo())
                            : null;
            if (settled != null && settled.getStatus() == Payment.STATUS_SUCCESS) {
                // G2 提交后再确认 Redis；失败由 SeatSyncJob 补偿。
                confirmSeatHold(notify.paymentNo());
            }

            return success ? channel.successResponse() : channel.failResponse();
        } catch (Exception e) {
            log.error("callback handling failed: paymentNo={}", notify.paymentNo(), e);
            notifyLogService.markFailed(record.logId(), e.getMessage());
            return channel.failResponse();
        }
    }

    /**
     * 钱到账之后，把 Redis 里的座位占用标记为「已售」。
     *
     * <p>刻意放在全局事务之外。失败时保留 seat_synced 未完成标记， 后续由 SeatSyncJob 核验订单状态并重试。
     */
    private void confirmSeatHold(String paymentNo) {
        try {
            seatSync.synchronize(paymentNo);
        } catch (Exception e) {
            log.error("could not confirm seat hold after payment: paymentNo={}", paymentNo, e);
        }
    }

    // ============================================================

    private void validateReusable(
            Payment payment,
            Long userId,
            BigDecimal amount,
            LocalDateTime deadline,
            LocalDateTime now) {
        if (payment == null
                || !userId.equals(payment.getUserId())
                || payment.getAmount().compareTo(amount) != 0
                || payment.getExpireTime() == null
                || !payment.getExpireTime().isAfter(now)
                || payment.getExpireTime().isAfter(deadline)
                || (payment.getStatus() != Payment.STATUS_PENDING
                        && payment.getStatus() != Payment.STATUS_FAILED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "原支付单不可复用，请核对原支付结果");
        }
    }

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
