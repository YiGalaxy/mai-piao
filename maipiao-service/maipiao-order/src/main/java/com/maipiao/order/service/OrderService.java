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

/** 订单创建及生命周期管理；{@link #create} 是 G1 全局事务入口。 */
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

    // G1：订单创建

    /** 创建订单；座位已在 Redis 锁定，库存、优惠券和订单写入 G1 全局事务。 */
    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class, timeoutMills = 30000)
    public OrderDtos.CreateOrderResponse create(OrderDtos.CreateOrderRequest request, Long userId) {

        // 锁座 token 同时作为订单号，保证两侧使用同一幂等键。
        String orderNo = request.lockToken();

        Order existing = orderMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            // 同一个 token 被提交了两次 —— 这是重复提交，不是一笔新订单。
            log.info("duplicate order submission: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_DUPLICATE);
        }

        // token 需经 Redis 持有校验；该检查在全局事务外执行，账本 CAS 负责最终并发仲裁。
        HeldSeats held = requireHeld(request.scheduleId(), orderNo, request.seatIndexes());

        Map<String, Object> schedule = fetchSchedule(request.scheduleId());
        int seatCount = request.seatIndexes().size();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plusMinutes(lockMinutes);

        // 订单金额按实际座位票档计算，不信任客户端价格。
        BigDecimal totalAmount = held.amount();
        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO : request.discountAmount();
        BigDecimal payAmount = totalAmount.subtract(discount).max(BigDecimal.ZERO);

        try {
            // 分支 1：预占场次库存。
            requireOk(movieClient.occupy(request.scheduleId(), orderNo, userId, seatCount,
                            expireTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            request.seatIndexes()),
                    "锁定场次库存失败");

            // 分支 2：锁定优惠券。
            if (request.couponId() != null) {
                requireOk(userClient.lockCoupon(request.couponId(), userId, orderNo, totalAmount),
                        "优惠券不可用");
            }

            // 分支 3：写入订单及明细。
            Order order = buildOrder(request, userId, schedule, orderNo, now, expireTime,
                    seatCount, totalAmount, discount, payAmount);
            orderMapper.insert(order);
            orderItemMapper.insertBatch(buildItems(order, request, held));

            log.info("order created: orderNo={}, user={}, seats={}, amount={}",
                    orderNo, userId, seatCount, payAmount);

            return new OrderDtos.CreateOrderResponse(orderNo, payAmount, expireTime);

        } catch (Exception e) {
            // Redis 不受 Seata 回滚，失败时补偿释放；释放脚本按订单归属保证幂等。
            compensateSeatLock(request.scheduleId(), orderNo, e);
            throw e;
        }
    }

    /** 下单失败后补偿释放 Redis 座位占用。 */
    private void compensateSeatLock(Long scheduleId, String orderNo, Exception cause) {
        log.warn("order creation failed, releasing seat hold: orderNo={}, reason={}",
                orderNo, cause.getMessage());
        try {
            // 按订单归属释放，避免误删并发重试的新占用。
            seatClient.release(scheduleId, orderNo);
        } catch (Exception e) {
            // 释放失败仅记录日志，TTL 和超时清扫负责兜底。
            log.error("could not release seat hold after failed order: orderNo={}", orderNo, e);
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /** 取消未支付订单，并释放库存、座位和优惠券。 */
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

    /** 场次结束后把订单标记为已完成。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String orderNo) {
        return stateMachine.complete(orderNo, "SYSTEM");
    }

    /** G2：标记订单已支付，并将账本座位从锁定转为已售。 */
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(String orderNo, LocalDateTime payTime) {
        Order order = stateMachine.require(orderNo);

        // 状态迁移失败表示订单已过期或已处理，不能继续出票。
        if (!stateMachine.markPaid(orderNo, payTime, "PAY_CALLBACK")) {
            log.error("payment arrived for an order that is not payable: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态不允许支付");
        }

        // 库存分支校验锁定数量，防止超时释放后继续出票。
        requireOk(movieClient.confirmSold(order.getScheduleId(), orderNo, order.getSeatCount()),
                "座位出票失败");
    }

    /** G2 提交后，将 Redis 座位占用标记为已售。 */
    public void confirmSeatHold(String orderNo) {
        try {
            Order order = stateMachine.require(orderNo);
            seatClient.confirm(order.getScheduleId(), orderNo);
            log.debug("seat hold confirmed as sold: orderNo={}", orderNo);
        } catch (Exception e) {
            log.error("could not confirm seat hold for paid order: {}", orderNo, e);
        }
    }

    /** 释放已取消订单的账本座位、Redis 占用和优惠券。 */
    private void releaseResources(Order order) {
        try {
            // 未支付订单只释放锁定库存，不减少已售计数。
            movieClient.release(order.getScheduleId(), order.getOrderNo(), order.getSeatCount(), false);
        } catch (Exception e) {
            log.error("could not release seats for cancelled order: {}", order.getOrderNo(), e);
        }

        try {
            // Redis 不受事务回滚；释放接口按订单归属幂等执行。
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
    // 查询
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
            // 能拿到标签的行列号时 seatId 由标签推导；账本的唯一键是 (session_id, seat_id)，
            // 所以这个值必须写上。
            item.setSeatId(seatIdFromLabel(item.getSeatLabel(), seatIndex));

            // 每一行都带着这张座位花了多少钱、来自哪个票档。
            // 用订单总额除以座位数 —— 这是它以前的做法 —— 只在每个座位同价时才成立，
            // 而退款需要的是这一行自己的数字，不是一个平均值。
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
     * 一笔订单正在购买的座位，以及它们的价格。
     *
     * <p>这两件事从同一次调用里回来，这正是要点：价格是从 seat 服务解析出来、
     * 并确认由本订单持有的座位上推导的。不存在任何一条让调用方自己送价格进来的路径。
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

    /**
     * 除非 seat 服务仍然显示本订单持有它列出的每一个座位，否则拒绝这笔订单，
     * 并把那些座位的价格返回回来。
     *
     * <p>seat-service 出错时按失败处理（fail-closed）：一个不可用的 seat 服务
     * 确认不了占用，放行订单就会退回到只信账本 —— 而这次调用存在的意义，
     * 恰恰是不再依赖那个。
     *
     * <p>价格读不出来时同样拒绝，理由相同：因为少了个字段就按 0 元收钱，
     * 比不卖这张票更糟。
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
