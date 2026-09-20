package com.maipiao.movie.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.entity.Cinema;
import com.maipiao.movie.entity.Film;
import com.maipiao.movie.service.CinemaService;
import com.maipiao.movie.service.FilmService;
import com.maipiao.movie.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 面向公众的浏览接口。
 *
 * <p>只读且不鉴权 —— 网关把 {@code /api/movie/**} 放进了白名单。客户端带 token 时
 * 仍然会转发过去，这样以后要加个性化不必再开一套并行的接口。
 *
 * <p>每个方法都返回 {@link R} 并向下委派；没有分支，没有 try/catch。失败由 service
 * 层以 {@code BizException} 抛出，再统一翻译。
 */
@RestController
@RequestMapping("/movie")
@RequiredArgsConstructor
public class MovieController {

    private final FilmService filmService;
    private final CinemaService cinemaService;
    private final SessionService sessionService;

    /**
     * @param status   0 待映，1 在售，2 已下线；不传表示全部
     * @param category MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL；
     *                 不传表示全部。做成不传而不是传一个列表，是为了让「全部」这种
     *                 情况仍然只是一条走得上索引的查询。
     */
    @GetMapping("/film/list")
    public R<List<Film>> filmList(@RequestParam(required = false) Integer status,
                                  @RequestParam(required = false) String category) {
        return R.ok(filmService.list(status, category));
    }

    @GetMapping("/film/{projectId}")
    public R<Film> filmDetail(@PathVariable Long projectId) {
        return R.ok(filmService.detail(projectId));
    }

    @GetMapping("/cinema/list")
    public R<List<Cinema>> cinemaList(@RequestParam(required = false) String district) {
        return R.ok(cinemaService.list(district));
    }

    /**
     * 某一天、某部影片和/或某个影院的排片。
     *
     * <p>{@code showDate} 接受 ISO {@code yyyy-MM-dd}，不传时默认今天，这样还没做
     * 日期选择器的客户端拿到的是有用的东西，而不是一个错误。
     */
    @GetMapping("/schedule/list")
    public R<List<SessionVO>> scheduleList(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate showDate) {
        return R.ok(sessionService.list(projectId, venueId, showDate));
    }

    @GetMapping("/schedule/{sessionId}")
    public R<SessionVO> scheduleDetail(@PathVariable Long sessionId) {
        return R.ok(sessionService.detail(sessionId));
    }
}
