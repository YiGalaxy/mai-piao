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
     * The catalogue, filtered.
     *
     * @param status   0 upcoming, 1 on sale, 2 closed; null for all
     * @param category MOVIE / CONCERT / TALK_SHOW / ...; null for all.
     *                 Filtering here rather than in the client means a category
     *                 page does not download the whole catalogue to discard
     *                 most of it - and it is one indexed column.
     */
    public List<Film> list(Integer status, String category) {
        return filmMapper.selectList(Wrappers.<Film>lambdaQuery()
                .eq(status != null, Film::getStatus, status)
                .eq(category != null && !category.isBlank(), Film::getCategory, category)
                // Newest first. Unrated (score 0) entries are not filtered out -
                // an upcoming one legitimately has no score yet.
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
