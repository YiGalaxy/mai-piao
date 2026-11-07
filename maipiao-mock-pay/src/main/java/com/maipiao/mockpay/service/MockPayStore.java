package com.maipiao.mockpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 单实例开发渠道，状态原子落盘；删除状态文件等同于丢失渠道账本。 */
@Component
@RequiredArgsConstructor
public class MockPayStore {
    private final ObjectMapper json;

    @Value("${maipiao.mock-pay.state-file:data/mock-pay/state.json}")
    private String stateFile;

    private State state = new State();
    private boolean healthy = true;
    private final Map<String, String> byPaymentNo = new HashMap<>();

    @Data
    public static class State {
        private Map<String, Session> sessions = new HashMap<>();
        private Map<String, RefundRecord> refunds = new HashMap<>();
    }

    @Data
    public static class Session {
        private String channelTradeNo;
        private String paymentNo;
        private String orderNo;
        private BigDecimal amount;
        private String status = "PENDING";
        private LocalDateTime createdAt;
        private LocalDateTime expireTime;
        private AtomicInteger notifyCount = new AtomicInteger();
    }

    @Data
    public static class RefundRecord {
        private String refundNo;
        private String paymentNo;
        private BigDecimal amount;
        private String channelRefundNo;
        private String status = "SUCCESS";
    }

    @PostConstruct
    public synchronized void load() throws Exception {
        Path file = Path.of(stateFile);
        if (Files.exists(file)) state = json.readValue(file.toFile(), State.class);
        for (Session session : state.sessions.values())
            byPaymentNo.put(session.paymentNo, session.channelTradeNo);
    }

    private void requireHealthy() {
        if (!healthy) throw new IllegalStateException("模拟渠道存储异常，请修复存储后重启");
    }

    private void save() {
        requireHealthy();
        try {
            Path file = Path.of(stateFile).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] bytes = json.writeValueAsBytes(state);
            try (var out =
                    java.nio.channels.FileChannel.open(
                            temp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // 内存已发生变化但落盘未确认，停止对外报告结果，重启从最后持久化账本恢复。
            healthy = false;
            throw new IllegalStateException("模拟渠道账本未能持久化", e);
        }
    }

    public synchronized Session createOrGet(
            String paymentNo, String orderNo, BigDecimal amount, LocalDateTime expires) {
        if (paymentNo == null
                || orderNo == null
                || amount == null
                || amount.signum() < 0
                || expires == null) throw new IllegalArgumentException("支付参数不完整");
        Session existing = findByPaymentNo(paymentNo);
        if (existing != null) {
            if (!orderNo.equals(existing.orderNo)
                    || amount.compareTo(existing.amount) != 0
                    || !expires.equals(existing.expireTime))
                throw new IllegalArgumentException("重复支付身份的金额、归属或期限不一致");
            save();
            return existing;
        }
        if (!expires.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("支付已过期");
        Session session = new Session();
        session.channelTradeNo = "MOCK" + UUID.randomUUID().toString().replace("-", "");
        session.paymentNo = paymentNo;
        session.orderNo = orderNo;
        session.amount = amount;
        session.createdAt = LocalDateTime.now();
        session.expireTime = expires;
        state.sessions.put(session.channelTradeNo, session);
        byPaymentNo.put(paymentNo, session.channelTradeNo);
        save();
        return session;
    }

    public synchronized Session get(String tradeNo) {
        requireHealthy();
        return state.sessions.get(tradeNo);
    }

    public synchronized Session findByPaymentNo(String paymentNo) {
        requireHealthy();
        return state.sessions.get(byPaymentNo.get(paymentNo));
    }

    public synchronized Collection<Session> list() {
        requireHealthy();
        return List.copyOf(state.sessions.values());
    }

    public synchronized boolean markSuccess(String tradeNo) {
        Session session = get(tradeNo);
        if (session == null || "CLOSED".equals(session.status)) return false;
        if (!"SUCCESS".equals(session.status) && !session.expireTime.isAfter(LocalDateTime.now())) {
            session.status = "CLOSED";
            save();
            return false;
        }
        session.status = "SUCCESS";
        save();
        return true;
    }

    public synchronized Session close(String paymentNo) {
        Session session = findByPaymentNo(paymentNo);
        if (session != null && !"SUCCESS".equals(session.status)) session.status = "CLOSED";
        save();
        return session;
    }

    public synchronized RefundRecord refund(
            String refundNo, String paymentNo, String tradeNo, BigDecimal amount) {
        Session payment = findByPaymentNo(paymentNo);
        if (refundNo == null
                || payment == null
                || !"SUCCESS".equals(payment.status)
                || !Objects.equals(tradeNo, payment.channelTradeNo)
                || amount == null
                || amount.compareTo(payment.amount) != 0)
            throw new IllegalArgumentException("退款与成功支付不一致");
        RefundRecord existing = state.refunds.get(refundNo);
        if (existing != null) {
            if (!paymentNo.equals(existing.paymentNo) || amount.compareTo(existing.amount) != 0)
                throw new IllegalArgumentException("退款身份冲突");
            save();
            return existing;
        }
        if (state.refunds.values().stream().anyMatch(r -> paymentNo.equals(r.paymentNo)))
            throw new IllegalArgumentException("该支付已存在其他退款号");
        RefundRecord refund = new RefundRecord();
        refund.refundNo = refundNo;
        refund.paymentNo = paymentNo;
        refund.amount = amount;
        refund.channelRefundNo = "MOCKREFUND" + refundNo;
        state.refunds.put(refundNo, refund);
        save();
        return refund;
    }

    public synchronized RefundRecord findRefund(String refundNo) {
        requireHealthy();
        return state.refunds.get(refundNo);
    }

    public synchronized int nextNotifyCount(String tradeNo) {
        Session session = get(tradeNo);
        return session == null ? 0 : session.notifyCount.incrementAndGet();
    }
}
