package com.maipiao.common.core.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单服务确认创建完成后提供的支付快照，金额与有效期均来自订单。 */
public record PaymentOrderSnapshot(
        String orderNo,
        Long userId,
        BigDecimal payAmount,
        LocalDateTime lockExpireTime,
        Integer status) {}
