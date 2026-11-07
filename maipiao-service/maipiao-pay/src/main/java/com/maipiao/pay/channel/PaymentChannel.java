package com.maipiao.pay.channel;

import java.math.BigDecimal;
import java.util.Map;

/** 渠道必须支持 paymentNo / refundNo 幂等。真实渠道接入还需验证状态映射和对账。 */
public interface PaymentChannel {

    /** 这是哪家渠道。与 {@code channel} 列对应。 */
    ChannelType type();

    /**
     * 在渠道方那边创建一笔支付。
     *
     * @return 把用户送去哪里，以及渠道方的交易号
     */
    PrepayResult prepay(PrepayCommand command);

    /**
     * 校验并解析一条回调。
     *
     * <p>验签放在这里而不是调用方，因为只有渠道方自己知道它的签名是怎么算出来的。 验签不通过的回调必须带着 {@code signVerified = false} 返回 ——
     * 绝不能抛异常， 因为无论哪种情况渠道方都需要一个答复。
     */
    NotifyResult parseNotify(String rawBody, Map<String, String> headers);

    /** 问渠道方到底发生了什么，给对账确认的扫描用。 */
    QueryResult query(String paymentNo);

    /** 关闭支付：仅明确 CLOSED 才允许本地关单；UNKNOWN 保持待核对。 */
    QueryResult close(String paymentNo);

    RefundResult queryRefund(String refundNo);

    /** 发起一笔退款；受理不代表结清。 */
    RefundResult refund(RefundCommand command);

    /** 成功后要回给渠道方的那段原文。支付宝要的是 "success"。 */
    String successResponse();

    /** 请渠道方稍后重试的那段原文。 */
    String failResponse();

    // ------------------------------------------------------------
    // 值类型
    // ------------------------------------------------------------

    enum ChannelType {
        MOCK,
        ALIPAY;

        public static ChannelType of(String value) {
            if (value == null) {
                return MOCK;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支持的支付渠道: " + value);
            }
        }
    }

    record PrepayCommand(
            String paymentNo,
            String orderNo,
            BigDecimal amount,
            String subject,
            java.time.LocalDateTime expireTime) {}

    record PrepayResult(String channelTradeNo, String payUrl, String rawResponse) {}

    /**
     * @param signVerified 验签没通过时为 false；调用方记日志并拒绝处理， 但渠道方仍然会拿到一个答复
     * @param status 渠道方上报的状态，SUCCESS 或 FAILED
     * @param channelTradeNo 渠道方的交易号，支付单的唯一性校验需要它
     */
    record NotifyResult(
            boolean signVerified,
            String paymentNo,
            String orderNo,
            String channelTradeNo,
            BigDecimal amount,
            String status,
            String rawBody) {
        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status);
        }
    }

    record QueryResult(boolean found, String status, String channelTradeNo, BigDecimal amount) {}

    record RefundCommand(
            String refundNo,
            String paymentNo,
            String channelTradeNo,
            BigDecimal amount,
            String reason) {}

    record RefundResult(boolean accepted, String channelRefundNo, String message, String status) {}
}
