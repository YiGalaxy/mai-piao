package com.maipiao.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_order.t_order_status_log}. Append-only audit trail.
 *
 * <p>{@code fromStatus} is -1 when the transition was performed by a CAS whose
 * actual from-status is recorded in the UPDATE that did it. Capturing it here
 * would mean an extra read, and that read could disagree with what the CAS
 * actually matched.
 */
@Data
@TableName("t_order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Integer fromStatus;

    private Integer toStatus;

    /** USER / SYSTEM / MQ / ADMIN */
    private String operator;

    private String remark;

    private LocalDateTime createTime;
}
