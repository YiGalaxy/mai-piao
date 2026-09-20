package com.maipiao.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_user.t_user_coupon}。
 *
 * <p>状态取值，以及这里要紧的那几个迁移：
 * <pre>
 *   0 未使用 --lock-->  1 已锁定 --consume--> 2 已使用
 *                       1 已锁定 --rollback--> 0 未使用
 *   0 未使用 ---------- 过期 ----------------> 3 已过期
 * </pre>
 *
 * <p>上面每一个都是一条带条件的 UPDATE，绝不是「先读再写」。锁定那次尤其是
 * {@code UPDATE ... SET status=1 WHERE id=? AND user_id=? AND status=0}
 * 而调用方必须断言有一行被改动 —— 否则两笔并发订单可以双双「成功」锁住同一张券。
 */
@Data
@TableName("t_user_coupon")
public class Coupon {

    public static final int STATUS_UNUSED = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_USED = 2;
    public static final int STATUS_EXPIRED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long couponTemplateId;

    /** 抵扣金额，领取时从模板复制过来，这样之后编辑模板也没法回头改动一张已发出的券。 */
    private BigDecimal amount;

    /** 使用它所需的最低订单金额。 */
    private BigDecimal threshold;

    private Integer status;

    /** 某笔订单锁定或核销这张券时写入。 */
    private String orderNo;

    private LocalDateTime lockTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
