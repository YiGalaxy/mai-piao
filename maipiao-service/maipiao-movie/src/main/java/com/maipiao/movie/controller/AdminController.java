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
 * The admin surface: saying what will be on sale.
 *
 * <p>Reachable only by an administrator. The gateway rejects
 * {@code /api/*&#47;admin/**} unless the token carries the admin role, in the
 * same place and by the same mechanism as the {@code /inner} block - before
 * the public whitelist is consulted, so a whitelist entry covering a whole
 * service cannot accidentally open this.
 *
 * <p>One controller rather than several, because what it does is one job:
 * create a thing, put it on sale, say when. Three controllers for three nouns
 * would be three places to get the authorization annotation wrong.
 */
@Slf4j
@RestController
// /movie/admin, not /admin: the gateway strips one prefix segment, so a
// request to /api/movie/admin/... arrives here as /movie/admin/... The public
// controller is under /movie for the same reason.
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
    // what exists
    // ------------------------------------------------------------

    /**
     * Venues and their rooms.
     *
     * <p>Nested rather than flat because that is how the choice is made: you
     * pick a stadium, then you pick which part of it. A flat list of a hundred
     * rooms with no venue attached is a list nobody can use.
     *
     * <p>Returns everything the edit forms need as well as what the picker
     * needs, so opening a venue to change its phone number does not require a
     * second round trip for the fields that were not on the first one.
     *
     * <p>{@code includeClosed} exists because this list is also the
     * management screen: a venue taken out of service has to remain visible to
     * whoever took it out, or the action looks like a deletion.
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
                // What the template actually yields, beside what the venue
                // declares. The two are allowed to differ - one is a label and
                // the other decides - but a person editing should see both.
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

    /** Projects, so the screen can list what is already there. */
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

    /** A project's dates. What an administrator checks after adding one. */
    @GetMapping("/projects/{projectId}/sessions")
    public R<List<Session>> sessions(@PathVariable Long projectId) {
        return R.ok(adminService.sessionsOf(projectId));
    }

    // ------------------------------------------------------------
    // creating
    // ------------------------------------------------------------

    @PostMapping("/projects")
    public R<Long> createProject(@Valid @RequestBody AdminDtos.CreateProjectRequest request) {
        return R.ok(adminService.createProject(request));
    }

    /**
     * Puts a project on sale for one date at one place.
     *
     * <p>One call, one night. A tour stop that plays three nights is three
     * calls, which is honest: they are three separate things to put on sale,
     * with their own seats and their own inventory. A repeating schedule would
     * be the cinema model again.
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
    // venues and places
    // ------------------------------------------------------------

    @PostMapping("/venues")
    public R<Long> createVenue(@Valid @RequestBody AdminDtos.VenueRequest request) {
        return R.ok(adminService.createVenue(request));
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
     * Edits a room, seat template included.
     *
     * <p>Existing sessions are untouched: their seat rows were written when
     * they were created, and a room really can be reconfigured between events.
     */
    @PutMapping("/places/{placeId}")
    public R<Void> updatePlace(@PathVariable Long placeId,
                               @Valid @RequestBody AdminDtos.PlaceRequest request) {
        adminService.updatePlace(placeId, request);
        return R.ok();
    }

    // ------------------------------------------------------------
    // editing
    // ------------------------------------------------------------

    @PutMapping("/projects/{projectId}")
    public R<Void> updateProject(@PathVariable Long projectId,
                                 @RequestBody AdminDtos.UpdateProjectRequest request) {
        adminService.updateProject(projectId, request);
        return R.ok();
    }

    /**
     * Changes how a session sells, not what it is selling.
     *
     * <p>Date, time and price bands are not editable here - see the request
     * record for why. {@code status} 0 takes it off sale without cancelling
     * anything.
     */
    @PutMapping("/sessions/{sessionId}")
    public R<Void> updateSession(@PathVariable Long sessionId,
                                 @RequestBody AdminDtos.UpdateSessionRequest request) {
        adminService.updateSession(sessionId, request);
        return R.ok();
    }

    // ------------------------------------------------------------

    /**
     * How many sessions use this room.
     *
     * <p>Shown next to it because editing a seat template changes what future
     * sessions are built from, and somebody about to do that should know how
     * many already exist - not to block them, but so the change is deliberate.
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
