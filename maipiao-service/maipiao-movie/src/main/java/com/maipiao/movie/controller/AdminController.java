package com.maipiao.movie.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.result.R;
import com.maipiao.common.web.audit.AdminAuditLog;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** 管理员接口；鉴权由网关统一执行。 */
@Slf4j
@RestController
// 网关转发 /api/movie/admin/** 时会保留 /movie 前缀。
@RequestMapping("/movie/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminPerformanceService adminService;
    private final FilmMapper filmMapper;
    private final HallMapper hallMapper;
    private final CinemaMapper cinemaMapper;
    private final SessionMapper sessionMapper;
    private final SessionSeatFactory seatFactory;

    /** 返回场馆及其场地；管理端默认包含停用项。 */
    @GetMapping("/venues")
    public R<List<Map<String, Object>>> venues(
            @RequestParam(defaultValue = "true") boolean includeClosed) {

        List<Cinema> venues =
                cinemaMapper.selectList(
                        Wrappers.<Cinema>lambdaQuery()
                                .eq(!includeClosed, Cinema::getStatus, Cinema.STATUS_OPEN)
                                .orderByAsc(Cinema::getId));

        List<Map<String, Object>> result = new ArrayList<>(venues.size());
        for (Cinema venue : venues) {
            List<Hall> places =
                    hallMapper.selectList(
                            Wrappers.<Hall>lambdaQuery()
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
                // 同时返回声明容量和模板实际容量，便于校验模板。
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

    /** 查询项目列表。 */
    @GetMapping("/projects")
    public R<List<AdminDtos.ProjectSummary>> projects(
            @RequestParam(required = false) String category) {

        List<Film> projects =
                filmMapper.selectList(
                        Wrappers.<Film>lambdaQuery()
                                .eq(
                                        category != null && !category.isBlank(),
                                        Film::getCategory,
                                        category)
                                .orderByDesc(Film::getId));

        List<AdminDtos.ProjectSummary> summaries = new ArrayList<>(projects.size());
        for (Film project : projects) {
            Long count = sessionCount(project.getId());
            summaries.add(
                    new AdminDtos.ProjectSummary(
                            project.getId(),
                            project.getTitle(),
                            project.getCategory(),
                            project.getArtist(),
                            project.getShowDate(),
                            project.getStatus(),
                            count == null ? 0 : count.intValue()));
        }
        return R.ok(summaries);
    }

    /** 查询项目场次。 */
    @GetMapping("/projects/{projectId}/sessions")
    public R<List<Session>> sessions(@PathVariable Long projectId) {
        return R.ok(adminService.sessionsOf(projectId));
    }

    @PostMapping("/projects")
    @AdminAuditLog(action = "CREATE", resource = "PROJECT")
    public R<Long> createProject(@Valid @RequestBody AdminDtos.CreateProjectRequest request) {
        return R.ok(adminService.createProject(request));
    }

    /** 创建一个场次及其独立座位库存。 */
    @PostMapping("/sessions")
    @AdminAuditLog(action = "CREATE", resource = "SESSION")
    public R<AdminDtos.SessionCreated> createSession(
            @Valid @RequestBody AdminDtos.CreateSessionRequest request) {
        return R.ok(adminService.createSession(request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @AdminAuditLog(action = "DELETE", resource = "SESSION")
    public R<Void> deleteSession(@PathVariable Long sessionId) {
        adminService.deleteSession(sessionId);
        return R.ok();
    }

    @PostMapping("/venues")
    @AdminAuditLog(action = "CREATE", resource = "VENUE")
    public R<Long> createVenue(@Valid @RequestBody AdminDtos.VenueRequest request) {
        return R.ok(adminService.createVenue(request));
    }

    /** 在一个事务中创建场馆及其场地。 */
    @PostMapping("/venues/full")
    @AdminAuditLog(action = "CREATE", resource = "VENUE_WITH_PLACES")
    public R<Map<String, Object>> createVenueWithPlaces(
            @Valid @RequestBody AdminDtos.CreateVenueWithPlacesRequest request) {
        return R.ok(adminService.createVenueWithPlaces(request));
    }

    @PutMapping("/venues/{venueId}")
    @AdminAuditLog(action = "UPDATE", resource = "VENUE")
    public R<Void> updateVenue(
            @PathVariable Long venueId, @Valid @RequestBody AdminDtos.VenueRequest request) {
        adminService.updateVenue(venueId, request);
        return R.ok();
    }

    @PostMapping("/places")
    @AdminAuditLog(action = "CREATE", resource = "PLACE")
    public R<Long> createPlace(@Valid @RequestBody AdminDtos.PlaceRequest request) {
        return R.ok(adminService.createPlace(request));
    }

    /** 编辑场地和座位模板；已创建场次的座位不变。 */
    @PutMapping("/places/{placeId}")
    @AdminAuditLog(action = "UPDATE", resource = "PLACE")
    public R<Void> updatePlace(
            @PathVariable Long placeId, @Valid @RequestBody AdminDtos.PlaceRequest request) {
        adminService.updatePlace(placeId, request);
        return R.ok();
    }

    @PutMapping("/projects/{projectId}")
    @AdminAuditLog(action = "UPDATE", resource = "PROJECT")
    public R<Void> updateProject(
            @PathVariable Long projectId, @RequestBody AdminDtos.UpdateProjectRequest request) {
        adminService.updateProject(projectId, request);
        return R.ok();
    }

    /** 更新场次售卖状态和入场规则；下架不删除历史数据。 */
    @PutMapping("/sessions/{sessionId}")
    @AdminAuditLog(action = "UPDATE", resource = "SESSION")
    public R<Void> updateSession(
            @PathVariable Long sessionId, @RequestBody AdminDtos.UpdateSessionRequest request) {
        adminService.updateSession(sessionId, request);
        return R.ok();
    }

    /** 统计场地关联的场次数量。 */
    private int sessionCountOfPlace(Long placeId) {
        Long count =
                sessionMapper.selectCount(
                        Wrappers.<Session>lambdaQuery().eq(Session::getPlaceId, placeId));
        return count == null ? 0 : count.intValue();
    }

    private Long sessionCount(Long projectId) {
        return sessionMapper.selectCount(
                Wrappers.<Session>lambdaQuery().eq(Session::getProjectId, projectId));
    }
}
