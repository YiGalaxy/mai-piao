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
     * 片库/剧库，带筛选。
     *
     * @param status   0 待映，1 在售，2 已下线；null 表示全部
     * @param category MOVIE / CONCERT / TALK_SHOW / ...；null 表示全部。
     *                 在这里筛而不是让客户端筛，一个分类页就不必把整个库拉下来再丢掉
     *                 绝大部分 —— 而且它只是一列，走得上索引。
     */
    public List<Film> list(Integer status, String category) {
        return filmMapper.selectList(Wrappers.<Film>lambdaQuery()
                .eq(status != null, Film::getStatus, status)
                .eq(category != null && !category.isBlank(), Film::getCategory, category)
                // 最新的在前。没有评分的（score 为 0）不筛掉 —— 一部待映的作品本来
                // 就还没有评分。
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
