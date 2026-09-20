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
 * In-memory state of the fake provider.
 *
 * <p>Deliberately not persisted. A real provider's records live on their side
 * and survive our restarts; here, losing them means the operator has to start
 * a new payment, which is exactly what would happen if a real provider lost
 * its records - so the failure is honest rather than misleading.
 */
@Slf4j
@Component
public class MockPayStore {

    /** Channel trade number -> session. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Payment number -> channel trade number, so a retry finds the same session. */
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
        /** How many times a callback has been delivered for this session. */
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
        // The channel's own identifier, in the shape a real provider uses:
        // not our payment number, and not derivable from it.
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

    /** By our payment number, for the operator tooling. */
    public Session findByPaymentNo(String paymentNo) {
        String channelTradeNo = byPaymentNo.get(paymentNo);
        return channelTradeNo == null ? null : sessions.get(channelTradeNo);
    }

    /** Everything the fake provider has seen, newest first. */
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
