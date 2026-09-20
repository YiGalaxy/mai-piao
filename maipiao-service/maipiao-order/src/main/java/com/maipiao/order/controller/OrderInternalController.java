package com.maipiao.order.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.dto.AdminOrderDtos;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.service.OrderAdminService;
import com.maipiao.order.service.OrderRefundService;
import com.maipiao.order.service.OrderService;
import com.maipiao.order.service.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * 支付流程（G2、G3）用的服务间接口。
 *
 * <p>只能从集群内部访问：网关会先于公共白名单拒绝 {@code /api/*&#47;inner/**}，
 * 因为白名单表达不了「除了这些之外都公开」，而这些接口动的是钱和库存。
 *
 * <p>每一个都是 Seata 全局事务的一个分支，所以规则和其他地方一样：
 * 断言影响行数，对不上就抛异常。一个什么都没改却报告成功的分支，
 * 会让一次支付在没有票的情况下提交掉。
 */
@Slf4j
@RestController
@RequestMapping("/inner")
@RequiredArgsConstructor
public class OrderInternalController {

    private final OrderService orderService;
    private final OrderStateMachine stateMachine;
    private final OrderItemMapper orderItemMapper;
    private final OrderAdminService orderAdminService;
    private final OrderRefundService orderRefundService;

    /**
     * 某个用户买了多少。
     *
     * <p>由 user-service 在用户详情页调用，那是唯一需要它的地方。
     * 故意不放进用户列表：一页二十个用户就会变成二十次这样的调用，
     * 只为算一个扫列表时没人会读的数字。
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam Long userId) {
        AdminOrderDtos.UserOrderStats stats = orderAdminService.statsOf(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("orderCount", stats.orderCount());
        body.put("paidAmount", stats.paidAmount());
        return R.ok(body);
    }

    /**
     * G2 分支：订单转已支付，座位账本从已锁定转已售出。
     *
     * <p>这两半放在同一个调用后面，因为它们本来就是同一个事实。拆成两个接口的话，
     * 调用了第一个却没调用第二个的调用方，会留下一笔已支付、座位却还是「已占用」的订单 ——
     * 这个接口只改状态的那段时间里，发生的正是这件事。
     */
    @PostMapping("/{orderNo}/paid")
    public R<Void> markPaid(@PathVariable String orderNo,
                            @RequestParam LocalDateTime payTime) {
        orderService.markPaid(orderNo, payTime);
        return R.ok();
    }

    /**
     * 把 Redis 里的占用标记为已售出。由 pay-service 在 G2 事务提交<b>之后</b>调用。
     *
     * <p>单独一个接口而不是并进 G2，正是因为这件事绝不能待在 G2 里面：
     * Redis 回滚不了，写在事务里的标记会挺过回滚，把座位永久钉成已售。
     */
    @PostMapping("/{orderNo}/confirm-seats")
    public R<Void> confirmSeats(@PathVariable String orderNo) {
        orderService.confirmSeatHold(orderNo);
        return R.ok();
    }

    /**
     * G2 分支：出票。
     *
     * <p>在支付这一刻，为每个座位生成一个票号。支付之前就已存在的票号，
     * 是一张能在支付之前被拿出来用的票。
     */
    @PostMapping("/{orderNo}/issue-tickets")
    public R<Void> issueTickets(@PathVariable String orderNo,
                                @RequestParam String paymentNo) {
        List<OrderItem> items = orderItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderNo, orderNo));

        if (items.isEmpty()) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单没有座位明细");
        }

        int issued = 0;
        for (OrderItem item : items) {
            if (item.getTicketNo() != null && !item.getTicketNo().isBlank()) {
                continue; // 已经出过票了；重试不能再造出第二个票号
            }
            item.setTicketNo(SnowflakeIdGenerator.nextString());
            orderItemMapper.updateById(item);
            issued++;
        }

        log.info("tickets issued: orderNo={}, paymentNo={}, count={}", orderNo, paymentNo, issued);
        return R.ok();
    }

    /**
     * G3 分支：订单转已退款，座位回到池子里。
     *
     * <p>三件事一起做，因为它们描述的是同一个事实：订单变成已退款、账本里那几行从
     * 已售改回可选、售出计数减回去。少做任何一件，都会要么票卖不出去，要么卖出去
     * 却不减计数。
     */
    @PostMapping("/{orderNo}/refund-success")
    public R<Void> markRefunded(@PathVariable String orderNo,
                                @RequestParam BigDecimal refundAmount) {
        orderRefundService.markRefunded(orderNo, refundAmount);
        return R.ok();
    }

    /**
     * 清掉 Redis 里的座位占用。在 G3 提交之后调用。
     *
     * <p>单独一个接口，理由和确认出票那个一样：Redis 回滚不了。写在事务里面的话，
     * 事务一旦回滚，这些位还在，座位就以「已售」的姿态长期占着，而账本说它是空的。
     */
    @PostMapping("/{orderNo}/release-refunded-seats")
    public R<Void> releaseRefundedSeats(@PathVariable String orderNo) {
        orderRefundService.clearSeatHold(stateMachine.require(orderNo));
        return R.ok();
    }

    /** 支付页要用的订单快照。 */
    @GetMapping("/{orderNo}/summary")
    public R<Order> summary(@PathVariable String orderNo) {
        return R.ok(stateMachine.require(orderNo));
    }
}
