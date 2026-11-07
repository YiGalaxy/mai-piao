package com.maipiao.order.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.dto.ReservationDtos;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.order.dto.OrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderReservation;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.SeatClient;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.mapper.OrderReservationMapper;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.api.GlobalTransaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 预约状态独立提交；只有持久化 PROCESSING+xid 成功后才允许业务分支执行。 不使用可过期的执行锁：旧 XID 未被 TC 确认结束时，重试和清扫都不能接管。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationService {
    private final OrderReservationMapper reservations;
    private final OrderMapper orders;
    private final OrderService orderService;
    private final SeatClient seatClient;
    private final CreationTransactions transactions;
    private final ObjectMapper json;

    public void prepare(ReservationDtos.Prepare request) {
        outside(
                () -> {
                    long now = System.currentTimeMillis();
                    if (request.token() == null
                            || request.token().isBlank()
                            || request.token().length() > 64
                            || request.userId() == null
                            || request.scheduleId() == null
                            || request.expiresAt() <= now
                            || request.expiresAt() > now + 3600000) {
                        throw invalid("无效的预约登记");
                    }
                    OrderReservation row = new OrderReservation();
                    row.setToken(request.token());
                    row.setUserId(request.userId());
                    row.setScheduleId(request.scheduleId());
                    row.setExpiresAt(request.expiresAt());
                    reservations.prepare(row);
                    OrderReservation saved = require(request.token(), request.userId());
                    if (!saved.getScheduleId().equals(request.scheduleId())
                            || saved.getExpiresAt() != request.expiresAt()
                            || !"PREPARING".equals(saved.getState())) {
                        throw invalid("预约已关闭或登记参数不一致");
                    }
                    return null;
                });
    }

    public void publish(ReservationDtos.Publish request) {
        outside(
                () -> {
                    List<Integer> indexes = canonical(request.seatIndexes());
                    if (request.seatLabels() == null
                            || request.seatLabels().size() != indexes.size()) {
                        throw invalid("座位标签不完整");
                    }
                    Map<Integer, String> labels = new HashMap<>();
                    for (int i = 0; i < request.seatIndexes().size(); i++) {
                        labels.put(request.seatIndexes().get(i), request.seatLabels().get(i));
                    }
                    String seats = encode(indexes);
                    String seatLabels = encode(indexes.stream().map(labels::get).toList());
                    if (reservations.publish(
                                    request.token(), seats, seatLabels, System.currentTimeMillis())
                            != 1) {
                        OrderReservation saved = reservations.find(request.token());
                        if (saved == null
                                || !"RESERVED".equals(saved.getState())
                                || saved.getExpiresAt() <= System.currentTimeMillis()
                                || !seats.equals(saved.getSeatIndexes())
                                || !seatLabels.equals(saved.getSeatLabels())) {
                            throw invalid("预约已到期、已关闭或座位不一致");
                        }
                    }
                    return null;
                });
    }

    public OrderDtos.CreationResult create(OrderDtos.CreateOrderRequest request, Long userId) {
        requireNoLocalTransaction();
        if (RootContext.inGlobalTransaction()) throw invalid("创建入口不能加入已有全局事务");
        OrderReservation row = require(request.lockToken(), userId);
        if (!row.getScheduleId().equals(request.scheduleId())
                || !Objects.equals(
                        row.getSeatIndexes(), encode(canonical(request.seatIndexes())))) {
            throw invalid("场次或座位与预约不一致");
        }
        String hash = fingerprint(request);
        reconcile(row);
        row = require(request.lockToken(), userId);
        boolean revise =
                Boolean.TRUE.equals(request.revise()) && "RETRYABLE".equals(row.getState());
        if (row.getRequestHash() != null && !row.getRequestHash().equals(hash) && !revise) {
            throw invalid("请求参数已改变；仅确认回滚后可明确修改优惠券重试");
        }
        if (!Set.of("RESERVED", "RETRYABLE").contains(row.getState())
                || row.getExpiresAt() <= System.currentTimeMillis()) {
            return status(request.lockToken(), userId);
        }

        GlobalTransaction tx = null;
        String xid = null;
        boolean claimed = false;
        boolean commitRequested = false;
        try {
            tx = transactions.begin();
            xid = tx.getXid();
            final String attemptXid = xid;
            claimed =
                    outside(
                                    () ->
                                            reservations.claim(
                                                    request.lockToken(),
                                                    attemptXid,
                                                    hash,
                                                    System.currentTimeMillis(),
                                                    revise))
                            == 1;
            if (!claimed) {
                tx.rollback();
            } else {
                // 使用锁座服务登记的标签；前端标签和优惠金额不作为权威数据。
                var normalized =
                        new OrderDtos.CreateOrderRequest(
                                request.lockToken(),
                                row.getScheduleId(),
                                decode(row.getSeatIndexes(), new TypeReference<List<Integer>>() {}),
                                decode(row.getSeatLabels(), new TypeReference<List<String>>() {}),
                                request.couponId(),
                                null,
                                request.revise());
                orderService.createReserved(normalized, userId, deadline(row));
                commitRequested = true;
                tx.commit();
                // Seata 的 commit() 返回 void，正常返回仍需检查 TC 返回的本地状态。
                GlobalStatus outcome = tx.getLocalStatus();
                if (committed(outcome)) finish(request.lockToken(), xid, "SUCCEEDED");
                else if (rolledBack(outcome)) finish(request.lockToken(), xid, "RETRYABLE");
            }
        } catch (Exception e) {
            log.warn(
                    "order attempt did not return success: token={}, xid={}",
                    request.lockToken(),
                    xid,
                    e);
            // 提交请求可能已经生效，不能在 commit 超时后改发 rollback。
            if (tx != null && !commitRequested) {
                try {
                    tx.rollback();
                    GlobalStatus local = tx.getLocalStatus();
                    if (claimed && rolledBack(local)) finish(request.lockToken(), xid, "RETRYABLE");
                } catch (Exception rollbackError) {
                    log.error(
                            "rollback outcome unresolved: token={}, xid={}",
                            request.lockToken(),
                            xid,
                            rollbackError);
                }
            }
        } finally {
            if (xid != null && xid.equals(RootContext.getXID())) RootContext.unbind();
        }
        return status(request.lockToken(), userId);
    }

    public OrderDtos.CreationResult status(String token, Long userId) {
        requireNoLocalTransaction();
        OrderReservation row = require(token, userId);
        reconcile(row);
        row = require(token, userId);
        if (Set.of("PREPARING", "RESERVED", "RETRYABLE").contains(row.getState())
                && row.getExpiresAt() <= System.currentTimeMillis()) {
            reservations.close(token, System.currentTimeMillis(), false);
            row = require(token, userId);
        }
        Order order = "SUCCEEDED".equals(row.getState()) ? orders.selectByOrderNo(token) : null;
        boolean held = false;
        if (Set.of("RESERVED", "RETRYABLE").contains(row.getState())) {
            try {
                var response =
                        seatClient.verify(
                                row.getScheduleId(),
                                token,
                                decode(
                                        row.getSeatIndexes(),
                                        new TypeReference<List<Integer>>() {}));
                held =
                        response != null
                                && response.isSuccess()
                                && response.getData() != null
                                && Boolean.TRUE.equals(response.getData().get("held"));
            } catch (Exception e) {
                log.warn("cannot verify retained seats: {}", token);
            }
        }
        String message =
                switch (row.getState()) {
                    case "SUCCEEDED" -> "订单已创建，请查看订单当前状态";
                    case "PROCESSING" -> "下单结果确认中，请查询原请求，不要重新选座或重复下单";
                    case "CLOSED", "RELEASED" -> "预约已关闭，请重新选座";
                    case "RETRYABLE" ->
                            held ? "上次下单已回滚，座位仍保留至原截止时间，可重试或调整优惠券" : "上次下单已回滚，暂时无法确认座位，请稍后查询";
                    default -> held ? "座位已保留，请在截止前下单" : "暂时无法确认座位，请稍后查询";
                };
        return new OrderDtos.CreationResult(
                row.getState(),
                row.getExpiresAt(),
                held,
                order == null ? null : order.getOrderNo(),
                order == null ? null : order.getPayAmount(),
                order == null ? null : order.getLockExpireTime(),
                order == null ? null : order.getStatus(),
                message);
    }

    /** 返回 true 才允许座位服务执行 Lua 释放。无订单的孤儿也先写 CLOSED 墓碑阻断迟到登记。 */
    public boolean close(String token, Long scheduleId, Long userId, boolean abandon) {
        requireNoLocalTransaction();
        OrderReservation row = reservations.find(token);
        if (userId != null) {
            row = require(token, userId);
            if (!row.getScheduleId().equals(scheduleId)) throw invalid("场次不一致");
        }
        if (row != null) {
            if (!row.getScheduleId().equals(scheduleId)) throw invalid("场次不一致");
            reconcile(row);
            row = reservations.find(token);
            if ("PROCESSING".equals(row.getState())) return false;
            if (!"SUCCEEDED".equals(row.getState())) {
                reservations.close(token, System.currentTimeMillis(), abandon);
                return Set.of("CLOSED", "RELEASED").contains(reservations.find(token).getState());
            }
        }
        Order order = orders.selectByOrderNo(token);
        if (order != null) {
            if (!order.getScheduleId().equals(scheduleId)) throw invalid("场次不一致");
            if (userId != null && !order.getUserId().equals(userId))
                throw new BizException(ErrorCode.FORBIDDEN);
            if (OrderStatus.CANCELLABLE.contains(order.getStatus())) {
                if (!abandon && order.getLockExpireTime().isAfter(LocalDateTime.now()))
                    return false;
                orderService.cancel(token, order.getUserId(), abandon);
                order = orders.selectByOrderNo(token);
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                // 取消写库已经确认提交；下游故障可由下一轮重复执行。
                return orderService.releaseCancelledResources(order);
            }
            return false; // 已支付/退款中等状态绝不由普通清扫释放。
        }
        if (row == null) {
            reservations.closeOrphan(token, scheduleId);
            // 与 prepare 并发时，必须重新读状态，不能把登记失败当作不存在。
            return Set.of("CLOSED", "RELEASED").contains(reservations.find(token).getState());
        }
        // SUCCEEDED 却没有订单是异常，不能当作失败释放。
        return false;
    }

    private void reconcile(OrderReservation row) {
        if (!"PROCESSING".equals(row.getState()) || row.getXid() == null) return;
        try {
            GlobalStatus state = transactions.status(row.getXid());
            if (committed(state)) finish(row.getToken(), row.getXid(), "SUCCEEDED");
            else if (rolledBack(state)) finish(row.getToken(), row.getXid(), "RETRYABLE");
            else
                log.debug(
                        "unresolved transaction: token={}, xid={}, status={}",
                        row.getToken(),
                        row.getXid(),
                        state);
        } catch (Exception e) {
            log.warn(
                    "TC unavailable; retain reservation: token={}, xid={}",
                    row.getToken(),
                    row.getXid());
        }
    }

    @Scheduled(fixedDelayString = "${maipiao.order.reservation-interval-ms:30000}")
    public void recover() {
        for (OrderReservation row : reservations.pending(System.currentTimeMillis(), 200)) {
            try {
                reconcile(row);
                if (close(row.getToken(), row.getScheduleId(), null, false)) {
                    var result = seatClient.release(row.getScheduleId(), row.getToken());
                    if (result == null || !result.isSuccess()) throw invalid("释放座位失败");
                    reservations.released(row.getToken());
                }
            } catch (Exception e) {
                log.error("reservation recovery failed: {}", row.getToken(), e);
            } finally {
                reservations.touch(row.getToken());
            }
        }
    }

    private void finish(String token, String xid, String state) {
        outside(() -> reservations.finish(token, xid, state));
    }

    private static boolean rolledBack(GlobalStatus state) {
        return state == GlobalStatus.Rollbacked || state == GlobalStatus.TimeoutRollbacked;
    }

    private static boolean committed(GlobalStatus state) {
        // AT AsyncCommitting 已作出提交决定，只剩清理 undo log；不会再回滚业务行。
        return state == GlobalStatus.Committed || state == GlobalStatus.AsyncCommitting;
    }

    private OrderReservation require(String token, Long userId) {
        OrderReservation row = reservations.find(token);
        if (row == null) throw new BizException(ErrorCode.SEAT_LOCK_EXPIRED, "预约不存在，请重新选座");
        if (!row.getUserId().equals(userId)) throw new BizException(ErrorCode.FORBIDDEN);
        return row;
    }

    private String fingerprint(OrderDtos.CreateOrderRequest request) {
        try {
            String data =
                    request.scheduleId()
                            + ":"
                            + encode(canonical(request.seatIndexes()))
                            + ":"
                            + request.couponId();
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Integer> canonical(List<Integer> seats) {
        if (seats == null
                || seats.isEmpty()
                || seats.size() > 6
                || seats.stream().anyMatch(i -> i == null || i < 0)
                || new HashSet<>(seats).size() != seats.size()) {
            throw invalid("座位列表无效或重复");
        }
        return seats.stream().sorted().toList();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("预约数据损坏", e);
        }
    }

    private LocalDateTime deadline(OrderReservation row) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(row.getExpiresAt()), ZoneId.systemDefault());
    }

    /** 解绑后新 SQL 自动提交，避免被 AT 作为分支一起撤销。入口不允许 Spring 本地事务。 */
    private <T> T outside(Supplier<T> operation) {
        requireNoLocalTransaction();
        String xid = RootContext.unbind();
        try {
            return operation.get();
        } finally {
            if (xid != null) RootContext.bind(xid);
        }
    }

    private void requireNoLocalTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("预约协调操作不能加入 Spring 本地事务");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.PARAM_ERROR, message);
    }
}
