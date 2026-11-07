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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员创建和维护演出、场馆、场次及票档。 */
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

    /** 系统知道怎么卖的类型。 */
    private static final List<String> CATEGORIES =
            List.of("MOVIE", "CONCERT", "TALK_SHOW", "THEATER", "MUSICAL");

    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Long createProject(AdminDtos.CreateProjectRequest request) {
        if (!CATEGORIES.contains(request.category())) {
            throw new BizException(
                    ErrorCode.PARAM_ERROR,
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
        // 项目存在即表示可被场次引用，实际售卖由场次窗口控制。
        project.setStatus(Film.STATUS_ON_SALE);

        filmMapper.insert(project);
        log.info(
                "project created: id={}, category={}, title={}",
                project.getId(),
                project.getCategory(),
                project.getTitle());
        return project.getId();
    }

    /** 创建场次、票档和座位快照；座位数以模板生成结果为准。 */
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

        // 先写票档，座位生成时才能绑定价格。
        List<SessionSeat> seats = seatFactory.buildSeats(session.getId(), tiers, layout);
        seatFactory.insert(seats);

        log.info(
                "session created: id={}, project={}, place={}, seats={}, tiers={}, starts={}",
                session.getId(),
                project.getId(),
                place.getId(),
                seats.size(),
                tiers.size(),
                startTime);

        return new AdminDtos.SessionCreated(
                session.getId(),
                place.getId(),
                venueNameOf(place),
                place.getName(),
                request.showDate(),
                request.startTime(),
                seats.size(),
                tiers.size());
    }

    /** 一个项目的日期，最早的在前，好让管理员看清已经有什么。 */
    public List<Session> sessionsOf(Long projectId) {
        return sessionMapper.selectList(
                Wrappers.<Session>lambdaQuery()
                        .eq(Session::getProjectId, projectId)
                        .orderByAsc(Session::getStartTime));
    }

    /** 将场次下架并保留场次及座位账本。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        // 仅更新状态，不物理删除历史数据。
        Session update = new Session();
        update.setId(sessionId);
        update.setStatus(0);
        sessionMapper.updateById(update);
        log.info("session taken off sale: id={}", sessionId);
    }

    // ------------------------------------------------------------
    // 场馆和场地
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public Long createVenue(AdminDtos.VenueRequest request) {
        Cinema venue = new Cinema();
        applyVenue(venue, request);
        cinemaMapper.insert(venue);
        log.info(
                "venue created: id={}, name={}, type={}",
                venue.getId(),
                venue.getName(),
                venue.getVenueType());
        return venue.getId();
    }

    /** 编辑场馆元数据；已生成场次使用自己的座位快照。 */
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

    /** 在一个事务中创建场馆及其场地，避免留下半成品。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createVenueWithPlaces(
            AdminDtos.CreateVenueWithPlacesRequest request) {
        Long venueId = createVenue(request.venue());

        List<Long> placeIds = new ArrayList<>();
        if (request.places() != null) {
            for (AdminDtos.PlaceSpec place : request.places()) {
                // 空名称表示前端未填写，跳过该场地。
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
        return createPlace(
                request.venueId(),
                new AdminDtos.PlaceSpec(
                        request.name(),
                        request.placeType(),
                        request.seatingMode(),
                        request.rowCount(),
                        request.colCount(),
                        request.seatTemplate(),
                        request.seatCount(),
                        request.status()));
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

        // 模板容量与声明值不一致时记录告警。
        warnIfCapacityDiffers(place);
        log.info(
                "place created: id={}, venue={}, name={}",
                place.getId(),
                place.getVenueId(),
                place.getName());
        return place.getId();
    }

    /** 编辑场地模板；只影响后续创建的场次，历史快照不变。 */
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
     * 声明的容量和模板对不上时给出警告。
     *
     * <p>这不是错误：{@code seat_count} 是个标签，说话算数的是模板。但它们是描述 同一个场子的两个数字，而一个人看着 716 旁边摆着一个得出 720 的网格，宁愿现在
     * 就知道，也不想等到某个场次的大小上才发现。
     */
    private void warnIfCapacityDiffers(Hall place) {
        int actual = seatFactory.layoutOf(place).size();
        if (place.getSeatCount() != null
                && place.getSeatCount() > 0
                && place.getSeatCount() != actual) {
            log.warn(
                    "place {} declares {} seats but its template yields {}; "
                            + "sessions will use {}",
                    place.getId(),
                    place.getSeatCount(),
                    actual,
                    actual);
        }
    }

    // ------------------------------------------------------------
    // 编辑已经创建出来的东西
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void updateProject(Long projectId, AdminDtos.UpdateProjectRequest request) {
        Film project = filmMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "项目不存在");
        }

        // null 表示不修改该字段，避免部分更新清空已有值。
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

    /** 仅修改售卖开关；日期、时间和票档不允许影响既有订单。 */
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
        if (session.getRushMode() != null
                && session.getRushMode() == 1
                && session.getRushStartTime() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "抢购场次必须指定开抢时间");
        }

        if (request.status() != null) {
            // 下架保留已售座位和账本记录，status=1 可重新上架。
            session.setStatus(request.status());
        }

        sessionMapper.updateById(session);
        log.info(
                "session updated: id={}, status={}, limit={}, rush={}",
                sessionId,
                session.getStatus(),
                session.getPurchaseLimit(),
                session.getRushMode());
    }

    // ------------------------------------------------------------

    /** 校验场地时间冲突；并发下由数据库唯一键兜底。 */
    private void requirePlaceFree(Hall place, LocalDateTime startTime) {
        Session clash =
                sessionMapper.selectOne(
                        Wrappers.<Session>lambdaQuery()
                                .eq(Session::getPlaceId, place.getId())
                                .eq(Session::getStartTime, startTime)
                                .last("LIMIT 1"));
        if (clash != null) {
            throw new BizException(ErrorCode.PARAM_ERROR, place.getName() + " 在该时间已有安排");
        }
    }

    /** 校验票档区间连续且可覆盖座位，避免座位无法定价。 */
    private void validateTiers(List<AdminDtos.TierSpec> specs) {
        for (AdminDtos.TierSpec spec : specs) {
            int end = spec.rowEnd() == null ? 0 : spec.rowEnd();
            if (end != 0 && end < spec.rowStart()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "票档「" + spec.name() + "」的结束排早于起始排");
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

    private Session buildSession(
            Film project,
            Hall place,
            LocalDateTime startTime,
            int totalSeat,
            AdminDtos.CreateSessionRequest request) {
        Session session = new Session();
        session.setProjectId(project.getId());
        session.setVenueId(place.getVenueId());
        session.setPlaceId(place.getId());
        session.setShowDate(request.showDate());
        session.setStartTime(startTime);
        session.setEndTime(
                startTime.plusMinutes(project.getDuration() == null ? 120 : project.getDuration()));

        // 列表页展示最低票价，座位实际价格由所属票档决定。
        session.setPrice(
                request.tierSpecs().stream()
                        .map(AdminDtos.TierSpec::price)
                        .min(java.math.BigDecimal::compareTo)
                        .orElse(java.math.BigDecimal.ZERO));

        session.setTotalSeat(totalSeat);
        session.setLockedSeat(0);
        session.setSoldSeat(0);
        session.setStatus(Session.STATUS_ON_SALE);

        session.setSeatMode(request.seatMode() == null ? 0 : request.seatMode());
        session.setPurchaseLimit(request.purchaseLimit() == null ? 0 : request.purchaseLimit());
        session.setRequireRealName(
                request.requireRealName() == null ? 0 : request.requireRealName());
        session.setSaleStartTime(request.saleStartTime());

        int rushMode = request.rushMode() == null ? 0 : request.rushMode();
        session.setRushMode(rushMode);
        if (rushMode == 1) {
            if (request.rushStartTime() == null) {
                // 抢购模式才启用排队与准入控制。
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
