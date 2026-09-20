package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_event.t_event_price_tier} - a price band within a session.
 *
 * <p>A film session has exactly one tier covering every row. That is not a
 * special case in the code: the seat map colours by tier and the order prices
 * by tier, and a film simply has one of them. The alternative - a nullable
 * price on the session that takes precedence when present - means every
 * consumer has to know which of two pricing models is in play.
 *
 * <p>Bands are row ranges because that is how venues sell them ("rows 1-5 are
 * VIP"). The seat carries its tier id from generation time, so a seat's price
 * is never recomputed from its row at request time.
 */
@Data
@TableName("t_event_price_tier")
public class PriceTier {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    /** VIP / floor / stands / standard. */
    private String name;

    private BigDecimal price;

    private Integer rowStart;

    /** 0 means "to the last row". */
    private Integer rowEnd;

    /** Colour hint for the seat map, so bands are distinguishable at a glance. */
    private String color;

    private LocalDateTime createTime;

    /** Whether this tier covers the given row. */
    public boolean covers(int row) {
        int end = rowEnd == null || rowEnd == 0 ? Integer.MAX_VALUE : rowEnd;
        return row >= rowStart && row <= end;
    }
}
