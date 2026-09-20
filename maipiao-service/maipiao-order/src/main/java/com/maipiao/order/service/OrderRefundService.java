package com.maipiao.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.MovieClient;
import com.maipiao.order.feign.SeatClient;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款，以及什么情况下才允许退的规则。
 *
 * <p>这里每一道关卡的存在，都是因为电影票的答案和商店退货不一样，
 * 答错就要么让场地方吃亏、要么让顾客吃亏：
 *
 * <ul>
 *   <li><b>开演前两小时。</b>开演前十分钟才放出来的电影票座位，是没人会买的座位。
 *       临近开演就拒绝退票，是所有真实售票系统的行为，也正因为如此，
 *       这个窗口是算到演出时刻的，而不是算到购买时刻。</li>
 *   <li><b>验票之后不退。</b>已验的票就是已经用掉的票。</li>
 *   <li><b>只在已支付状态下退。</b>待支付的订单走取消，不走退款；
 *       已经退过的不会再退第二次。</li>
 * </ul>
 *
 * <p>退款窗口是票自身的属性，不是顾客耐心的属性，所以在这里判定，
 * 而不是丢给客户端自己去把关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefundService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStateMachine stateMachine;
    private final MovieClient movieClient;
    private final SeatClient seatClient;

    /** 距开演多近就不再允许退款。 */
    @Value("${maipiao.order.refund-deadline-hours:2}")
    private int refundDeadlineHours;

    // ------------------------------------------------------------

    /**
     * 这笔订单此刻能不能退，以及不能退的原因。
     *
     * <p>和退款请求分开，是为了让订单页在任何人按下按钮之前就能把按钮置灰。
     * 校验和动作共用这一个方法，所以按钮不可能承诺一件服务器会拒绝的事。
     */
    public RefundCheck checkRefundable(Order order) {
        if (order == null) {
            return RefundCheck.no("订单不存在");
        }

        Integer status = order.getStatus();
        if (status == null) {
            return RefundCheck.no("订单状态异常");
        }
        if (status == OrderStatus.REFUNDING || status == OrderStatus.REFUNDED) {
            return RefundCheck.no("该订单已在退款流程中");
        }
        if (status == OrderStatus.CANCELLED) {
            return RefundCheck.no("订单已取消");
        }
        if (status != OrderStatus.PAID && status != OrderStatus.COMPLETED) {
            return RefundCheck.no("订单尚未支付");
        }

        if (order.getShowTime() == null) {
            return RefundCheck.no("场次信息缺失");
        }
        LocalDateTime deadline = order.getShowTime().minusHours(refundDeadlineHours);
        if (LocalDateTime.now().isAfter(deadline)) {
            return RefundCheck.no("距开演不足 " + refundDeadlineHours + " 小时，不可退票");
        }

        List<OrderItem> items = itemsOf(order.getOrderNo());
        boolean anyChecked = items.stream()
                .anyMatch(item -> item.getCheckStatus() != null && item.getCheckStatus() == 1);
        if (anyChecked) {
            return RefundCheck.no("已验票，不可退票");
        }

        return RefundCheck.yes(order.getPayAmount());
    }

    /**
     * 把订单推进到 REFUNDING。
     *
     * <p>只做状态迁移。真正把钱要回来是 pay-service 的事，而它会跑自己的全局事务 ——
     * 那件事发生时这个事务绝不能还开着，否则一个慢的支付渠道会让订单行锁
     * 被按住整整一次 HTTP 调用的时长。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal startRefund(String orderNo, String reason) {
        Order order = stateMachine.require(orderNo);
        RefundCheck check = checkRefundable(order);
        if (!check.allowed()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, check.reason());
        }

        if (!stateMachine.startRefund(orderNo, "USER")) {
            // 输给了超时任务，或者输给了第二次点击。
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态已变化，请刷新后重试");
        }

        log.info("refund started: orderNo={}, amount={}, reason={}",
                orderNo, check.amount(), reason);
        return check.amount();
    }

    /**
     * G3 的第二个分支：钱回来了，所以订单也要这么说。
     *
     * <p>这里发生三件事，而三件事都是同一个事实的后果。订单变成 REFUNDED；
     * 座位账本从已售改回可选、回到池子里，因为座位可以再卖一次；
     * Redis 里的占用被清掉，好让选座图把它们放出来。
     *
     * <p>Redis 那次调用是故意放在事务<b>外面</b>的。Redis 回滚不了，
     * 写在事务内的清理会挺过回滚，留下一个订单读起来还是已支付、
     * 而它的座位已经在向别人出售的状态。
     *
     * <p>订单和账本在事务里面，所以任何一处失败，两处都没做成 ——
     * 而退款重试清扫会把这件事再跑一遍。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRefunded(String orderNo, BigDecimal amount) {
        Order order = stateMachine.require(orderNo);

        if (!stateMachine.markRefunded(orderNo, amount, null)) {
            // 已经退过了。这不是错误：重试清扫可能先到了，
            // 而结果正是调用方想要的。
            log.info("refund already recorded: orderNo={}", orderNo);
            return;
        }

        try {
            // releaseToPool = true：这些座位是卖出去的，所以售出计数要减下来，
            // 座位重新上架销售。
            movieClient.release(order.getScheduleId(), orderNo, order.getSeatCount(), true);
        } catch (Exception e) {
            // 要大声，因为事务马上会回滚、退款会被重试 ——
            // 钱已经动了，账本却不同意，而这道差额正是清扫任务存在的原因。
            log.error("refund recorded but the seat ledger was not released: orderNo={}",
                    orderNo, e);
            throw e;
        }

        log.info("order refunded: orderNo={}, amount={}", orderNo, amount);
    }

    /** 清掉已退款订单在 Redis 里的占用。事后执行，尽力而为。 */
    public void clearSeatHold(Order order) {
        try {
            // includeSold：这些座位带着 SOLD: 标记，而退款正是唯一会把它们
            // 放回市场的流程。
            seatClient.release(order.getScheduleId(), order.getOrderNo(), true);
        } catch (Exception e) {
            log.error("could not clear the seat hold for refunded order: {} - "
                    + "the seats stay unsellable until the reconciliation job runs",
                    order.getOrderNo(), e);
        }
    }

    // ------------------------------------------------------------

    private List<OrderItem> itemsOf(String orderNo) {
        return orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo));
    }

    /** 结论，连同金额一起给出，免得调用方还得自己再推一遍。 */
    public record RefundCheck(boolean allowed, String reason, BigDecimal amount) {

        public static RefundCheck yes(BigDecimal amount) {
            return new RefundCheck(true, "", amount);
        }

        public static RefundCheck no(String reason) {
            return new RefundCheck(false, reason, BigDecimal.ZERO);
        }
    }
}
