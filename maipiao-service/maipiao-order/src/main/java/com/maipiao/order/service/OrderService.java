package com.maipiao.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.order.dto.OrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.MovieClient;
import com.maipiao.order.feign.SeatClient;
import com.maipiao.order.feign.UserClient;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.mapper.OrderMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Order creation and the surrounding lifecycle.
 *
 * <p>The interesting part is {@link #create}, which is the G1 global
 * transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStateMachine stateMachine;

    private final MovieClient movieClient;
    private final SeatClient seatClient;
    private final UserClient userClient;

    @Value("${maipiao.order.lock-minutes:15}")
    private int lockMinutes;

    // ============================================================
    // G1 - order creation
    // ============================================================

    /**
     * Creates an order against seats the user has already locked.
     *
     * <p>Three services are written, so this runs as a Seata AT global
     * transaction. The order of operations matters:
     *
     * <ol>
     *   <li>The seats were locked in Redis <b>before</b> this method was
     *       entered. Redis is not part of the global transaction, so it cannot
     *       be a branch - it is compensated explicitly in the catch block
     *       below. Doing it here would put a non-transactional resource inside
     *       a transactional boundary and make rollback a lie.</li>
     *   <li>Inventory is reserved, then the coupon, then the order row.</li>
     * </ol>
     *
     * <p>Every branch asserts its affected row count and throws when it does
     * not match. That is the mechanism: Seata only rolls back what fails
     * loudly, so a branch that silently returns "no rows changed" would commit
     * a half-built order.
     *
     * <p>Note what is <b>not</b> inside the transaction: notifications,
     * statistics, and anything touching the payment channel. Each would extend
     * the transaction's lifetime and its row locks for no correctness benefit.
     */
    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class, timeoutMills = 30000)
    public OrderDtos.CreateOrderResponse create(OrderDtos.CreateOrderRequest request, Long userId) {

        // The lock token issued by seat-service doubles as the order number,
        // so the Redis hold and the order row cannot drift apart.
        String orderNo = request.lockToken();

        Order existing = orderMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            // Same token presented twice - a double submit, not a new order.
            log.info("duplicate order submission: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_DUPLICATE);
        }

        Map<String, Object> schedule = fetchSchedule(request.scheduleId());
        int seatCount = request.seatIndexes().size();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plusMinutes(lockMinutes);

        BigDecimal unitPrice = toDecimal(schedule.get("price"));
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(seatCount));
        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO : request.discountAmount();
        BigDecimal payAmount = totalAmount.subtract(discount).max(BigDecimal.ZERO);

        try {
            // ---- branch 1: reserve screening inventory ----
            // expireTime crosses the wire as ISO-8601; see MovieClient.occupy.
            requireOk(movieClient.occupy(request.scheduleId(), orderNo, userId, seatCount,
                            expireTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            request.seatIndexes()),
                    "锁定场次库存失败");

            // ---- branch 2: hold the coupon, when one was chosen ----
            if (request.couponId() != null) {
                requireOk(userClient.lockCoupon(request.couponId(), userId, orderNo, totalAmount),
                        "优惠券不可用");
            }

            // ---- branch 3: write the order itself ----
            Order order = buildOrder(request, userId, schedule, orderNo, now, expireTime,
                    seatCount, totalAmount, discount, payAmount);
            orderMapper.insert(order);
            orderItemMapper.insertBatch(buildItems(order, request));

            log.info("order created: orderNo={}, user={}, seats={}, amount={}",
                    orderNo, userId, seatCount, payAmount);

            return new OrderDtos.CreateOrderResponse(orderNo, payAmount, expireTime);

        } catch (Exception e) {
            // The Redis hold is outside the transaction, so Seata cannot undo
            // it. Releasing here is what stops a failed order from leaving
            // seats unavailable for the full hold period.
            //
            // Safe to run more than once: the release script only clears seats
            // whose owner marker still points at this order.
            compensateSeatLock(request.scheduleId(), orderNo, e);
            throw e;
        }
    }

    /**
     * Best-effort release of the Redis hold after a failed order.
     *
     * <p>Failure here is logged, not rethrown: the original exception is the
     * one the caller needs to see, and the hold expires on its own anyway.
     * The reconciliation job picks up anything this misses.
     */
    private void compensateSeatLock(Long scheduleId, String orderNo, Exception cause) {
        log.warn("order creation failed, releasing seat hold: orderNo={}, reason={}",
                orderNo, cause.getMessage());
        try {
            // Goes through seat-service's owner-checked release rather than a
            // force-release, so this cannot clear seats that a concurrent
            // retry has since claimed.
            seatClient.release(scheduleId, orderNo);
        } catch (Exception e) {
            // Logged, not rethrown - and recoverable regardless, because the
            // hold carries a TTL and the timeout sweep will find it.
            log.error("could not release seat hold after failed order: orderNo={}", orderNo, e);
        }
    }

    // ============================================================
    // lifecycle
    // ============================================================

    /**
     * Cancels an unpaid order and frees its seats.
     *
     * <p>The seat release is asynchronous in spirit: the order reaching
     * CANCELLED is the authoritative fact, and freeing the seats is a
     * consequence that can be retried. Doing it inline would make the
     * cancellation fail whenever seat-service is briefly unavailable, for a
     * reason the user cannot act on.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(String orderNo, Long userId, boolean byUser) {
        Order order = stateMachine.require(orderNo);

        if (!order.getUserId().equals(userId) && byUser) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        boolean moved = stateMachine.cancel(orderNo, byUser ? "USER" : "SYSTEM",
                byUser ? "cancelled by user" : "payment timeout");

        if (moved) {
            releaseResources(order);
        }
        return moved;
    }

    /** Marks an order complete once its session has finished. */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String orderNo) {
        return stateMachine.complete(orderNo, "SYSTEM");
    }

    /** Gives back the seats and the coupon of a cancelled order. */
    private void releaseResources(Order order) {
        try {
            // releaseToPool = false: the seats were only ever held, never sold,
            // so the sold counter must not be decremented.
            movieClient.release(order.getScheduleId(), order.getOrderNo(), order.getSeatCount(), false);
        } catch (Exception e) {
            log.error("could not release seats for cancelled order: {}", order.getOrderNo(), e);
        }

        if (order.getCouponId() != null) {
            try {
                userClient.releaseCoupon(order.getCouponId(), order.getOrderNo());
            } catch (Exception e) {
                log.error("could not release coupon for cancelled order: {}", order.getOrderNo(), e);
            }
        }
    }

    // ============================================================
    // queries
    // ============================================================

    public List<Order> listByUser(Long userId, Integer status) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(status != null, Order::getStatus, status)
                .orderByDesc(Order::getCreateTime));
    }

    public OrderDtos.OrderDetail detail(String orderNo, Long userId) {
        Order order = stateMachine.require(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        List<OrderItem> items = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo));

        return new OrderDtos.OrderDetail(order, items, OrderStatus.name(order.getStatus()));
    }

    // ============================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchSchedule(Long scheduleId) {
        var response = movieClient.scheduleSnapshot(scheduleId);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        return (Map<String, Object>) response.getData();
    }

    private Order buildOrder(OrderDtos.CreateOrderRequest request, Long userId,
                             Map<String, Object> schedule, String orderNo,
                             LocalDateTime now, LocalDateTime expireTime, int seatCount,
                             BigDecimal totalAmount, BigDecimal discount, BigDecimal payAmount) {

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setScheduleId(request.scheduleId());
        order.setProjectId(toLong(schedule.get("projectId")));
        order.setProjectTitle(str(schedule.get("projectTitle")));
        order.setVenueId(toLong(schedule.get("venueId")));
        order.setVenueName(str(schedule.get("venueName")));
        order.setPlaceName(str(schedule.get("placeName")));
        order.setShowTime(toDateTime(schedule.get("startTime")));
        order.setShowDate(toDate(schedule.get("showDate")));
        order.setSeatCount(seatCount);
        order.setSeatLabels(String.join(",", request.seatLabels() == null ? List.of() : request.seatLabels()));
        order.setTotalAmount(totalAmount);
        order.setDiscountAmount(discount);
        order.setPayAmount(payAmount);
        order.setCouponId(request.couponId());
        order.setStatus(OrderStatus.PENDING_PAY);
        order.setLockExpireTime(expireTime);
        return order;
    }

    private List<OrderItem> buildItems(Order order, OrderDtos.CreateOrderRequest request) {
        List<OrderItem> items = new ArrayList<>(request.seatIndexes().size());
        BigDecimal price = order.getTotalAmount()
                .divide(BigDecimal.valueOf(Math.max(1, order.getSeatCount())), 2, java.math.RoundingMode.HALF_UP);

        for (int i = 0; i < request.seatIndexes().size(); i++) {
            OrderItem item = new OrderItem();
            item.setOrderNo(order.getOrderNo());
            item.setScheduleId(order.getScheduleId());
            item.setSeatIndex(request.seatIndexes().get(i));
            item.setSeatLabel(request.seatLabels() != null && i < request.seatLabels().size()
                    ? request.seatLabels().get(i) : "");
            // seatId is derived from the label's row/col when available; the
            // ledger's unique key is (session_id, seat_id), so it has to be set.
            item.setSeatId(seatIdFromLabel(item.getSeatLabel(), request.seatIndexes().get(i)));
            item.setPrice(price);
            item.setTicketNo("");
            item.setCheckStatus(0);
            items.add(item);
        }
        return items;
    }

    /** "5排7座" -> "5_7"; falls back to the index when the label is missing. */
    private String seatIdFromLabel(String label, int seatIndex) {
        if (label == null || label.isBlank()) {
            return "idx_" + seatIndex;
        }
        String digits = label.replaceAll("[^0-9]+", " ").trim();
        String[] parts = digits.split("\\s+");
        if (parts.length >= 2) {
            return parts[0] + "_" + parts[1];
        }
        return "idx_" + seatIndex;
    }

    private void requireOk(com.maipiao.common.core.result.R<?> response, String message) {
        if (response == null || !response.isSuccess()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    message + (response == null ? "" : ": " + response.getMessage()));
        }
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDateTime toDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        return value == null ? null : LocalDateTime.parse(value.toString().replace(" ", "T"));
    }

    private LocalDate toDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        return value == null ? null : LocalDate.parse(value.toString().substring(0, 10));
    }
}
