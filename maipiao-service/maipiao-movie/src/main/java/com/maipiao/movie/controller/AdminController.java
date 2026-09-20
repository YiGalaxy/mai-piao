package com.maipiao.movie.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.result.R;
import com.maipiao.movie.dto.AdminDtos;
import com.maipiao.movie.entity.Cinema;
import com.maipiao.movie.entity.Film;
import com.maipiao.movie.entity.Hall;
import com.maipiao.movie.entity.Session;
import com.maipiao.movie.mapper.CinemaMapper;
import com.maipiao.movie.mapper.FilmMapper;
import com.maipiao.movie.mapper.HallMapper;
import com.maipiao.movie.mapper.SessionMapper;
import com.maipiao.movie.service.AdminPerformanceService;
import com.maipiao.movie.service.SessionSeatFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理接口：说明将会卖什么。
 *
 * <p>只有管理员够得着。网关在 token 不带 admin 角色时拒绝
 * {@code /api/*&#47;admin/**}，位置和机制都与 {@code /inner} 的拦截相同 —— 都在查
 * 公开白名单之前，所以一条覆盖整个服务的白名单条目不会不小心把它放开。
 *
 * <p>只做一个 controller 而不是好几个，因为它干的是一件事：建一个东西、让它上架、
 * 说明什么时候。为三个名词写三个 controller，就是三个可能把鉴权注解写错的地方。
 */
@Slf4j
@RestController
// 是 /movie/admin 而不是 /admin：网关会剥掉一层前缀，所以发往
// /api/movie/admin/... 的请求到这里是 /movie/admin/... 公开的那个 controller
// 挂在 /movie 下也是同一个原因。
@RequestMapping("/movie/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminPerformanceService adminService;
    private final FilmMapper filmMapper;
    private final HallMapper hallMapper;
    private final CinemaMapper cinemaMapper;
    private final SessionMapper sessionMapper;
    private final SessionSeatFactory seatFactory;

    // ------------------------------------------------------------
    // 已有什么
    // ------------------------------------------------------------

    /**
     * 场馆和它们下面的场地。
     *
     * <p>做成嵌套而不是平铺，因为选择就是这么做的：先挑一个体育场，再挑它的哪一块。
     * 一份一百个场地、不挂任何场馆的平铺列表，是没人用得起来的列表。
     *
     * <p>返回的东西既够选择器用，也够编辑表单用，这样打开一个场馆改电话号时，不必
     * 为了第一次没带的字段再跑一趟。
     *
     * <p>{@code includeClosed} 存在是因为这个列表同时也是管理界面：一个被停用的
     * 场馆必须对停用它的人仍然可见，否则这个操作看起来就像删除。
     */
    @GetMapping("/venues")
    public R<List<Map<String, Object>>> venues(
            @RequestParam(defaultValue = "true") boolean includeClosed) {

        List<Cinema> venues = cinemaMapper.selectList(Wrappers.<Cinema>lambdaQuery()
                .eq(!includeClosed, Cinema::getStatus, Cinema.STATUS_OPEN)
                .orderByAsc(Cinema::getId));

        List<Map<String, Object>> result = new ArrayList<>(venues.size());
        for (Cinema venue : venues) {
            List<Hall> places = hallMapper.selectList(Wrappers.<Hall>lambdaQuery()
                    .eq(Hall::getVenueId, venue.getId())
                    .eq(!includeClosed, Hall::getStatus, Hall.STATUS_ACTIVE)
                    .orderByAsc(Hall::getId));

            List<Map<String, Object>> placeViews = new ArrayList<>(places.size());
            for (Hall place : places) {
                Map<String, Object> view = new HashMap<>();
                view.put("id", place.getId());
                view.put("venueId", place.getVenueId());
                view.put("name", place.getName());
                view.put("placeType", place.getPlaceType());
                view.put("seatingMode", place.getSeatingMode());
                view.put("rowCount", place.getRowCount());
                view.put("colCount", place.getColCount());
                view.put("seatCount", place.getSeatCount());
                view.put("seatTemplate", place.getSeatTemplate());
                view.put("status", place.getStatus());
                // 模板实际产出多少，摆在场馆声明的数字旁边。两者允许不一致 —— 一个是
                // 标签，另一个才算数 —— 但正在编辑的人应该两个都看得到。
                view.put("actualSeatCount", seatFactory.layoutOf(place).size());
                view.put("sessionCount", sessionCountOfPlace(place.getId()));
                placeViews.add(view);
            }

            Map<String, Object> view = new HashMap<>();
            view.put("id", venue.getId());
            view.put("name", venue.getName());
            view.put("venueType", venue.getVenueType());
            view.put("city", venue.getDistrict());
            view.put("district", venue.getDistrict());
            view.put("address", venue.getAddress());
            view.put("phone", venue.getPhone());
            view.put("longitude", venue.getLongitude());
            view.put("latitude", venue.getLatitude());
            view.put("status", venue.getStatus());
            view.put("places", placeViews);
            result.add(view);
        }
        return R.ok(result);
    }

    /** 项目列表，好让界面把已有的东西列出来。 */
    @GetMapping("/projects")
    public R<List<AdminDtos.ProjectSummary>> projects(
            @RequestParam(required = false) String category) {

        List<Film> projects = filmMapper.selectList(Wrappers.<Film>lambdaQuery()
                .eq(category != null && !category.isBlank(), Film::getCategory, category)
                .orderByDesc(Film::getId));

        List<AdminDtos.ProjectSummary> summaries = new ArrayList<>(projects.size());
        for (Film project : projects) {
            Long count = sessionCount(project.getId());
            summaries.add(new AdminDtos.ProjectSummary(project.getId(), project.getTitle(),
                    project.getCategory(), project.getArtist(), project.getShowDate(),
                    project.getStatus(), count == null ? 0 : count.intValue()));
        }
        return R.ok(summaries);
    }

    /** 一个项目的日期。管理员加完之后就来看这里。 */
    @GetMapping("/projects/{projectId}/sessions")
    public R<List<Session>> sessions(@PathVariable Long projectId) {
        return R.ok(adminService.sessionsOf(projectId));
    }

    // ------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------

    @PostMapping("/projects")
    public R<Long> createProject(@Valid @RequestBody AdminDtos.CreateProjectRequest request) {
        return R.ok(adminService.createProject(request));
    }

    /**
     * 让一个项目在某一天、某个场地上架开卖。
     *
     * <p>一次调用，一个晚上。巡演的一站要演三晚就是三次调用，这是诚实的做法：它们是
     * 三件各自独立、要上架的事，有各自的座位和各自的库存。搞成循环排期，又回到电影院
     * 那套模型了。
     */
    @PostMapping("/sessions")
    public R<AdminDtos.SessionCreated> createSession(
            @Valid @RequestBody AdminDtos.CreateSessionRequest request) {
        return R.ok(adminService.createSession(request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public R<Void> deleteSession(@PathVariable Long sessionId) {
        adminService.deleteSession(sessionId);
        return R.ok();
    }

    // ------------------------------------------------------------
    // 场馆和场地
    // ------------------------------------------------------------

    @PostMapping("/venues")
    public R<Long> createVenue(@Valid @RequestBody AdminDtos.VenueRequest request) {
        return R.ok(adminService.createVenue(request));
    }

    /**
     * 一次建好场馆和它的场地。
     *
     * <p>界面走的是这一个。分开两次调用会留下「场馆在、场地没建成」的中间状态，
     * 那种场馆排不了演出也卖不了票。
     */
    @PostMapping("/venues/full")
    public R<Map<String, Object>> createVenueWithPlaces(
            @Valid @RequestBody AdminDtos.CreateVenueWithPlacesRequest request) {
        return R.ok(adminService.createVenueWithPlaces(request));
    }

    @PutMapping("/venues/{venueId}")
    public R<Void> updateVenue(@PathVariable Long venueId,
                               @Valid @RequestBody AdminDtos.VenueRequest request) {
        adminService.updateVenue(venueId, request);
        return R.ok();
    }

    @PostMapping("/places")
    public R<Long> createPlace(@Valid @RequestBody AdminDtos.PlaceRequest request) {
        return R.ok(adminService.createPlace(request));
    }

    /**
     * 编辑一个场地，座位模板也在内。
     *
     * <p>已有场次不受影响：它们的座位行在创建时就写好了，而一个场子在两场活动之间
     * 确实可能重新布置。
     */
    @PutMapping("/places/{placeId}")
    public R<Void> updatePlace(@PathVariable Long placeId,
                               @Valid @RequestBody AdminDtos.PlaceRequest request) {
        adminService.updatePlace(placeId, request);
        return R.ok();
    }

    // ------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------

    @PutMapping("/projects/{projectId}")
    public R<Void> updateProject(@PathVariable Long projectId,
                                 @RequestBody AdminDtos.UpdateProjectRequest request) {
        adminService.updateProject(projectId, request);
        return R.ok();
    }

    /**
     * 改的是一个场次怎么卖，不是它在卖什么。
     *
     * <p>日期、时间、票价档在这里都不可改 —— 原因见那个请求 record。{@code status}
     * 传 0 是下架，取消不了任何东西。
     */
    @PutMapping("/sessions/{sessionId}")
    public R<Void> updateSession(@PathVariable Long sessionId,
                                 @RequestBody AdminDtos.UpdateSessionRequest request) {
        adminService.updateSession(sessionId, request);
        return R.ok();
    }

    // ------------------------------------------------------------

    /**
     * 这个场地被多少个场次用着。
     *
     * <p>摆在它旁边显示，因为改座位模板会改变之后每一个场次的构造基础，而正要做
     * 这件事的人应该知道已经存在多少个 —— 不是要拦他，而是让这个改动是有意为之。
     */
    private int sessionCountOfPlace(Long placeId) {
        Long count = sessionMapper.selectCount(
                Wrappers.<Session>lambdaQuery().eq(Session::getPlaceId, placeId));
        return count == null ? 0 : count.intValue();
    }

    private Long sessionCount(Long projectId) {
        return sessionMapper.selectCount(Wrappers.<Session>lambdaQuery()
                .eq(Session::getProjectId, projectId));
    }

}
