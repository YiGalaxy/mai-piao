package com.maipiao.mockpay.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假服务商的内存态。
 *
 * <p>刻意不做持久化。真实服务商的记录存在它那边，能扛过我们的重启；在这里，丢记录的
 * 后果是操作者得重新发起一笔支付，而这恰恰就是一个真实服务商丢了记录时会发生的事 ——
 * 所以这种故障是诚实的，不会误导人。
 */
@Slf4j
@Component
public class MockPayStore {

    /** 渠道交易号 -> 会话。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 支付单号 -> 渠道交易号，这样重试能找到同一个会话。 */
    private final Map<String, String> byPaymentNo = new ConcurrentHashMap<>();

    @Data
    public static class Session {
        private String channelTradeNo;
        private String paymentNo;
        private String orderNo;
        private BigDecimal amount;
        /** PENDING / SUCCESS / FAILED */
        private String status = "PENDING";
        private LocalDateTime createdAt;
        /** 这个会话已经投递过多少次回调。 */
        private AtomicInteger notifyCount = new AtomicInteger(0);
    }

    public Session createOrGet(String paymentNo, String orderNo, BigDecimal amount) {
        String existing = byPaymentNo.get(paymentNo);
        if (existing != null) {
            Session session = sessions.get(existing);
            if (session != null) {
                return session;
            }
        }

        Session session = new Session();
        // 渠道自己的标识，形态照着真实服务商来：不是我们的支付单号，也没法从它推出来。
        session.setChannelTradeNo("MOCK" + System.currentTimeMillis() + (int) (Math.random() * 1000));
        session.setPaymentNo(paymentNo);
        session.setOrderNo(orderNo);
        session.setAmount(amount);
        session.setCreatedAt(LocalDateTime.now());

        sessions.put(session.getChannelTradeNo(), session);
        byPaymentNo.put(paymentNo, session.getChannelTradeNo());
        return session;
    }

    public Session get(String channelTradeNo) {
        return sessions.get(channelTradeNo);
    }

    /** 按我们的支付单号查，给运维工具用。 */
    public Session findByPaymentNo(String paymentNo) {
        String channelTradeNo = byPaymentNo.get(paymentNo);
        return channelTradeNo == null ? null : sessions.get(channelTradeNo);
    }

    /** 假服务商见过的所有东西，最新的在前。 */
    public java.util.Collection<Session> list() {
        return sessions.values();
    }

    public void markSuccess(String channelTradeNo) {
        Session session = sessions.get(channelTradeNo);
        if (session != null) {
            session.setStatus("SUCCESS");
        }
    }

    public int nextNotifyCount(String channelTradeNo) {
        Session session = sessions.get(channelTradeNo);
        return session == null ? 0 : session.notifyCount.incrementAndGet();
    }
}
