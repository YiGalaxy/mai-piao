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

        // The token is an identifier, not proof of anything: it was issued when
        // the seats were locked and carries no expiry of its own, so it stays
        // usable after the hold behind it has lapsed and the seats have been
        // taken by somebody else. Confirming with the seat service first is
        // what makes it evidence.
        //
        // Outside the transaction on purpose - it reads Redis, which Seata
        // cannot roll back, so putting it inside would make the rollback a lie.
        // It narrows the race rather than closing it: a release can still land
        // between this and G1. What stops that from overselling is the ledger's
        // own status = 0 compare-and-set; what this prevents is the realistic
        // version - a page left open past the hold, then submitted - where the
        // holder who won the seat fairly is the one who gets refused.
        HeldSeats held = requireHeld(request.scheduleId(), orderNo, request.seatIndexes());

        Map<String, Object> schedule = fetchSchedule(request.scheduleId());
        int seatCount = request.seatIndexes().size();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plusMinutes(lockMinutes);

        // Priced from the bands the seats actually sit in, not from the
        // session's listing figure. That figure is the "from ¥580" headline on
        // the detail page; charging it for an 1880 VIP seat is an undercharge,
        // and a concert selling four bands at once made it the only price there
        // was.
        BigDecimal totalAmount = held.amount();
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
            orderItemMapper.insertBatch(buildItems(order, request, held));

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

    /**
     * G2: the order is paid, so the seats it holds become sold.
     *
     * <p>Two writes, and the second one is easy to leave out because the first
     * looks like the whole job. Marking the order paid does not touch the seat
     * ledger, and the ledger is what every counter, every refund and every
     * reconciliation reads. Left as merely "locked", a paid seat means:
     *
     * <ul>
     *   <li>{@code locked_seat} never comes back down, so a screening slowly
     *       reports itself sold out while seats are still free - the
     *       {@code locked + sold + n <= total} guard has no way to tell a lock
     *       that became a sale from one that never will;</li>
     *   <li>{@code sold_order_no} stays null, so a refund, which releases on
     *       {@code status = 2 AND sold_order_no = ?}, matches nothing and
     *       refuses to return the seat;</li>
     *   <li>the sale is invisible to the ledger, which is the one place the
     *       design says the truth lives.</li>
     * </ul>
     *
     * <p>Runs inside the caller's global transaction, so the ledger move and
     * the order status commit or roll back together.
     *
     * @throws BizException when the order was not in a payable state, or the
     *                      ledger refused the move - the hold having been
     *                      released underneath us means the seats are gone
     */
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(String orderNo, LocalDateTime payTime) {
        Order order = stateMachine.require(orderNo);

        // false means the order had already moved on - cancelled by the
        // timeout job while the payment was in flight. That must fail loudly:
        // the money has been taken and there is nothing to give in return.
        if (!stateMachine.markPaid(orderNo, payTime, "PAY_CALLBACK")) {
            log.error("payment arrived for an order that is not payable: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态不允许支付");
        }

        // Asserted by the branch itself: it throws when the ledger has fewer
        // locked seats than the order claims, which is what a hold released by
        // the timeout job looks like from here.
        requireOk(movieClient.confirmSold(order.getScheduleId(), orderNo, order.getSeatCount()),
                "座位出票失败");
    }

    /**
     * Marks the Redis hold as sold rather than merely held.
     *
     * <p>Called after G2 commits, never inside it. Redis is not a transactional
     * resource, so a marker written inside the transaction would survive a
     * rollback and leave the seat reading as sold with no paid order behind it
     * - and the release path, which refuses to free a seat marked {@code SOLD:},
     * would then never let it go.
     *
     * <p>Best-effort. A missed marker leaves the seat unavailable either way,
     * since the bitmap bit is already set; what is lost is the distinction that
     * stops the occupancy from later being released as if it were still only a
     * hold. Nothing in the current flow releases a paid order, so this is a
     * second line of defence rather than the first.
     */
    public void confirmSeatHold(String orderNo) {
        try {
            Order order = stateMachine.require(orderNo);
            seatClient.confirm(order.getScheduleId(), orderNo);
            log.debug("seat hold confirmed as sold: orderNo={}", orderNo);
        } catch (Exception e) {
            log.error("could not confirm seat hold for paid order: {}", orderNo, e);
        }
    }

    /**
     * Gives back the seats and the coupon of a cancelled order.
     *
     * <p>A seat is held in two places: the ledger row in movie-service and the
     * bitmap bit in seat-service. Cancelling has to undo both. Freeing only the
     * ledger leaves the seat reading as available in the database while the
     * seat map still refuses to let anyone select it - a dead seat, and silent,
     * because nothing else revisits an order that has reached a terminal state.
     *
     * <p>The ledger goes first. If the bitmap call then fails, the seat is dead
     * either way, but freeing the bitmap first would additionally hand a
     * selectable seat to the next buyer whose G1 would fail on the ledger
     * compare-and-set - an error for somebody who did nothing wrong, in exchange
     * for no better outcome.
     */
    private void releaseResources(Order order) {
        try {
            // releaseToPool = false: the seats were only ever held, never sold,
            // so the sold counter must not be decremented.
            movieClient.release(order.getScheduleId(), order.getOrderNo(), order.getSeatCount(), false);
        } catch (Exception e) {
            log.error("could not release seats for cancelled order: {}", order.getOrderNo(), e);
        }

        try {
            // Redis is not enlisted in the global transaction, so nothing rolls
            // this back for us and nothing retries it either - it has to happen
            // here or not at all.
            //
            // Idempotent and owner-checked on the seat side: only bits whose
            // owner marker still names this order are cleared, so running it
            // after the timeout job already freed them releases nothing extra.
            Integer freed = seatClient.release(order.getScheduleId(), order.getOrderNo()).getData();
            log.debug("seat hold released for cancelled order: orderNo={}, seats={}",
                    order.getOrderNo(), freed);
        } catch (Exception e) {
            log.error("could not release seat hold for cancelled order: {} - seats remain unselectable",
                    order.getOrderNo(), e);
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
        order.setCategory(str(schedule.get("category")));
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

    private List<OrderItem> buildItems(Order order, OrderDtos.CreateOrderRequest request,
                                       HeldSeats held) {
        List<OrderItem> items = new ArrayList<>(request.seatIndexes().size());

        for (int i = 0; i < request.seatIndexes().size(); i++) {
            int seatIndex = request.seatIndexes().get(i);

            OrderItem item = new OrderItem();
            item.setOrderNo(order.getOrderNo());
            item.setScheduleId(order.getScheduleId());
            item.setSeatIndex(seatIndex);
            item.setSeatLabel(request.seatLabels() != null && i < request.seatLabels().size()
                    ? request.seatLabels().get(i) : "");
            // seatId is derived from the label's row/col when available; the
            // ledger's unique key is (session_id, seat_id), so it has to be set.
            item.setSeatId(seatIdFromLabel(item.getSeatLabel(), seatIndex));

            // Each line carries what that seat cost and which band it came
            // from. Dividing the order total by the seat count - what this did
            // before - is only right when every seat has the same price, and a
            // refund needs the line's own figure rather than an average.
            HeldSeats.Line line = held.lineFor(seatIndex);
            item.setPrice(line == null ? BigDecimal.ZERO : line.price());
            item.setTierId(line == null ? null : line.tierId());

            item.setTicketNo("");
            item.setCheckStatus(0);
            items.add(item);
        }
        return items;
    }

    /**
     * The seats an order is buying, and what they cost.
     *
     * <p>Both facts come back from the same call, which is the point: the price
     * is derived from seats the seat service resolved and confirmed are held by
     * this order. There is no path by which a caller supplies one.
     */
    public record HeldSeats(BigDecimal amount, List<Line> lines) {

        public record Line(int seatIndex, Long tierId, BigDecimal price) {
        }

        public Line lineFor(int seatIndex) {
            for (Line line : lines) {
                if (line.seatIndex() == seatIndex) {
                    return line;
                }
            }
            return null;
        }
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

    /**
     * Refuses the order unless the seat service still shows this order holding
     * every seat it named, and returns what those seats cost.
     *
     * <p>Fail-closed on a seat-service error: an unavailable seat service
     * cannot confirm the hold, and letting the order through would fall back to
     * the ledger alone - which is what this call exists to stop relying on.
     *
     * <p>An unreadable price is refused for the same reason. Charging zero
     * because a field was missing is worse than not selling the ticket.
     */
    @SuppressWarnings("unchecked")
    private HeldSeats requireHeld(Long scheduleId, String orderNo, List<Integer> seatIndexes) {
        Map<String, Object> body;
        try {
            body = seatClient.verify(scheduleId, orderNo, seatIndexes).getData();
        } catch (Exception e) {
            log.error("could not verify seat hold, refusing order: orderNo={}", orderNo, e);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }

        if (body == null || !Boolean.TRUE.equals(body.get("held"))) {
            log.info("order rejected, seat hold no longer valid: orderNo={}, seats={}",
                    orderNo, seatIndexes);
            throw new BizException(ErrorCode.SEAT_LOCK_EXPIRED);
        }

        Object amount = body.get("amount");
        if (amount == null) {
            log.error("seat service returned no price for a valid hold: orderNo={}", orderNo);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }

        List<HeldSeats.Line> lines = new ArrayList<>(seatIndexes.size());
        Object rawSeats = body.get("seats");
        if (rawSeats instanceof List<?> seatList) {
            for (Object entry : seatList) {
                if (entry instanceof Map<?, ?> row) {
                    lines.add(new HeldSeats.Line(
                            toInt(row.get("seatIndex")),
                            toLong(row.get("tierId")),
                            toDecimal(row.get("price"))));
                }
            }
        }

        return new HeldSeats(toDecimal(amount), lines);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.parseInt(value.toString());
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
