package com.maipiao.order.entity;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class RefundRequest {
    private String orderNo;
    private BigDecimal amount;
    private String refundNo;
    private Integer attempts;
}
