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
 * 创建演出，与生成演示演出相对。
 *
 * <p>值得说清的差别：{@link DemoDataService} 拿到的是一段日期窗口并把它填满，
 * 这对电影是对的，对其他一切都不对。这里拿到的是一个日期、一个场地和一组价格，
 * 并且只创建一个场次。一场公布出来的演唱会没有网格要填。
 *
 * <p>以场次为事务边界。创建一个场次要写入一个场次、它的票档和场馆里每一行座位 ——
 * 几千行 —— 而写了一半的场次会变成一个卖着座位却没有座位行的排片。
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

    /** 系统知道怎么卖的类型。 */
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
        // 一存在就是在售：票实际能不能买，由场次的售票窗口决定，不由这个字段决定。
        project.setStatus(Film.STATUS_ON_SALE);

        filmMapper.insert(project);
        log.info("project created: id={}, category={}, title={}",
                project.getId(), project.getCategory(), project.getTitle());
        return project.getId();
    }

    /**
     * 让一个项目在某一天、某个场地上架开卖。
     *
     * <p>座位经过 {@link SessionSeatFactory} 取自场馆自己的模板，所以场馆里有条过道，
     * 数据里就有条过道。座位数就是它算出来的那个 —— 不是调用方给的数字，因为两者
     * 一旦对不上，差的就是「满座」和「超卖」。
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

        // 票档先写：每个座位都要从它们那里确定自己的档，先写座位会让每个座位都没有价。
        List<SessionSeat> seats = seatFactory.buildSeats(session.getId(), tiers, layout);
        seatFactory.insert(seats);

        log.info("session created: id={}, project={}, place={}, seats={}, tiers={}, starts={}",
                session.getId(), project.getId(), place.getId(), seats.size(), tiers.size(), startTime);

        return new AdminDtos.SessionCreated(session.getId(), place.getId(),
                venueNameOf(place), place.getName(), request.showDate(),
                request.startTime(), seats.size(), tiers.size());
    }

    /** 一个项目的日期，最早的在前，好让管理员看清已经有什么。 */
    public List<Session> sessionsOf(Long projectId) {
        return sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                .eq(Session::getProjectId, projectId)
                .orderByAsc(Session::getStartTime));
    }

    /**
     * 删掉一个场次和挂在它下面的一切。
     *
     * <p>一旦卖出过座位就拒绝。一个背后有票的场次不是排期时手滑、可以随手收拾掉的
     * 东西 —— 那是有人掏过钱的，取消它意味着给这些人退款，而那是另一回事，后果也
     * 不一样。
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
    // 场馆和场地
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
     * 编辑一个场馆。
     *
     * <p>不做限制，类型也能改。类型是选择器用来分组、也是用来推荐默认票价档的依据；
     * 生成场次座位时并不查它，所以改它不会让已经存在的座位失效。名称、地址、坐标
     * 同样是展示性的 —— 下单时订单会把它们快照一份，留着自己的副本。
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

        // 把模板实际产出多少说出来。声明的 seat_count 和网格对不上这种事，没人会
        // 注意到，直到某个场次出来的大小和预期不一样；而在这里喊一声是不要钱的。
        warnIfCapacityDiffers(place);
        log.info("place created: id={}, venue={}, name={}", place.getId(),
                place.getVenueId(), place.getName());
        return place.getId();
    }

    /**
     * 编辑一个场地，座位模板也能改。
     *
     * <p>已有场次保留它们创建时的座位。那些座位行是当时写下来的，对那批场次就是事实
     * —— 一个场子在两场活动之间确实可能重新布置，拒绝这件事等于拒绝一件再平常不过
     * 的事。这里改动影响的是之后创建的每一个场次。
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
     * 声明的容量和模板对不上时给出警告。
     *
     * <p>这不是错误：{@code seat_count} 是个标签，说话算数的是模板。但它们是描述
     * 同一个场子的两个数字，而一个人看着 716 旁边摆着一个得出 720 的网格，宁愿现在
     * 就知道，也不想等到某个场次的大小上才发现。
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
    // 编辑已经创建出来的东西
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void updateProject(Long projectId, AdminDtos.UpdateProjectRequest request) {
        Film project = filmMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "项目不存在");
        }

        // null 的含义是「别动它」，不是「把它设成 null」。否则只提交了改动字段的
        // 表单，会把其余字段全清空。
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
     * 改的是一个场次怎么卖，不是它在卖什么。
     *
     * <p>日期、时间和票价档是故意不放进来的。挪动一个场次等于挪动它卖出去的每一个
     * 座位；重新划分票档会把人们已经握在手里的座位重新映射。两者都是换了个说法的
     * 取消，而取消是要退钱的 —— 那是另一回事、另一种后果，不该偷偷塞进一个编辑
     * 表单里。
     *
     * <p>把场次下架是允许的，因为那才是停止售卖的诚实做法：不必假装过去没发生过。
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
            // 下架不等于取消：已经卖出的座位仍然算卖出，场次在账本里的位置也保留。
            // 重新上架就是同样的调用、status 传 1。
            session.setStatus(request.status());
        }

        sessionMapper.updateById(session);
        log.info("session updated: id={}, status={}, limit={}, rush={}",
                sessionId, session.getStatus(), session.getPurchaseLimit(),
                session.getRushMode());
    }

    // ------------------------------------------------------------

    /**
     * 一个场地同一时间只装得下一件事。
     *
     * <p>数据库用唯一键强制这一点，但唯一键只在插入真正执行时才生效 —— 到那时调用方
     * 拿到的是一个约束冲突，而不是一句解释。先查一次，把它变成一句人能照着行动的话。
     * 而在并发下真正让这件事成立的，仍然是那个唯一键。
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
     * 票档得先讲得通，才写进去。
     *
     * <p>票档之间留出空档，就意味着有座位不属于任何一档，而那些座位定不出价来。
     * 工厂会兜底落到最后一档，所以空档是无声的 —— 这正是它要在这里被检查、而不是
     * 等到开票时才发现的原因。
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

        // 列表页上那个大字的价格：最便宜的进场方式。一个座位实际花多少钱由它的档
        // 决定。
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
                // 没有它，这场售卖就是直接敞开的，队列也没有东西可拦 —— 那和对方
                // 要的东西不是一回事。
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
