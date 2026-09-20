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
 * Public browsing endpoints.
 *
 * <p>Read-only and unauthenticated - the gateway whitelists {@code /api/movie/**}.
 * A token is still forwarded when the client has one, so personalisation can
 * be added later without introducing a parallel set of endpoints.
 *
 * <p>Every method returns {@link R} and delegates; no branching, no try/catch.
 * Failures are raised as {@code BizException} from the service layer and
 * translated centrally.
 */
@RestController
@RequestMapping("/movie")
@RequiredArgsConstructor
public class MovieController {

    private final FilmService filmService;
    private final CinemaService cinemaService;
    private final SessionService sessionService;

    /** @param status 0 upcoming, 1 now showing, 2 offline; omit for all */
    @GetMapping("/film/list")
    public R<List<Film>> filmList(@RequestParam(required = false) Integer status) {
        return R.ok(filmService.list(status));
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
     * Screenings for a film and/or cinema on a date.
     *
     * <p>{@code showDate} accepts ISO {@code yyyy-MM-dd} and defaults to today
     * when omitted, so a client that has not built a date picker yet still gets
     * something useful rather than an error.
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
