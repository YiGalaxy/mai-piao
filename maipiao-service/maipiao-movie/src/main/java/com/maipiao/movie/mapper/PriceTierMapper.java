package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.entity.PriceTier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PriceTierMapper extends BaseMapper<PriceTier> {

    @Select("""
            SELECT * FROM t_event_price_tier
             WHERE session_id = #{sessionId}
             ORDER BY row_start
            """)
    List<PriceTier> selectBySession(@Param("sessionId") Long sessionId);
}
