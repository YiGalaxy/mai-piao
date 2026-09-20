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
 * 支付的创建、回调与退款。
 *
 * <p>真正有意思的失败都集中在回调这条路径上，那里的每一道防线都是被某个具体的失败逼出来的：
 *
 * <ul>
 *   <li>同一个回调来了两次</li>
 *   <li>先失败后成功，顺序颠倒</li>
 *   <li>订单已经取消之后才成功</li>
 *   <li>验签不通过的回调</li>
 *   <li>金额与订单对不上的回调</li>
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
    // 创建支付
    // ============================================================

    /**
     * 为一个订单创建（或复用）支付单，并请渠道方把它开出来。
     *
     * <p>按订单幂等：用户刷新收银台页面拿到的还是同一个支付单、同一笔渠道交易，
     * 而不是又开一笔可以并行付款的单子。
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

        // 渠道方的交易号在这里就回写落库，这样即使回调在这个方法返回之前
        // 就到了，也还能被匹配上。
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
    // 回调
    // ============================================================

    /**
     * 处理渠道方发来的回调。
     *
     * <p>支付成功时以 G2 全局事务执行：把订单置为已支付、出票、确认座位占用，
     * 这三件事必须一起成立，否则就一件都不做。
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

        // ---- L1：日志去重，它只告诉我们「要不要重新处理」 ----
        NotifyLogService.RecordResult record =
                notifyLogService.record(channelType, notify.channelTradeNo(), "PAY", rawBody, true);

        if (record.alreadySucceeded()) {
            log.debug("callback already handled: paymentNo={}", notify.paymentNo());
            return channel.successResponse();
        }

        try {
            // 带事务的那一半在 PaymentTxService 里，必须经由它的代理调过去。
            // 在这里直接调会把代理绕开，全局事务就悄无声息地没了 —— 见那个类的说明。
            boolean success = notify.isSuccess()
                    ? paymentTxService.markPaid(notify)
                    : paymentTxService.markFailed(notify);
            notifyLogService.markProcessed(record.logId(),
                    success ? 1 : 0, success ? "handled" : "ignored");

            if (success && notify.isSuccess()) {
                // 放在事务之后，绝不放进事务里：Redis 回滚不了，写在事务里的标记
                // 会在回滚之后留下来，把座位钉死成已售，后面却什么都没有。
                // 尽力而为 —— 这里失败只记日志，不算致命，因为支付和订单本身已经是正确的。
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
     * 钱到账之后，把 Redis 里的座位占用标记为「已售」。
     *
     * <p>刻意放在全局事务之外。座位位已经占住了，所以少打一个标记并不会超卖；
     * 丢掉的只是「占用」和「售出」的区别 —— 而正是这个区别，拦住了后续的释放逻辑
     * 把已经付过钱的座位重新放回市场。
     */
    private void confirmSeatHold(String orderNo) {
        try {
            orderClient.confirmSeats(orderNo);
        } catch (Exception e) {
            log.error("could not confirm seat hold after payment: orderNo={}", orderNo, e);
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
