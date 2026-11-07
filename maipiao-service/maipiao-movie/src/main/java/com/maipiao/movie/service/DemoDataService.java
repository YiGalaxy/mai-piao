package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.movie.entity.Film;
import com.maipiao.movie.entity.Hall;
import com.maipiao.movie.entity.PriceTier;
import com.maipiao.movie.entity.Session;
import com.maipiao.movie.entity.SessionSeat;
import com.maipiao.movie.mapper.FilmMapper;
import com.maipiao.movie.mapper.HallMapper;
import com.maipiao.movie.mapper.PriceTierMapper;
import com.maipiao.movie.mapper.SessionMapper;
import com.maipiao.movie.mapper.SessionSeatMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 生成演示场次、票档和座位；电影、演出、站席共用库存模型。生成过程不使用大事务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private static final LocalTime[] FILM_SLOTS = {
        LocalTime.of(10, 0),
        LocalTime.of(13, 0),
        LocalTime.of(16, 0),
        LocalTime.of(19, 0),
        LocalTime.of(21, 30),
    };

    /** 演出都在晚上；只留两个时段，好让演示数据集看得过来。 */
    private static final LocalTime[] SHOW_SLOTS = {
        LocalTime.of(19, 30), LocalTime.of(20, 30),
    };

    /** 标记本生成器创建的数据，用于拒绝覆盖已有演示场次。 */
    private static final String SOURCE_GENERATED = "DEMO";

    /** 标记管理员创建的数据，生成器不能碰。 */
    private static final String SOURCE_ADMIN = "ADMIN";

    private static final int BATCH_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long SHOWCASE_PLACE_ID = 3199L;
    private static final long SHOWCASE_PROJECT_ID = 1199L;
    private static final LocalTime SHOWCASE_SLOT = LocalTime.of(19, 30);
    private static final int SHOWCASE_NIGHTS = 2;

    /** showcase 的固定票档，不从场馆类型推导。 */
    private static final List<PriceTier> SHOWCASE_TIERS =
            List.of(
                    showcaseTier("内场VIP", 1980, 1, 8, "#e91e63"),
                    showcaseTier("内场", 1280, 9, 20, "#ff6700"),
                    showcaseTier("看台A", 880, 21, 32, "#2196f3"),
                    showcaseTier("看台B", 580, 33, 40, "#4caf50"));

    private static PriceTier showcaseTier(
            String name, float price, int rowStart, int rowEnd, String color) {
        PriceTier tier = new PriceTier();
        tier.setName(name);
        tier.setPrice(BigDecimal.valueOf(Math.round(price)).setScale(2, RoundingMode.HALF_UP));
        tier.setRowStart(rowStart);
        tier.setRowEnd(rowEnd);
        tier.setColor(color);
        return tier;
    }

    private final FilmMapper filmMapper;
    private final HallMapper hallMapper;
    private final SessionMapper sessionMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final PriceTierMapper priceTierMapper;
    private final SessionSeatFactory seatFactory;

    private final Random random = new Random(20260920L);

    public record GenerateResult(int sessionCount, int seatCount, int tierCount, long elapsedMs) {}

    /** 清理前确认不存在演示交易或预约。 */
    @Transactional(rollbackFor = Exception.class)
    public void clearSchedules() {
        List<Session> generated =
                sessionMapper.selectList(
                        Wrappers.<Session>lambdaQuery().eq(Session::getSource, SOURCE_GENERATED));
        if (generated.isEmpty()) {
            log.info("no generated sessions to clear");
            return;
        }

        // 临时预约尚未写入库存计数，不能仅凭 sold/locked 为零证明可以删除。
        throw new IllegalStateException("已有演示场次，请使用独立开发数据库重置，禁止在线清理场次");
    }

    /** 生成固定 2000 座体育场 showcase；已有数据时拒绝覆盖。 */
    public GenerateResult generateShowcase() {
        long started = System.currentTimeMillis();

        Hall place = hallMapper.selectById(SHOWCASE_PLACE_ID);
        Film project = filmMapper.selectById(SHOWCASE_PROJECT_ID);
        if (place == null || project == null) {
            throw new IllegalStateException(
                    "showcase venue or project is missing; run docs/sql/demo/03_showcase.sql first");
        }

        // 拒绝覆盖可能已承接预约或交易的场次。
        List<Session> existing =
                sessionMapper.selectList(
                        Wrappers.<Session>lambdaQuery()
                                .eq(Session::getProjectId, SHOWCASE_PROJECT_ID));
        if (!existing.isEmpty()) {
            throw new IllegalStateException("showcase 已存在，禁止覆盖可能承接交易的场次");
        }

        List<SessionSeatFactory.SeatPosition> layout = seatFactory.layoutOf(place);
        int sessionCount = 0;
        int seatCount = 0;
        int tierCount = 0;

        LocalDate firstNight =
                project.getShowDate() == null
                        ? LocalDate.now().plusDays(56)
                        : project.getShowDate();
        LocalDateTime now = LocalDateTime.now();

        // 两晚使用相同座位和票档，一晚排队、一晚直售。
        for (int i = 0; i < SHOWCASE_NIGHTS; i++) {
            LocalDate showDate = firstNight.plusDays(i);
            boolean rush = i == 0;

            Session session =
                    buildShowcaseSession(
                            project,
                            place,
                            showDate,
                            LocalDateTime.of(showDate, SHOWCASE_SLOT),
                            layout.size(),
                            rush,
                            now);
            sessionMapper.insert(session);

            for (PriceTier tier : showcaseTiers()) {
                tier.setSessionId(session.getId());
                priceTierMapper.insert(tier);
            }
            tierCount += SHOWCASE_TIERS.size();

            // showcase 保持全量可售，便于压测。
            List<SessionSeat> seats =
                    seatFactory.buildSeats(session.getId(), sessionTiers(session), layout);
            seatFactory.insert(seats);

            sessionCount++;
            seatCount += seats.size();
            log.info(
                    "showcase session: id={}, rush={}, seats={}, starts={}",
                    session.getId(),
                    rush,
                    seats.size(),
                    session.getStartTime());
        }

        return new GenerateResult(
                sessionCount, seatCount, tierCount, System.currentTimeMillis() - started);
    }

    /** 复制一组独立的票档实体。 */
    private List<PriceTier> sessionTiers(Session session) {
        return priceTierMapper.selectList(
                Wrappers.<PriceTier>lambdaQuery()
                        .eq(PriceTier::getSessionId, session.getId())
                        .orderByAsc(PriceTier::getRowStart));
    }

    private Session buildShowcaseSession(
            Film project,
            Hall place,
            LocalDate showDate,
            LocalDateTime startTime,
            int totalSeat,
            boolean rush,
            LocalDateTime now) {
        Session session = new Session();
        session.setProjectId(project.getId());
        session.setVenueId(place.getVenueId());
        session.setPlaceId(place.getId());
        session.setShowDate(showDate);
        session.setStartTime(startTime);
        session.setEndTime(
                startTime.plusMinutes(project.getDuration() == null ? 180 : project.getDuration()));

        session.setPrice(SHOWCASE_TIERS.get(SHOWCASE_TIERS.size() - 1).getPrice());
        session.setTotalSeat(totalSeat);
        session.setLockedSeat(0);
        session.setSoldSeat(0);
        session.setStatus(Session.STATUS_ON_SALE);

        // 1 = 系统分配座位，这正是 showcase 的要点：体育场演唱会不可能让 2000 个人
        // 自己在座位图上挑。
        session.setSeatMode(1);
        session.setSource(SOURCE_GENERATED);
        session.setSaleStartTime(now.minusMinutes(1));
        session.setPurchaseLimit(rush ? 2 : 4);
        session.setRequireRealName(1);

        if (rush) {
            // 延后开抢，保留可观察的排队窗口。
            session.setRushMode(1);
            session.setRushStartTime(now.plusMinutes(5));
        } else {
            session.setRushMode(0);
        }
        return session;
    }

    /** 四个票档，按体育场演唱会的定法写死，不从场馆推导。 */
    private List<PriceTier> showcaseTiers() {
        List<PriceTier> tiers = new ArrayList<>(SHOWCASE_TIERS.size());
        for (PriceTier template : SHOWCASE_TIERS) {
            PriceTier tier = new PriceTier();
            tier.setName(template.getName());
            tier.setPrice(template.getPrice());
            tier.setRowStart(template.getRowStart());
            tier.setRowEnd(template.getRowEnd());
            tier.setColor(template.getColor());
            tiers.add(tier);
        }
        return tiers;
    }

    /** 按电影网格和演出日期分别生成场次。 */
    public GenerateResult generate(int days, double soldRatio, boolean rushSchedule) {
        long started = System.currentTimeMillis();

        List<Hall> places =
                hallMapper.selectList(
                        Wrappers.<Hall>lambdaQuery().eq(Hall::getStatus, Hall.STATUS_ACTIVE));
        List<Film> projects =
                filmMapper.selectList(
                        Wrappers.<Film>lambdaQuery()
                                .in(Film::getStatus, Film.STATUS_UPCOMING, Film.STATUS_ON_SALE)
                                .orderByAsc(Film::getId));

        if (places.isEmpty() || projects.isEmpty()) {
            throw new IllegalStateException(
                    "need places and projects before generating sessions; "
                            + "run docs/sql/schema/*.sql and docs/sql/demo/*.sql first");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        GenerateResult screenings =
                generateScreenings(places, projects, days, soldRatio, today, now);
        GenerateResult runs =
                generatePerformanceRuns(
                        places, projects, days, soldRatio, today, now, rushSchedule);

        GenerateResult total =
                new GenerateResult(
                        screenings.sessionCount() + runs.sessionCount(),
                        screenings.seatCount() + runs.seatCount(),
                        screenings.tierCount() + runs.tierCount(),
                        System.currentTimeMillis() - started);

        log.info(
                "demo sessions generated: screenings={}, performance runs={}, seats={}, "
                        + "tiers={}, elapsed={}ms",
                screenings.sessionCount(),
                runs.sessionCount(),
                total.seatCount(),
                total.tierCount(),
                total.elapsedMs());
        return total;
    }

    /** 生成电影院的日常排片网格。 */
    private GenerateResult generateScreenings(
            List<Hall> places,
            List<Film> projects,
            int days,
            double soldRatio,
            LocalDate today,
            LocalDateTime now) {
        int sessionCount = 0;
        int seatCount = 0;
        int tierCount = 0;
        int cursor = 0;

        for (int dayOffset = 0; dayOffset < days; dayOffset++) {
            LocalDate showDate = today.plusDays(dayOffset);

            for (Hall place : places) {
                if (!isCinemaPlace(place)) {
                    continue;
                }
                for (LocalTime slot : FILM_SLOTS) {
                    LocalDateTime startTime = LocalDateTime.of(showDate, slot);
                    if (!startTime.isAfter(now)) {
                        continue;
                    }

                    Film project = nextMatching(projects, place, cursor++);
                    if (project == null) {
                        continue;
                    }

                    WrittenSession written =
                            writeSession(project, place, showDate, startTime, soldRatio);
                    sessionCount++;
                    seatCount += written.seatCount();
                    tierCount += written.tierCount();
                }
            }
        }

        return new GenerateResult(sessionCount, seatCount, tierCount, 0);
    }

    /** 按场馆容量和档期生成演出日期，每晚最多一场。 */
    private GenerateResult generatePerformanceRuns(
            List<Hall> places,
            List<Film> projects,
            int days,
            double soldRatio,
            LocalDate today,
            LocalDateTime now,
            boolean rushSchedule) {
        List<Hall> performanceVenues =
                places.stream().filter(place -> !isCinemaPlace(place)).toList();
        // 已由管理员排期的项目不再自动追加日期。
        Set<Long> handScheduled =
                sessionMapper
                        .selectList(
                                Wrappers.<Session>lambdaQuery()
                                        .eq(Session::getSource, SOURCE_ADMIN))
                        .stream()
                        .map(Session::getProjectId)
                        .collect(java.util.stream.Collectors.toSet());

        List<Film> performances =
                projects.stream()
                        .filter(Film::isPerformance)
                        .filter(project -> !handScheduled.contains(project.getId()))
                        .toList();

        if (performanceVenues.isEmpty() || performances.isEmpty()) {
            return new GenerateResult(0, 0, 0, 0);
        }

        int sessionCount = 0;
        int seatCount = 0;
        int tierCount = 0;
        Session rushTarget = null;

        // 记录已占用的场地时段，避免触发唯一键冲突。
        Set<String> taken = new HashSet<>();
        for (Session existing :
                sessionMapper.selectList(
                        Wrappers.<Session>lambdaQuery()
                                .in(
                                        Session::getPlaceId,
                                        performanceVenues.stream().map(Hall::getId).toList()))) {
            taken.add(existing.getPlaceId() + "@" + existing.getStartTime());
        }

        for (int i = 0; i < performances.size(); i++) {
            Film project = performances.get(i);
            Hall venue = performanceVenues.get(i % performanceVenues.size());

            // 大场馆少排几晚，小场馆支持驻演。
            boolean bigRoom = "ARENA".equals(venue.getPlaceType());
            int nights = bigRoom ? (i % 2 == 0 ? 2 : 1) : 2 + (i % 3);

            // 巡演日期错开，避免连续占用同一场地。
            int gap = Math.max(1, (days - 1) / Math.max(1, nights));
            LocalTime slot = slotsFor(venue)[0];

            int placed = 0;
            int offset = 2 + (i % 3);
            // 仅在当前生成窗口内寻找空档。
            for (int day = offset; day < days && placed < nights; day++) {
                if (placed > 0 && (day - offset) % gap != 0) {
                    continue;
                }

                LocalDate showDate = today.plusDays(day);
                LocalDateTime startTime = LocalDateTime.of(showDate, slot);
                if (!startTime.isAfter(now)) {
                    continue;
                }
                if (!taken.add(venue.getId() + "@" + startTime)) {
                    continue;
                }

                WrittenSession written =
                        writeSession(project, venue, showDate, startTime, soldRatio);
                sessionCount++;
                seatCount += written.seatCount();
                tierCount += written.tierCount();
                rushTarget = written.session();
                placed++;
            }
        }

        if (rushSchedule && rushTarget != null && sessionCount > 0) {
            rushTarget.setRushMode(1);
            rushTarget.setRushStartTime(LocalDateTime.now().plusMinutes(2));
            sessionMapper.updateById(rushTarget);
            log.info("rush session marked: sessionId={}", rushTarget.getId());
        }

        return new GenerateResult(sessionCount, seatCount, tierCount, 0);
    }

    /** 按比例预售演示座位，并同步场次 sold_seat。 */
    private void presell(List<SessionSeat> seats, double soldRatio, Long sessionId) {
        if (soldRatio > 0) {
            for (SessionSeat seat : seats) {
                if (random.nextDouble() < soldRatio) {
                    seat.setStatus(SessionSeat.STATUS_SOLD);
                }
            }
        }

        long sold = seats.stream().filter(s -> s.getStatus() == SessionSeat.STATUS_SOLD).count();
        seatFactory.insert(seats);

        if (sold > 0) {
            Session counter = new Session();
            counter.setId(sessionId);
            counter.setSoldSeat((int) sold);
            sessionMapper.updateById(counter);
        }
    }

    /** 生成结果中的场次及数量。 */
    private record WrittenSession(Session session, int seatCount, int tierCount) {}

    /** 写入场次、票档和座位。 */
    private WrittenSession writeSession(
            Film project,
            Hall place,
            LocalDate showDate,
            LocalDateTime startTime,
            double soldRatio) {
        List<PriceTier> tiers = buildTiers(project, place);

        // total_seat 必须与模板实际生成的座位数一致。
        List<SessionSeatFactory.SeatPosition> layout = seatFactory.layoutOf(place);

        Session session = buildSession(project, place, showDate, startTime, tiers, layout.size());
        sessionMapper.insert(session);

        for (PriceTier tier : tiers) {
            tier.setSessionId(session.getId());
            priceTierMapper.insert(tier);
        }

        List<SessionSeat> seats = seatFactory.buildSeats(session.getId(), tiers, layout);
        presell(seats, soldRatio, session.getId());

        return new WrittenSession(session, seats.size(), tiers.size());
    }

    /** 从游标开始选择与场地类型匹配的项目。 */
    private Film nextMatching(List<Film> projects, Hall place, int from) {
        boolean placeIsCinema = isCinemaPlace(place);
        for (int i = 0; i < projects.size(); i++) {
            Film candidate = projects.get((from + i) % projects.size());
            if (candidate.isPerformance() != placeIsCinema) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isCinemaPlace(Hall place) {
        String type = place.getPlaceType();
        return "IMAX".equals(type) || "3D".equals(type) || "NORMAL".equals(type);
    }

    /** 按场馆类型选择电影或演出时段。 */
    private LocalTime[] slotsFor(Hall place) {
        return isCinemaPlace(place) ? FILM_SLOTS : SHOW_SLOTS;
    }

    /** 按项目和场地生成票档；电影和站席各使用一个票档。 */
    private List<PriceTier> buildTiers(Film project, Hall place) {
        int rows = place.getRowCount() == null ? 10 : place.getRowCount();
        String placeType = place.getPlaceType() == null ? "NORMAL" : place.getPlaceType();

        int base =
                switch (placeType) {
                    case "ARENA" -> 380;
                    case "THEATER" -> 220;
                    case "STUDIO" -> 120;
                    case "STANDING" -> 180;
                    case "IMAX" -> 85;
                    case "3D" -> 55;
                    case "VIP" -> 120;
                    default -> 45;
                };

        List<PriceTier> tiers = new ArrayList<>();

        // 站席只有一个容量区。
        if (place.isStanding() || !project.isPerformance()) {
            String name = place.isStanding() ? "站席" : "标准";
            tiers.add(tier(name, money(base), 1, 0, "#ff6700"));
            return tiers;
        }

        int vipEnd = Math.max(1, rows / 4);
        int floorEnd = Math.max(vipEnd + 1, rows / 2);

        tiers.add(tier("VIP 内场", money(base * 1.8f), 1, vipEnd, "#e91e63"));
        tiers.add(tier("内场", money(base * 1.2f), vipEnd + 1, floorEnd, "#ff6700"));
        tiers.add(tier("看台", money(base * 0.7f), floorEnd + 1, 0, "#2196f3"));
        return tiers;
    }

    private PriceTier tier(String name, BigDecimal price, int rowStart, int rowEnd, String color) {
        PriceTier t = new PriceTier();
        t.setId(SnowflakeIdGenerator.next());
        t.setName(name);
        t.setPrice(price);
        t.setRowStart(rowStart);
        t.setRowEnd(rowEnd);
        t.setColor(color);
        return t;
    }

    private BigDecimal money(float yuan) {
        return BigDecimal.valueOf(Math.round(yuan)).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------
    // 场次
    // ------------------------------------------------------------

    private Session buildSession(
            Film project,
            Hall place,
            LocalDate showDate,
            LocalDateTime startTime,
            List<PriceTier> tiers,
            int totalSeat) {
        Session session = new Session();
        session.setSource(SOURCE_GENERATED);
        session.setProjectId(project.getId());
        session.setVenueId(place.getVenueId());
        session.setPlaceId(place.getId());
        session.setShowDate(showDate);
        session.setStartTime(startTime);
        session.setEndTime(
                startTime.plusMinutes(project.getDuration() == null ? 120 : project.getDuration()));

        // 列表页展示最低价，座位实际价格由票档决定。
        session.setPrice(
                tiers.stream()
                        .map(PriceTier::getPrice)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO));

        session.setTotalSeat(totalSeat);
        session.setLockedSeat(0);
        session.setSoldSeat(0);
        session.setStatus(Session.STATUS_ON_SALE);
        session.setRushMode(0);

        // 演出和电影使用不同的购票限制。
        if (project.isPerformance()) {
            session.setSaleStartTime(LocalDateTime.now().minusDays(1));
            session.setPurchaseLimit(4);
            session.setRequireRealName(1);
        } else {
            session.setPurchaseLimit(6);
            session.setRequireRealName(0);
        }
        return session;
    }
}
