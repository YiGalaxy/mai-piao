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
     * Open cinemas, optionally filtered by district.
     *
     * <p>Closed cinemas are excluded unconditionally - there is no client-side
     * reason to see one, and returning them would mean every caller filters
     * the same way.
     */
    public List<Cinema> list(String district) {
        return cinemaMapper.selectList(Wrappers.<Cinema>lambdaQuery()
                .eq(Cinema::getStatus, Cinema.STATUS_OPEN)
                .eq(district != null && !district.isBlank(), Cinema::getDistrict, district)
                .orderByAsc(Cinema::getId));
    }
}
