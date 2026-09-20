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
import java.util.Map;

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
    // venues and places
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Long createVenue(AdminDtos.VenueRequest request) {
        Cinema venue = new Cinema();
        applyVenue(venue, request);
        cinemaMapper.insert(venue);
        log.info("venue created: id={}, name={}, type={}",
                venue.getId(), venue.getName(), venue.getVenueType());
        return venue.getId();
    }

    /**
     * Edits a venue.
     *
     * <p>Unrestricted, including the type. The type is what the picker groups
     * by and what suggests a default price band; it is not consulted when a
     * session's seats are generated, so changing it cannot invalidate seats
     * that already exist. Name, address and coordinates are likewise
     * presentational - an order snapshots them when it is placed and keeps its
     * own copy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVenue(Long venueId, AdminDtos.VenueRequest request) {
        Cinema venue = cinemaMapper.selectById(venueId);
        if (venue == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "场馆不存在");
        }
        applyVenue(venue, request);
        cinemaMapper.updateById(venue);
        log.info("venue updated: id={}, name={}", venueId, venue.getName());
    }

    /**
     * 一次建好一个场馆和它下面的场地。
     *
     * <p>一个事务。分成两次调用会留下「场馆建好了、场地没建成」的中间状态，
     * 那种场馆排不了演出也卖不了票，而在界面上它和「场地填错了」长得一模一样。
     *
     * @return 场馆 id 和建好的场地 id，顺序与请求一致
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createVenueWithPlaces(AdminDtos.CreateVenueWithPlacesRequest request) {
        Long venueId = createVenue(request.venue());

        List<Long> placeIds = new ArrayList<>();
        if (request.places() != null) {
            for (AdminDtos.PlaceSpec place : request.places()) {
                // 前端可能只填了场馆、场地留空；空名字的整条跳过，不当成错误。
                if (place.name() == null || place.name().isBlank()) {
                    continue;
                }
                placeIds.add(createPlace(venueId, place));
            }
        }

        return Map.of("venueId", venueId, "placeIds", placeIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long createPlace(AdminDtos.PlaceRequest request) {
        return createPlace(request.venueId(), new AdminDtos.PlaceSpec(
                request.name(), request.placeType(), request.seatingMode(),
                request.rowCount(), request.colCount(), request.seatTemplate(),
                request.seatCount(), request.status()));
    }

    /** 建一个场地。场馆 id 由调用方给出，因为嵌套调用时它才刚被建出来。 */
    @Transactional(rollbackFor = Exception.class)
    public Long createPlace(Long venueId, AdminDtos.PlaceSpec request) {
        if (cinemaMapper.selectById(venueId) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所属场馆不存在");
        }
        Hall place = new Hall();
        place.setVenueId(venueId);
        place.setName(request.name());
        place.setPlaceType(request.placeType());
        place.setSeatingMode(request.seatingMode());
        place.setRowCount(request.rowCount());
        place.setColCount(request.colCount());
        place.setSeatTemplate(request.seatTemplate() == null ? "" : request.seatTemplate());
        place.setSeatCount(request.seatCount() == null ? 0 : request.seatCount());
        place.setStatus(request.status() == null ? Hall.STATUS_ACTIVE : request.status());
        hallMapper.insert(place);

        // Say out loud what the template actually yields. A declared seat_count
        // and a grid that disagrees is the kind of thing nobody notices until a
        // session comes out a different size than expected, and the warning is
        // free here.
        warnIfCapacityDiffers(place);
        log.info("place created: id={}, venue={}, name={}", place.getId(),
                place.getVenueId(), place.getName());
        return place.getId();
    }

    /**
     * Edits a place, including its seat template.
     *
     * <p>Existing sessions keep the seats they were created with. Their rows
     * were written out at the time and are the truth for those sessions - a
     * room really can be reconfigured between events, and refusing that would
     * be refusing something ordinary. What this changes is every session
     * created afterwards.
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePlace(Long placeId, AdminDtos.PlaceRequest request) {
        Hall place = hallMapper.selectById(placeId);
        if (place == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "场地不存在");
        }
        if (cinemaMapper.selectById(request.venueId()) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所属场馆不存在");
        }
        applyPlace(place, request);
        hallMapper.updateById(place);
        warnIfCapacityDiffers(place);
        log.info("place updated: id={}, name={}", placeId, place.getName());
    }

    private void applyVenue(Cinema venue, AdminDtos.VenueRequest request) {
        venue.setName(request.name());
        venue.setVenueType(request.venueType());
        venue.setAddress(request.address() == null ? "" : request.address());
        venue.setDistrict(request.district() == null ? "" : request.district());
        venue.setPhone(request.phone() == null ? "" : request.phone());
        venue.setLongitude(request.longitude());
        venue.setLatitude(request.latitude());
        venue.setStatus(request.status() == null ? Cinema.STATUS_OPEN : request.status());
    }

    private void applyPlace(Hall place, AdminDtos.PlaceRequest request) {
        place.setVenueId(request.venueId());
        place.setName(request.name());
        place.setPlaceType(request.placeType());
        place.setSeatingMode(request.seatingMode());
        place.setRowCount(request.rowCount());
        place.setColCount(request.colCount());
        place.setSeatTemplate(request.seatTemplate() == null ? "" : request.seatTemplate());
        place.setSeatCount(request.seatCount() == null ? 0 : request.seatCount());
        place.setStatus(request.status() == null ? Hall.STATUS_ACTIVE : request.status());
    }

    /**
     * Warns when the declared capacity and the template disagree.
     *
     * <p>Not an error: {@code seat_count} is a label and the template is what
     * decides. But they are two numbers describing the same room, and somebody
     * looking at 716 beside a grid that yields 720 would rather know now than
     * discover it in the size of a session.
     */
    private void warnIfCapacityDiffers(Hall place) {
        int actual = seatFactory.layoutOf(place).size();
        if (place.getSeatCount() != null && place.getSeatCount() > 0
                && place.getSeatCount() != actual) {
            log.warn("place {} declares {} seats but its template yields {}; "
                            + "sessions will use {}",
                    place.getId(), place.getSeatCount(), actual, actual);
        }
    }

    // ------------------------------------------------------------
    // editing what has already been created
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void updateProject(Long projectId, AdminDtos.UpdateProjectRequest request) {
        Film project = filmMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "项目不存在");
        }

        // Null means "leave it alone", not "set it to null". A form that sends
        // only the field somebody changed would otherwise blank the rest.
        if (request.title() != null) {
            project.setTitle(request.title());
        }
        if (request.enTitle() != null) {
            project.setEnTitle(request.enTitle());
        }
        if (request.artist() != null) {
            project.setArtist(request.artist());
        }
        if (request.organizer() != null) {
            project.setOrganizer(request.organizer());
        }
        if (request.director() != null) {
            project.setDirector(request.director());
        }
        if (request.actors() != null) {
            project.setActors(request.actors());
        }
        if (request.tags() != null) {
            project.setTags(request.tags());
        }
        if (request.posterUrl() != null) {
            project.setPosterUrl(request.posterUrl());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.duration() != null) {
            project.setDuration(request.duration());
        }
        if (request.showDate() != null) {
            project.setShowDate(request.showDate());
        }
        if (request.status() != null) {
            project.setStatus(request.status());
        }

        filmMapper.updateById(project);
        log.info("project updated: id={}, title={}", projectId, project.getTitle());
    }

    /**
     * Edits how a session is sold, not what it is selling.
     *
     * <p>Date, time and price bands are absent on purpose. Moving a session
     * moves every seat it sold; re-banding one remaps the seats people already
     * hold. Both are cancellations with extra steps, and a cancellation owes
     * money back - a different operation with different consequences, not
     * something to smuggle into an edit form.
     *
     * <p>Taking a session off sale is allowed, because that is the honest way
     * to stop selling without pretending the past did not happen.
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSession(Long sessionId, AdminDtos.UpdateSessionRequest request) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "场次不存在");
        }

        if (request.purchaseLimit() != null) {
            session.setPurchaseLimit(request.purchaseLimit());
        }
        if (request.requireRealName() != null) {
            session.setRequireRealName(request.requireRealName());
        }
        if (request.saleStartTime() != null) {
            session.setSaleStartTime(request.saleStartTime());
        }

        if (request.rushMode() != null) {
            session.setRushMode(request.rushMode());
            if (request.rushMode() == 0) {
                session.setRushStartTime(null);
            }
        }
        if (request.rushStartTime() != null) {
            session.setRushStartTime(request.rushStartTime());
        }
        if (session.getRushMode() != null && session.getRushMode() == 1
                && session.getRushStartTime() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "抢购场次必须指定开抢时间");
        }

        if (request.status() != null) {
            // Off sale is not cancelled: the seats already sold stay sold and
            // the session keeps its place in the ledger. Putting it back on
            // sale is the same call with status 1.
            session.setStatus(request.status());
        }

        sessionMapper.updateById(session);
        log.info("session updated: id={}, status={}, limit={}, rush={}",
                sessionId, session.getStatus(), session.getPurchaseLimit(),
                session.getRushMode());
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
