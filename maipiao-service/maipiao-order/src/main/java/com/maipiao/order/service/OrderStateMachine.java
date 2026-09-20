package com.maipiao.order.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.entity.OrderStatusLog;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单状态变更的唯一出口。
 *
 * <p>代码库里其他地方都不许写 {@code t_order_order.status}。这不是风格偏好：
 * 整个守护条件都活在 WHERE 子句里，所以任何绕过这个类的写入，同时也绕过了那道守护，
 * 状态机就悄无声息地不再是状态机了。
 *
 * <p>每个方法返回的都是<em>本次</em>调用是否真的完成了这次迁移。
 * {@code false} 不是错误 —— 它意味着别人先到了（重投的消息、被连点的按钮、
 * 和支付回调抢跑的超时任务）。调用方把它当作幂等成功处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateMachine {

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;

    /**
     * 取消一笔未支付的订单。
     *
     * @return 本次调用完成了取消时为 true；已经被取消、或已经推进到支付状态时为 false
     */
    public boolean cancel(String orderNo, String operator, String remark) {
        // 只有 PENDING_PAY 可以取消。PAYING 是被故意排除的：支付可能正在路上，
        // 超时任务在取消之前会先去问支付渠道，而不是跟它抢。
        boolean moved = doTransition(orderNo,
                List.of(OrderStatus.PENDING_PAY), OrderStatus.CANCELLED, operator, remark);
        return moved;
    }

    /** @return 本次调用就是把它标成已支付的那一次时为 true */
    public boolean markPaid(String orderNo, LocalDateTime payTime, String operator) {
        int rows = orderMapper.markPaid(orderNo, List.of(OrderStatus.PENDING_PAY, OrderStatus.PAYING), payTime);
        boolean moved = rows == 1;
        logTransition(orderNo, OrderStatus.PAID, operator,
                moved ? "payment confirmed" : "already paid or not payable");
        return moved;
    }

    /**
     * 场次结束后，把已支付的订单推进到已完成。
     *
     * <p>已完成的订单不能再走常规退款路径，所以这件事由场次结束时间来驱动，
     * 而不是单靠一个定时器。
     */
    public boolean complete(String orderNo, String operator) {
        return doTransition(orderNo, List.of(OrderStatus.PAID), OrderStatus.COMPLETED, operator, "screening finished");
    }

    /** 把订单标记为已发起退款。 */
    public boolean startRefund(String orderNo, String operator) {
        return doTransition(orderNo, List.of(OrderStatus.PAID, OrderStatus.COMPLETED),
                OrderStatus.REFUNDING, operator, "refund requested");
    }

    /** @return 本次调用就是记下这笔退款的那一次时为 true */
    public boolean markRefunded(String orderNo, BigDecimal amount, Long operator) {
        int rows = orderMapper.markRefunded(orderNo, amount, LocalDateTime.now());
        boolean moved = rows == 1;
        logTransition(orderNo, OrderStatus.REFUNDED, String.valueOf(operator),
                moved ? "refund confirmed" : "not in refunding state");
        return moved;
    }

    /**
     * 把一笔退款中的订单放回已支付。
     *
     * <p>用在退款彻底失败时：钱根本没出去，所以订单仍然是已支付、票仍然有效。
     * 把它丢在 REFUNDING 里就是把它晾死 —— 用不了，也退不了。
     */
    public boolean rollbackRefund(String orderNo, String reason) {
        return doTransition(orderNo, List.of(OrderStatus.REFUNDING), OrderStatus.PAID,
                "SYSTEM", "refund failed: " + reason);
    }

    // ------------------------------------------------------------

    private boolean doTransition(String orderNo, List<Integer> fromStatuses, int toStatus,
                                 String operator, String remark) {
        int rows = orderMapper.transition(orderNo, fromStatuses, toStatus);
        boolean moved = rows == 1;
        logTransition(orderNo, toStatus, operator, moved ? remark : "no-op, status already moved");
        return moved;
    }

    /**
     * 往审计轨迹里追加一条。
     *
     * <p>故意不放进事务里，并且容忍失败：日志是有价值，但丢一行日志，
     * 绝不能把一次已经发生的状态变更回滚掉。
     */
    private void logTransition(String orderNo, int toStatus, String operator, String remark) {
        try {
            OrderStatusLog entry = new OrderStatusLog();
            entry.setId(SnowflakeIdGenerator.next());
            entry.setOrderNo(orderNo);
            entry.setFromStatus(-1); // 权威的 from-status 在 CAS 本身里
            entry.setToStatus(toStatus);
            entry.setOperator(operator == null ? "SYSTEM" : operator);
            entry.setRemark(remark);
            statusLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("could not write status log: order={}, status={}, reason={}",
                    orderNo, toStatus, e.getMessage());
        }
    }

    /** 读一笔订单，并断言它存在。 */
    public Order require(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
