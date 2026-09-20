package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.movie.dto.AdminDtos;
import com.maipiao.movie.entity.Cinema;
import com.maipiao.movie.entity.Film;
import com.maipiao.movie.entity.Hall;
import com.maipiao.movie.entity.PriceTier;
import com.maipiao.movie.entity.Session;
import com.maipiao.movie.entity.SessionSeat;
import com.maipiao.movie.mapper.CinemaMapper;
import com.maipiao.movie.mapper.FilmMapper;
import com.maipiao.movie.mapper.HallMapper;
import com.maipiao.movie.mapper.PriceTierMapper;
import com.maipiao.movie.mapper.SessionMapper;
import com.maipiao.movie.mapper.SessionSeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Creating performances, as opposed to generating demo ones.
 *
 * <p>The difference worth stating: {@link DemoDataService} is handed a window
 * of days and fills it, which is right for a film and was wrong for everything
 * else. This is handed a date, a place and a set of prices, and creates
 * exactly one session. An announced concert has no grid to fill.
 *
 * <p>Transactional per session. Creating one writes a session, its bands and
 * every seat row in the hall - a few thousand rows - and a half-written
 * session would be a screening that sells seats it has no rows for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPerformanceService {

    private final FilmMapper filmMapper;
    private final HallMapper hallMapper;
    private final SessionMapper sessionMapper;
    private final PriceTierMapper priceTierMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final SessionSeatFactory seatFactory;
    private final CinemaMapper cinemaMapper;

    /** Categories the system knows how to sell. */
    private static final List<String> CATEGORIES =
            List.of("MOVIE", "CONCERT", "TALK_SHOW", "THEATER", "MUSICAL");

    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Long createProject(AdminDtos.CreateProjectRequest request) {
        if (!CATEGORIES.contains(request.category())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未知的类型：" + request.category() + "，可选 " + String.join("/", CATEGORIES));
        }

        Film project = new Film();
        project.setTitle(request.title());
        project.setEnTitle(request.enTitle() == null ? "" : request.enTitle());
        project.setCategory(request.category());
        project.setArtist(request.artist() == null ? "" : request.artist());
        project.setOrganizer(request.organizer() == null ? "" : request.organizer());
        project.setDirector(request.director() == null ? "" : request.director());
        project.setActors(request.actors() == null ? "" : request.actors());
        project.setTags(request.tags() == null ? "" : request.tags());
        project.setPosterUrl(request.posterUrl() == null ? "" : request.posterUrl());
        project.setDescription(request.description() == null ? "" : request.description());
        project.setDuration(request.duration());
        project.setShowDate(request.showDate());
        project.setScore(java.math.BigDecimal.ZERO);
        // On sale from the moment it exists: whether tickets can actually be
        // bought is decided by the session's sale window, not by this.
        project.setStatus(Film.STATUS_ON_SALE);

        filmMapper.insert(project);
        log.info("project created: id={}, category={}, title={}",
                project.getId(), project.getCategory(), project.getTitle());
        return project.getId();
    }

    /**
     * Puts a project on sale for one date at one place.
     *
     * <p>The seats come from the hall's own template through
     * {@link SessionSeatFactory}, so an aisle in the venue is an aisle in the
     * data. The number of seats is whatever that produces - not a number the
     * caller supplies, because the two disagreeing is the difference between a
     * full house and an oversold one.
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminDtos.SessionCreated createSession(AdminDtos.CreateSessionRequest request) {
        Film project = filmMapper.selectById(request.projectId());
        if (project == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "项目不存在");
        }

        Hall place = hallMapper.selectById(request.placeId());
        if (place == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "场馆不存在");
        }

        LocalDateTime startTime = LocalDateTime.of(request.showDate(), request.startTime());
        requirePlaceFree(place, startTime);
        validateTiers(request.tierSpecs());

        List<SessionSeatFactory.SeatPosition> layout = seatFactory.layoutOf(place);
        if (layout.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该场馆没有可用座位");
        }

        Session session = buildSession(project, place, startTime, layout.size(), request);
        sessionMapper.insert(session);

        List<PriceTier> tiers = insertTiers(session.getId(), request.tierSpecs());

        // Bands first: every seat resolves its band from them, so writing the
        // seats before the bands would leave every seat unpriced.
        List<SessionSeat> seats = seatFactory.buildSeats(session.getId(), tiers, layout);
        seatFactory.insert(seats);

        log.info("session created: id={}, project={}, place={}, seats={}, tiers={}, starts={}",
                session.getId(), project.getId(), place.getId(), seats.size(), tiers.size(), startTime);

        return new AdminDtos.SessionCreated(session.getId(), place.getId(),
                venueNameOf(place), place.getName(), request.showDate(),
                request.startTime(), seats.size(), tiers.size());
    }

    /** A project's dates, newest first, so an administrator sees what exists. */
    public List<Session> sessionsOf(Long projectId) {
        return sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                .eq(Session::getProjectId, projectId)
                .orderByAsc(Session::getStartTime));
    }

    /**
     * Removes a session and everything hanging off it.
     *
     * <p>Refuses once a seat has been sold. A session with tickets behind it is
     * not a scheduling mistake to be tidied away - it is something people have
     * paid for, and cancelling it means refunding them, which is a different
     * operation with different consequences.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        if (session.getSoldSeat() != null && session.getSoldSeat() > 0) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE,
                    "该场次已售出 " + session.getSoldSeat() + " 张票，不能直接删除");
        }

        sessionSeatMapper.delete(Wrappers.<SessionSeat>lambdaQuery()
                .eq(SessionSeat::getSessionId, sessionId));
        priceTierMapper.delete(Wrappers.<PriceTier>lambdaQuery()
                .eq(PriceTier::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        log.info("session deleted: id={}", sessionId);
    }

    // ------------------------------------------------------------

    /**
     * A place holds one thing at a time.
     *
     * <p>The database enforces this with a unique key, but only when the insert
     * runs - by which point the caller has a constraint violation instead of an
     * explanation. Checking first turns it into a sentence somebody can act on.
     * The key is still the thing that makes it true under concurrency.
     */
    private void requirePlaceFree(Hall place, LocalDateTime startTime) {
        Session clash = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                .eq(Session::getPlaceId, place.getId())
                .eq(Session::getStartTime, startTime)
                .last("LIMIT 1"));
        if (clash != null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    place.getName() + " 在该时间已有安排");
        }
    }

    /**
     * Bands have to make sense before they are written.
     *
     * <p>A gap between bands means seats no band covers, and those seats price
     * at nothing. The factory falls back to the last band, so a gap is silent -
     * which is exactly why it is checked here rather than discovered on an
     * invoice.
     */
    private void validateTiers(List<AdminDtos.TierSpec> specs) {
        for (AdminDtos.TierSpec spec : specs) {
            int end = spec.rowEnd() == null ? 0 : spec.rowEnd();
            if (end != 0 && end < spec.rowStart()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "票档「" + spec.name() + "」的结束排早于起始排");
            }
        }
    }

    private List<PriceTier> insertTiers(Long sessionId, List<AdminDtos.TierSpec> specs) {
        List<PriceTier> tiers = new ArrayList<>(specs.size());
        for (AdminDtos.TierSpec spec : specs) {
            PriceTier tier = new PriceTier();
            tier.setSessionId(sessionId);
            tier.setName(spec.name());
            tier.setPrice(spec.price());
            tier.setRowStart(spec.rowStart());
            tier.setRowEnd(spec.rowEnd() == null ? 0 : spec.rowEnd());
            tier.setColor(spec.color() == null ? "#ff6700" : spec.color());
            priceTierMapper.insert(tier);
            tiers.add(tier);
        }
        return tiers;
    }

    private Session buildSession(Film project, Hall place, LocalDateTime startTime,
                                 int totalSeat, AdminDtos.CreateSessionRequest request) {
        Session session = new Session();
        session.setProjectId(project.getId());
        session.setVenueId(place.getVenueId());
        session.setPlaceId(place.getId());
        session.setShowDate(request.showDate());
        session.setStartTime(startTime);
        session.setEndTime(startTime.plusMinutes(
                project.getDuration() == null ? 120 : project.getDuration()));

        // The headline figure on the listing: the cheapest way in. What a seat
        // actually costs comes from its band.
        session.setPrice(request.tierSpecs().stream()
                .map(AdminDtos.TierSpec::price)
                .min(java.math.BigDecimal::compareTo)
                .orElse(java.math.BigDecimal.ZERO));

        session.setTotalSeat(totalSeat);
        session.setLockedSeat(0);
        session.setSoldSeat(0);
        session.setStatus(Session.STATUS_ON_SALE);

        session.setSeatMode(request.seatMode() == null ? 0 : request.seatMode());
        session.setPurchaseLimit(request.purchaseLimit() == null ? 0 : request.purchaseLimit());
        session.setRequireRealName(request.requireRealName() == null ? 0 : request.requireRealName());
        session.setSaleStartTime(request.saleStartTime());

        int rushMode = request.rushMode() == null ? 0 : request.rushMode();
        session.setRushMode(rushMode);
        if (rushMode == 1) {
            if (request.rushStartTime() == null) {
                // Without it the sale is simply open, and the queue has nothing
                // to gate - which is a different thing from what was asked for.
                throw new BizException(ErrorCode.PARAM_ERROR, "抢购场次必须指定开抢时间");
            }
            session.setRushStartTime(request.rushStartTime());
        }

        return session;
    }

    private String venueNameOf(Hall place) {
        Cinema venue = cinemaMapper.selectById(place.getVenueId());
        return venue == null ? "" : venue.getName();
    }

}
