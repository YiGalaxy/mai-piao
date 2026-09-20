package com.maipiao.pay.channel;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 一个支付渠道方。
 *
 * <p>这个接口的意义在于：服务的其余部分不需要知道用的是哪家渠道。渠道之间有差异的一切 ——
 * 支付怎么创建、回调怎么验签、失败怎么上报 —— 都藏在这六个方法背后，所以接入支付宝
 * 意味着加一个类，而业务逻辑一行都不用改。
 *
 * <p>目前只有一个实现：对接本地替身网关的 {@code MockPaymentChannel}。
 * 支付宝沙箱那条路是<b>刻意留白</b>而不是写了一半 —— 一个默默返回成功的桩，比没有桩
 * 更糟：它会让「支持支付宝」看起来已经做完了，而实际上没有任何一笔钱会真的走通。
 * 真接的时候是新增一个实现类，业务代码一行不用改。
 */
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
     * <p>验签放在这里而不是调用方，因为只有渠道方自己知道它的签名是怎么算出来的。
     * 验签不通过的回调必须带着 {@code signVerified = false} 返回 —— 绝不能抛异常，
     * 因为无论哪种情况渠道方都需要一个答复。
     */
    NotifyResult parseNotify(String rawBody, Map<String, String> headers);

    /** 问渠道方到底发生了什么，给对账确认的扫描用。 */
    QueryResult query(String paymentNo);

    /** 发起一笔退款。 */
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
                return MOCK;
            }
        }
    }

    record PrepayCommand(String paymentNo, String orderNo, BigDecimal amount, String subject) {
    }

    record PrepayResult(String channelTradeNo, String payUrl, String rawResponse) {
    }

    /**
     * @param signVerified  验签没通过时为 false；调用方记日志并拒绝处理，
     *                      但渠道方仍然会拿到一个答复
     * @param status        渠道方上报的状态，SUCCESS 或 FAILED
     * @param channelTradeNo 渠道方的交易号，支付单的唯一性校验需要它
     */
    record NotifyResult(
            boolean signVerified,
            String paymentNo,
            String orderNo,
            String channelTradeNo,
            BigDecimal amount,
            String status,
            String rawBody
    ) {
        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status);
        }
    }

    record QueryResult(boolean found, String status, String channelTradeNo, BigDecimal amount) {
    }

    record RefundCommand(String refundNo, String paymentNo, String channelTradeNo,
                         BigDecimal amount, String reason) {
    }

    record RefundResult(boolean accepted, String channelRefundNo, String message) {
    }
}
