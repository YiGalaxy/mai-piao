package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.entity.Film;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FilmMapper extends BaseMapper<Film> {
}
