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
 * 订单创建，以及围绕它的整个生命周期。
 *
 * <p>真正有料的是 {@link #create}，也就是 G1 全局事务。
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
    // G1 - 订单创建
    // ============================================================

    /**
     * 针对用户已经锁定的座位创建订单。
     *
     * <p>这里要写三个服务，所以整体跑在一个 Seata AT 全局事务里。操作的先后顺序是有讲究的：
     *
     * <ol>
     *   <li>座位在进入这个方法<b>之前</b>就已经锁在 Redis 里了。Redis 不属于全局事务，
     *       因此它没法成为一个分支 —— 它由下面 catch 块里的补偿动作显式撤销。把加锁放到
     *       这里来做，等于把一个非事务性资源塞进了事务边界，那样回滚就是一句假话。</li>
     *   <li>先占库存，再锁优惠券，最后才写订单行。</li>
     * </ol>
     *
     * <p>每个分支都会断言自己的影响行数，对不上就抛异常。这就是机制本身：Seata 只回滚那些
     * 明着报错的失败，一个默默返回「没有行被改动」的分支，只会把一个半成品订单提交掉。
     *
     * <p>注意哪些东西是<b>不</b>在事务里的：通知、统计，以及任何要碰支付渠道的动作。
     * 放进来只会拉长事务的存活时间和行锁持有时间，换不来任何正确性上的好处。
     */
    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class, timeoutMills = 30000)
    public OrderDtos.CreateOrderResponse create(OrderDtos.CreateOrderRequest request, Long userId) {

        // seat-service 发放的 lock token 同时充当订单号，
        // 这样 Redis 里的占用和订单行在构造上就不可能跑到两处去。
        String orderNo = request.lockToken();

        Order existing = orderMapper.selectByOrderNo(orderNo);
        if (existing != null) {
            // 同一个 token 被提交了两次 —— 这是重复提交，不是一笔新订单。
            log.info("duplicate order submission: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_DUPLICATE);
        }

        // token 只是个标识，本身不构成任何证明：它是在锁座那一刻发出的，自身不带过期时间，
        // 所以在它背后的占用早已失效、座位已经被别人拿走之后，它依然可用。
        // 先向 seat 服务确认一次，才让它成为证据。
        //
        // 故意放在事务外面 —— 它读 Redis，而 Seata 回滚不了 Redis，
        // 放进去就等于让回滚变成谎话。
        // 它只是把竞态窗口收窄，并没有关上：一次 release 仍可能落在这一步和 G1 之间。
        // 真正拦住超卖的是账本自己的 status = 0 比较并交换（CAS）；而这里拦下的，
        // 是现实中真会出现的那个版本 —— 页面开着超过了占用时限才提交 ——
        // 在那个版本里，被拒的会是那个公平赢得座位的人。
        HeldSeats held = requireHeld(request.scheduleId(), orderNo, request.seatIndexes());

        Map<String, Object> schedule = fetchSchedule(request.scheduleId());
        int seatCount = request.seatIndexes().size();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plusMinutes(lockMinutes);

        // 价格按座位实际所在的票档算，而不是按场次详情页上那个数字。
        // 那个数字是详情页「¥580 起」的门面；拿它去收一张 1880 的 VIP 座就是少收钱，
        // 而一场同时卖四个票档的演唱会，会让它成为当时唯一存在的价格。
        BigDecimal totalAmount = held.amount();
        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO : request.discountAmount();
        BigDecimal payAmount = totalAmount.subtract(discount).max(BigDecimal.ZERO);

        try {
            // ---- 分支 1：预占场次库存 ----
            // expireTime 以 ISO-8601 的形式过线；原因见 MovieClient.occupy。
            requireOk(movieClient.occupy(request.scheduleId(), orderNo, userId, seatCount,
                            expireTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            request.seatIndexes()),
                    "锁定场次库存失败");

            // ---- 分支 2：选了优惠券就把它占住 ----
            if (request.couponId() != null) {
                requireOk(userClient.lockCoupon(request.couponId(), userId, orderNo, totalAmount),
                        "优惠券不可用");
            }

            // ---- 分支 3：写订单本身 ----
            Order order = buildOrder(request, userId, schedule, orderNo, now, expireTime,
                    seatCount, totalAmount, discount, payAmount);
            orderMapper.insert(order);
            orderItemMapper.insertBatch(buildItems(order, request, held));

            log.info("order created: orderNo={}, user={}, seats={}, amount={}",
                    orderNo, userId, seatCount, payAmount);

            return new OrderDtos.CreateOrderResponse(orderNo, payAmount, expireTime);

        } catch (Exception e) {
            // Redis 里的占用在事务之外，Seata 撤销不了它。在这里释放，
            // 才能让一个下单失败的订单不至于把座位卡满整个占用时长。
            //
            // 重复执行是安全的：释放脚本只清那些归属标记仍指向本订单的座位。
            compensateSeatLock(request.scheduleId(), orderNo, e);
            throw e;
        }
    }

    /**
     * 下单失败后，尽力释放 Redis 里的占用。
     *
     * <p>这里失败只记日志，不再往上抛：调用方需要看到的是最初那个异常，
     * 而且占用本身带 TTL，到点自己就没了。这里漏掉的，由对账任务兜底。
     */
    private void compensateSeatLock(Long scheduleId, String orderNo, Exception cause) {
        log.warn("order creation failed, releasing seat hold: orderNo={}, reason={}",
                orderNo, cause.getMessage());
        try {
            // 走 seat-service 带归属校验的释放，而不是强制释放，
            // 所以它不会清掉并发重试已经抢过去的座位。
            seatClient.release(scheduleId, orderNo);
        } catch (Exception e) {
            // 只记日志，不重抛 —— 而且无论怎样都能恢复：占用带 TTL，
            // 超时清扫会找到它。
            log.error("could not release seat hold after failed order: orderNo={}", orderNo, e);
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * 取消一笔未支付的订单并释放它的座位。
     *
     * <p>座位释放本质上是个异步动作：订单变成 CANCELLED 才是权威事实，
     * 释放座位是随之而来的、可以重试的后果。把它做成一件事，
     * 只要 seat-service 抖一下取消就会失败，而失败的理由用户根本无从处理。
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

    /** 场次结束后把订单标记为已完成。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String orderNo) {
        return stateMachine.complete(orderNo, "SYSTEM");
    }

    /**
     * G2：订单已支付，它占着的座位随之变成已售。
     *
     * <p>两处写入，而第二处很容易被落下，因为第一处看起来就把活干完了。
     * 把订单标成已支付并不会动座位账本，而账本才是所有计数、所有退款、所有对账读取的东西。
     * 一笔已支付的座位如果只停在「已锁定」，意味着：
     *
     * <ul>
     *   <li>{@code locked_seat} 再也降不下来，于是某个场次会一边还有空位、
     *       一边慢慢把自己报成售罄 —— {@code locked + sold + n <= total} 这道防线
     *       分不清「锁座变成了售出」和「锁座永远不会成交」；</li>
     *   <li>{@code sold_order_no} 一直是 null，于是退款时按
     *       {@code status = 2 AND sold_order_no = ?} 去释放，什么都匹配不上，
     *       座位退不回来；</li>
     *   <li>这笔销售对账本不可见，而账本正是设计上唯一说真话的地方。</li>
     * </ul>
     *
     * <p>运行在调用方的全局事务里，所以账本的迁移和订单状态同生共死。
     *
     * @throws BizException 订单不在可支付状态时，或账本拒绝这次迁移时 ——
     *                      占用在我们脚下被释放掉，意味着座位已经没了
     */
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(String orderNo, LocalDateTime payTime) {
        Order order = stateMachine.require(orderNo);

        // false 表示订单已经走过去了 —— 支付还在路上时被超时任务取消了。
        // 这种情况必须大声失败：钱已经收了，却拿不出任何东西来交付。
        if (!stateMachine.markPaid(orderNo, payTime, "PAY_CALLBACK")) {
            log.error("payment arrived for an order that is not payable: orderNo={}", orderNo);
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态不允许支付");
        }

        // 由分支自己断言：账本里的锁定座位数少于订单声明的数量时它会抛异常，
        // 而从这里的视角看，那正是「占用被超时任务释放掉了」的样子。
        requireOk(movieClient.confirmSold(order.getScheduleId(), orderNo, order.getSeatCount()),
                "座位出票失败");
    }

    /**
     * 把 Redis 里的占用标记从「已锁定」改成「已售出」。
     *
     * <p>在 G2 提交之后调用，绝不放在 G2 里面。Redis 不是事务性资源，
     * 写在事务内的标记会挺过回滚，留下一个座位读起来是已售、背后却没有已支付订单的状态 ——
     * 而释放路径拒绝释放标记为 {@code SOLD:} 的座位，那个座位从此就再也放不出来了。
     *
     * <p>尽力而为。漏打一个标记，座位反正还是不可用的，因为位图里那一位已经置上了；
     * 真正丢掉的是那道区分 —— 它用来防止这次占用在之后被当成「仅仅是个占用」而释放掉。
     * 当前流程里没有任何地方会释放一笔已支付的订单，所以这里是第二道防线，不是第一道。
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
     * 把已取消订单的座位和优惠券还回去。
     *
     * <p>一个座位存在两个地方：movie-service 里的账本行，和 seat-service 里的位图位。
     * 取消必须把两边都撤掉。只释放账本，会让座位在数据库里读起来可选，
     * 而选座图仍然拒绝任何人选中它 —— 一个死座位，而且悄无声息，
     * 因为没有任何东西会再去碰一个已经进入终态的订单。
     *
     * <p>先动账本。如果随后位图调用失败，座位反正都是死的；但先释放位图，
     * 额外会把一个可选座位递到下一个买家手里，而他的 G1 会卡在账本的比较并交换上 ——
     * 代价是让一个什么都没做错的人拿到一个错误，换来的结果却一点也不更好。
     */
    private void releaseResources(Order order) {
        try {
            // releaseToPool = false：这些座位从头到尾只是被占用过、从未售出，
            // 所以售出计数不能减。
            movieClient.release(order.getScheduleId(), order.getOrderNo(), order.getSeatCount(), false);
        } catch (Exception e) {
            log.error("could not release seats for cancelled order: {}", order.getOrderNo(), e);
        }

        try {
            // Redis 没有加入全局事务，所以没有任何东西会替我们回滚它，
            // 也没有任何东西会重试它 —— 要么在这里做，要么永远不做。
            //
            // 在 seat 一侧是幂等且带归属校验的：只有归属标记仍写着本订单的位才会被清掉，
            // 所以即使超时任务已经释放过，再跑一遍也不会多释放任何东西。
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
