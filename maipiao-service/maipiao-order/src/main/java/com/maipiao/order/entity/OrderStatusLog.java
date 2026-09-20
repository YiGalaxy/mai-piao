package com.maipiao.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_order.t_order_status_log}。只追加的审计轨迹。
 *
 * <p>当迁移是由 CAS 完成的时候，{@code fromStatus} 是 -1，
 * 因为真正的来源状态记录在完成这次迁移的那条 UPDATE 里。
 * 在这里捕获它意味着多一次读，而那次读的结果可能和 CAS 实际匹配到的并不一致。
 */
@Data
@TableName("t_order_status_log")
public class OrderStatusLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Integer fromStatus;

    private Integer toStatus;

    /** USER / SYSTEM / MQ / ADMIN —— 取值本身保持原样，不翻译 */
    private String operator;

    private String remark;

    private LocalDateTime createTime;
}
