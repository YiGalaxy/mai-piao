package com.maipiao.order.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.mapper.OrderMapper;
import io.seata.spring.annotation.GlobalLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderSettlementGuard {
    private final OrderMapper orders;

    @GlobalLock
    @Transactional(rollbackFor = Exception.class)
    public Order confirmedRefunded(String orderNo) {
        Order order = orders.selectForUpdate(orderNo);
        if (order == null || order.getStatus() != OrderStatus.REFUNDED) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "退款事务尚未确认");
        }
        return order;
    }
}
