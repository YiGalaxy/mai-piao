package com.maipiao.order.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.MovieClient;
import com.maipiao.order.feign.UserClient;
import com.maipiao.order.mapper.OrderMapper;
import io.seata.spring.annotation.GlobalLock;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库资源与取消状态一起提交；Redis 只在此代理返回后释放。 */
@Service
@RequiredArgsConstructor
public class OrderCancellationService {
    private final OrderMapper orders;
    private final OrderStateMachine states;
    private final MovieClient movie;
    private final UserClient users;

    @GlobalTransactional(name = "cancel-order", rollbackFor = Exception.class, timeoutMills = 30000)
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(String token, Long userId, boolean byUser) {
        // FOR UPDATE 通过 Seata AT 检查全局锁，避免读到 G1/G2 的一阶段暂存状态。
        Order order = orders.selectForUpdate(token);
        if (order == null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        if (byUser && !order.getUserId().equals(userId))
            throw new BizException(ErrorCode.FORBIDDEN);
        boolean moved =
                states.cancel(
                        token,
                        byUser ? "USER" : "SYSTEM",
                        byUser ? "cancelled by user" : "payment timeout");
        if (moved || order.getStatus() == OrderStatus.CANCELLED) {
            requireOk(movie.release(order.getScheduleId(), token, order.getSeatCount(), false));
            if (order.getCouponId() != null)
                requireOk(users.releaseCoupon(order.getCouponId(), token));
        }
        return moved;
    }

    /** 通过独立代理调用，在释放 Redis 前确认没有其他 AT 事务持有取消结果的全局锁。 */
    @GlobalLock
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmedCancelled(String token) {
        Order order = orders.selectForUpdate(token);
        return order != null && order.getStatus() == OrderStatus.CANCELLED;
    }

    private void requireOk(R<?> result) {
        if (result == null || !result.isSuccess())
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "取消资源回收失败，请重试");
    }
}
