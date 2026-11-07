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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款，以及什么情况下才允许退的规则。
 *
 * <p>这里每一道关卡的存在，都是因为电影票的答案和商店退货不一样， 答错就要么让场地方吃亏、要么让顾客吃亏：
 *
 * <ul>
 *   <li><b>开演前两小时。</b>开演前十分钟才放出来的电影票座位，是没人会买的座位。 临近开演就拒绝退票，是所有真实售票系统的行为，也正因为如此，
 *       这个窗口是算到演出时刻的，而不是算到购买时刻。
 *   <li><b>验票之后不退。</b>已验的票就是已经用掉的票。
 *   <li><b>只在已支付状态下退。</b>待支付的订单走取消，不走退款； 已经退过的不会再退第二次。
 * </ul>
 *
 * <p>退款窗口是票自身的属性，不是顾客耐心的属性，所以在这里判定， 而不是丢给客户端自己去把关。
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
    private final com.maipiao.order.mapper.RefundRequestMapper refundRequests;
    private final OrderSettlementGuard settlementGuard;

    /** 距开演多近就不再允许退款。 */
    @Value("${maipiao.order.refund-deadline-hours:2}")
    private int refundDeadlineHours;

    // ------------------------------------------------------------

    /**
     * 这笔订单此刻能不能退，以及不能退的原因。
     *
     * <p>和退款请求分开，是为了让订单页在任何人按下按钮之前就能把按钮置灰。 校验和动作共用这一个方法，所以按钮不可能承诺一件服务器会拒绝的事。
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
        boolean anyChecked =
                items.stream()
                        .anyMatch(
                                item ->
                                        item.getCheckStatus() != null
                                                && item.getCheckStatus() == 1);
        if (anyChecked) {
            return RefundCheck.no("已验票，不可退票");
        }

        return RefundCheck.yes(order.getPayAmount());
    }

    /**
     * 把订单推进到 REFUNDING。
     *
     * <p>只做状态迁移。真正把钱要回来是 pay-service 的事，而它会跑自己的全局事务 —— 那件事发生时这个事务绝不能还开着，否则一个慢的支付渠道会让订单行锁 被按住整整一次
     * HTTP 调用的时长。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal startRefund(String orderNo, String reason) {
        Order order = orderMapper.selectForUpdate(orderNo);
        if (order == null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        var existing = refundRequests.find(orderNo);
        if (existing != null) return existing.getAmount();
        RefundCheck check = checkRefundable(order);
        if (!check.allowed()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, check.reason());
        }

        if (!stateMachine.startRefund(orderNo, "USER")) {
            // 输给了超时任务，或者输给了第二次点击。
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态已变化，请刷新后重试");
        }

        refundRequests.insert(orderNo, check.amount());

        log.info(
                "refund started: orderNo={}, amount={}, reason={}",
                orderNo,
                check.amount(),
                reason);
        return check.amount();
    }

    /**
     * G3 的第二个分支：钱回来了，所以订单也要这么说。
     *
     * <p>G3 分支：在全局事务中将订单置为已退款，并把已售库存退回。 Redis 座位占用在事务提交后由调用方清理，失败由持久化任务补偿。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRefunded(String orderNo, BigDecimal amount) {
        Order order = stateMachine.require(orderNo);

        if (amount == null || order.getPayAmount().compareTo(amount) != 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "退款金额与订单不符");
        }
        if (!stateMachine.markRefunded(orderNo, amount, null)) {
            if (order.getStatus() != OrderStatus.REFUNDED
                    || order.getRefundAmount() == null
                    || order.getRefundAmount().compareTo(amount) != 0) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单退款状态冲突");
            }
            // 已经退过了。这不是错误：重试清扫可能先到了，
            // 而结果正是调用方想要的。
            log.info("refund already recorded: orderNo={}", orderNo);
            return;
        }

        try {
            // releaseToPool = true：这些座位是卖出去的，所以售出计数要减下来，
            // 座位重新上架销售。
            com.maipiao.common.core.result.RemoteResult.requireSuccess(
                    movieClient.release(order.getScheduleId(), orderNo, order.getSeatCount(), true),
                    "释放退款库存");
        } catch (Exception e) {
            // 库存账本更新失败时回滚 G3，保留退款记录供后续重试。
            log.error(
                    "refund recorded but the seat ledger was not released: orderNo={}", orderNo, e);
            throw e;
        }

        log.info("order refunded: orderNo={}, amount={}", orderNo, amount);
    }

    /** 核验退款已提交后清理 Redis 占用；失败由支付服务的持久化任务重试。 */
    public void clearSeatHold(Order order) {
        order = settlementGuard.confirmedRefunded(order.getOrderNo());
        // 失败必须传回支付服务，由持久化补偿任务继续释放。
        com.maipiao.common.core.result.RemoteResult.requireSuccess(
                seatClient.release(order.getScheduleId(), order.getOrderNo(), true), "释放退款座位");
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
