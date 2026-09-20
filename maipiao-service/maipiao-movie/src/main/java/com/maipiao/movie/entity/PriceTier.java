package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_event.t_event_price_tier} —— 一个场次内的票价档。
 *
 * <p>电影场次恰好只有一个覆盖全部排的档。这不是代码里的特例：座位图按档着色、下单
 * 按档定价，而电影只不过是「只有一个档」而已。另一种做法 —— 在场次上放一个可为空的
 * 价格、有值时就优先 —— 意味着每个下游都得知道当前跑的是两套定价模型里的哪一套。
 *
 * <p>用排区间是因为场馆就是这么卖的（「1-5 排是 VIP」）。座位在生成时就带着自己的
 * 档 id，所以座位的价格从不在请求时按排重算。
 */
@Data
@TableName("t_event_price_tier")
public class PriceTier {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    /** VIP / 内场 / 看台 / 标准。 */
    private String name;

    private BigDecimal price;

    private Integer rowStart;

    /** 0 表示「一直到最后一排」。 */
    private Integer rowEnd;

    /** 给座位图的颜色提示，好让各档一眼能分辨。 */
    private String color;

    private LocalDateTime createTime;

    /** 这一档是否覆盖给定的排。 */
    public boolean covers(int row) {
        int end = rowEnd == null || rowEnd == 0 ? Integer.MAX_VALUE : rowEnd;
        return row >= rowStart && row <= end;
    }
}
