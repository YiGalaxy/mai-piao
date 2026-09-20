package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.movie.entity.Film;
import com.maipiao.movie.mapper.FilmMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilmService {

    private final FilmMapper filmMapper;

    /**
     * @param status 0 upcoming, 1 now showing, 2 offline; null for all
     */
    public List<Film> list(Integer status) {
        return filmMapper.selectList(Wrappers.<Film>lambdaQuery()
                .eq(status != null, Film::getStatus, status)
                // Newest releases first. Unrated (score 0) films are not
                // sorted out here - an upcoming film legitimately has no score.
                .orderByDesc(Film::getShowDate)
                .orderByDesc(Film::getId));
    }

    public Film detail(Long projectId) {
        Film film = filmMapper.selectById(projectId);
        if (film == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "影片不存在");
        }
        return film;
    }
}
