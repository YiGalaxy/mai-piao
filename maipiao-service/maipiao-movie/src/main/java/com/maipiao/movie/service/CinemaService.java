package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.movie.entity.Cinema;
import com.maipiao.movie.mapper.CinemaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CinemaService {

    private final CinemaMapper cinemaMapper;

    /**
     * 营业中的影院，可以按行政区筛。
     *
     * <p>停业的影院无条件排除 —— 客户端没有任何理由看到它，返回它们只会让每个调用方
     * 都做一遍同样的过滤。
     */
    public List<Cinema> list(String district) {
        return cinemaMapper.selectList(Wrappers.<Cinema>lambdaQuery()
                .eq(Cinema::getStatus, Cinema.STATUS_OPEN)
                .eq(district != null && !district.isBlank(), Cinema::getDistrict, district)
                .orderByAsc(Cinema::getId));
    }
}
