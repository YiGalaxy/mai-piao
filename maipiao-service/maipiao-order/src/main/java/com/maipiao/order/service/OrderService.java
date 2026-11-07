package com.maipiao.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.dto.PaymentOrderSnapshot;
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
import com.maipiao.order.mapper.OrderReservationMapper;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订单创建及生命周期状态流转。 */
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

    private final OrderReservationMapper reservations;
    private final OrderCancellationService cancellation;

    // ============================================================
    // G1 - 订单创建
    // ============================================================

    /** 仅预约协调器在持有执行权且开启 G1 后调用。Redis 保留不参与事务补偿。 */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.CreateOrderResponse createReserved(
            OrderDtos.CreateOrderRequest request, Long userId, LocalDateTime expireTime) {
        if (!RootContext.inGlobalTransaction()) throw new IllegalStateException("缺少全局事务");
        if (!expireTime.isAfter(LocalDateTime.now()))
            throw new BizException(ErrorCode.SEAT_LOCK_EXPIRED);

        // 锁令牌复用为订单号，重试可直接命中同一业务键。
        String orderNo = request.lockToken();

        Order existing = orderMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            // 同一令牌重复提交，拒绝创建新订单。
            log.info("duplicate order submission: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_DUPLICATE);
        }

        // 先取得数据库执行权，再核验 Redis，避免与过期回收并发。
        HeldSeats held = requireHeld(request.scheduleId(), orderNo, request.seatIndexes());

        Map<String, Object> schedule = fetchSchedule(request.scheduleId());
        int seatCount = request.seatIndexes().size();

        LocalDateTime now = LocalDateTime.now();

        // 金额以座位票档为准，不能信任详情页展示价或前端金额。
        BigDecimal totalAmount = held.amount();
        BigDecimal discount = BigDecimal.ZERO;

        // 分支 1：预占场次库存。
        requireOk(
                movieClient.occupy(
                        request.scheduleId(),
                        orderNo,
                        userId,
                        seatCount,
                        expireTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        request.seatIndexes()),
                "锁定场次库存失败");

        // 优惠金额由锁券结果返回，不能信任前端传值。
        if (request.couponId() != null) {
            var coupon = userClient.lockCoupon(request.couponId(), userId, orderNo, totalAmount);
            requireOk(coupon, "优惠券不可用");
            if (coupon.getData() == null) throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE);
            discount = coupon.getData();
        }
        BigDecimal payAmount = totalAmount.subtract(discount).max(BigDecimal.ZERO);

        // 分支 3：写订单和明细。
        Order order =
                buildOrder(
                        request,
                        userId,
                        schedule,
                        orderNo,
                        now,
                        expireTime,
                        seatCount,
                        totalAmount,
                        discount,
                        payAmount);
        if (orderMapper.insert(order) != 1)
            throw new BizException(ErrorCode.SYSTEM_ERROR, "订单写入失败");
        if (orderItemMapper.insertBatch(buildItems(order, request, held)) != seatCount)
            throw new BizException(ErrorCode.SYSTEM_ERROR, "订单明细写入不完整");

        log.info(
                "order created: orderNo={}, user={}, seats={}, amount={}",
                orderNo,
                userId,
                seatCount,
                payAmount);

        return new OrderDtos.CreateOrderResponse(orderNo, payAmount, expireTime);
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /** 取消未支付订单；先确认取消状态，再尝试释放座位，失败可由回收任务重试。 */
    public boolean cancel(String orderNo, Long userId, boolean byUser) {
        var reservation = reservations.find(orderNo);
        if (byUser && reservation != null && !reservation.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (reservation != null && "PROCESSING".equals(reservation.getState())) return false;
        boolean moved = cancellation.cancel(orderNo, userId, byUser);
        Order order = stateMachine.require(orderNo);
        if (order.getStatus() == OrderStatus.CANCELLED
                && cancellation.confirmedCancelled(orderNo)) {
            try {
                requireOk(seatClient.release(order.getScheduleId(), orderNo), "释放座位失败");
            } catch (Exception e) {
                // 保留延迟记录，后续清扫会重试释放。
                log.error("cancelled order awaits Redis release: {}", orderNo, e);
            }
        }
        return moved;
    }

    public boolean releaseCancelledResources(Order order) {
        // 重试取消流程，确保全局事务已提交后再释放资源。
        cancellation.cancel(order.getOrderNo(), order.getUserId(), false);
        return cancellation.confirmedCancelled(order.getOrderNo());
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional(rollbackFor = Exception.class)
    public PaymentOrderSnapshot paymentSummary(String orderNo) {
        requireCreationConfirmed(orderNo);
        Order order = orderMapper.selectForUpdate(orderNo);
        if (order == null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        return new PaymentOrderSnapshot(
                order.getOrderNo(),
                order.getUserId(),
                order.getPayAmount(),
                order.getLockExpireTime(),
                order.getStatus());
    }

    private void requireCreationConfirmed(String orderNo) {
        var reservation = reservations.find(orderNo);
        // 新订单必须确认 G1 已提交，不能把 AT 一阶段状态当作支付依据。
        if (reservation != null && !"SUCCEEDED".equals(reservation.getState())) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单创建结果尚未确认，请稍后查询");
        }
    }

    /** 场次结束后把订单标记为已完成。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String orderNo) {
        return stateMachine.complete(orderNo, "SYSTEM");
    }

    /** G2：在全局事务中将订单置为已支付，并把座位账本从锁定迁移为已售。 */
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(String orderNo, LocalDateTime payTime) {
        requireCreationConfirmed(orderNo);
        Order order = stateMachine.require(orderNo);

        // 状态已被取消或已处理时拒绝回调，避免收款后无票可交付。
        if (!stateMachine.markPaid(orderNo, payTime, "PAY_CALLBACK")) {
            log.error("payment arrived for an order that is not payable: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态不允许支付");
        }

        // 账本会校验锁定数量，数量不足直接失败。
        requireOk(
                movieClient.confirmSold(order.getScheduleId(), orderNo, order.getSeatCount()),
                "座位出票失败");
    }

    /** G2 提交后同步 Redis 售出标记；失败由支付侧补偿重试。 */
    @io.seata.spring.annotation.GlobalLock
    @Transactional(rollbackFor = Exception.class)
    public void confirmSeatHold(String orderNo) {
        Order order = orderMapper.selectForUpdate(orderNo);
        if (order == null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.COMPLETED) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "仅已支付订单可确认座位");
        }
        requireOk(seatClient.confirm(order.getScheduleId(), orderNo), "确认已售座位失败");
    }

    // ============================================================
    // 查询
    // ============================================================

    public List<Order> listByUser(Long userId, Integer status) {
        return orderMapper.selectList(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status)
                        .orderByDesc(Order::getCreateTime));
    }

    public OrderDtos.OrderDetail detail(String orderNo, Long userId) {
        Order order = stateMachine.require(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        List<OrderItem> items =
                orderItemMapper.selectList(
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

    private Order buildOrder(
            OrderDtos.CreateOrderRequest request,
            Long userId,
            Map<String, Object> schedule,
            String orderNo,
            LocalDateTime now,
            LocalDateTime expireTime,
            int seatCount,
            BigDecimal totalAmount,
            BigDecimal discount,
            BigDecimal payAmount) {

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
        order.setSeatLabels(
                String.join(",", request.seatLabels() == null ? List.of() : request.seatLabels()));
        order.setTotalAmount(totalAmount);
        order.setDiscountAmount(discount);
        order.setPayAmount(payAmount);
        order.setCouponId(request.couponId());
        order.setStatus(OrderStatus.PENDING_PAY);
        order.setLockExpireTime(expireTime);
        return order;
    }

    private List<OrderItem> buildItems(
            Order order, OrderDtos.CreateOrderRequest request, HeldSeats held) {
        List<OrderItem> items = new ArrayList<>(request.seatIndexes().size());

        for (int i = 0; i < request.seatIndexes().size(); i++) {
            int seatIndex = request.seatIndexes().get(i);

            OrderItem item = new OrderItem();
            item.setOrderNo(order.getOrderNo());
            item.setScheduleId(order.getScheduleId());
            item.setSeatIndex(seatIndex);
            item.setSeatLabel(
                    request.seatLabels() != null && i < request.seatLabels().size()
                            ? request.seatLabels().get(i)
                            : "");
            // 账本唯一键需要稳定的 seatId；优先从座位标签推导。
            item.setSeatId(seatIdFromLabel(item.getSeatLabel(), seatIndex));

            // 明细保存自身票价和票档，支持多票档订单退款。
            HeldSeats.Line line = held.lineFor(seatIndex);
            item.setPrice(line == null ? BigDecimal.ZERO : line.price());
            item.setTierId(line == null ? null : line.tierId());

            item.setTicketNo("");
            item.setCheckStatus(0);
            items.add(item);
        }
        return items;
    }

    /** seat 服务确认的持有座位及其票档价格。 */
    public record HeldSeats(BigDecimal amount, List<Line> lines) {

        public record Line(int seatIndex, Long tierId, BigDecimal price) {}

        public Line lineFor(int seatIndex) {
            for (Line line : lines) {
                if (line.seatIndex() == seatIndex) {
                    return line;
                }
            }
            return null;
        }
    }

    /** "5排7座" -> "5_7"；标签缺失时退回用座位下标。 */
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

    /** 仅在 seat 服务确认全部座位仍归属该订单且价格完整时继续下单。 */
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
            log.info(
                    "order rejected, seat hold no longer valid: orderNo={}, seats={}",
                    orderNo,
                    seatIndexes);
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
                    lines.add(
                            new HeldSeats.Line(
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
            throw new BizException(
                    ErrorCode.SYSTEM_ERROR,
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
