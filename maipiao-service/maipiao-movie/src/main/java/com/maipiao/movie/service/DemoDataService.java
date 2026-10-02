package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * 生成演示用的场次、票档和座位行。
 *
 * <p>电影和演出走同一条路径，这正是统一模型的要点：
 *
 * <ul>
 *   <li><b>电影</b>场次只得到一个覆盖全部排的票档。下游没有任何一处为此分支 ——
 *       座位图照常读票档，只不过读到的只有这一个。</li>
 *   <li><b>演出</b>按排区间分若干票档，每档各有一个价，场馆实际上就是这么卖的。</li>
 *   <li><b>站席</b>场地压根没有网格。它的 bitmap 被用作入场人数计数器：
 *       seat_index 仍然标识一个容量单位，所以锁定、下单、退款都不需要特例。</li>
 * </ul>
 *
 * <p>整体不开事务。一个横跨十万次 insert 的事务会撑出巨大的 Undo Log，而且只要有
 * 一行坏掉就全盘失败；这里每个场次单独写入，并且因为生成器会先清空，重跑是安全的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private static final LocalTime[] FILM_SLOTS = {
            LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(16, 0),
            LocalTime.of(19, 0), LocalTime.of(21, 30),
    };

    /** 演出都在晚上；只留两个时段，好让演示数据集看得过来。 */
    private static final LocalTime[] SHOW_SLOTS = {
            LocalTime.of(19, 30), LocalTime.of(20, 30),
    };

    /** 标记本生成器创建的数据，这样它重置时可以放过其余的。 */
    private static final String SOURCE_GENERATED = "DEMO";

    /** 标记管理员创建的数据，生成器不能碰。 */
    private static final String SOURCE_ADMIN = "ADMIN";

    private static final int BATCH_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------
    // showcase：一个体育场、一位艺人、2000 个座位
    // ------------------------------------------------------------

    private static final long SHOWCASE_PLACE_ID = 3199L;
    private static final long SHOWCASE_PROJECT_ID = 1199L;
    private static final LocalTime SHOWCASE_SLOT = LocalTime.of(19, 30);
    private static final int SHOWCASE_NIGHTS = 2;

    /**
     * 票档直接写死，不从场馆推导。
     *
     * <p>{@link #buildTiers} 按 {@code place_type} 定价，对生成式数据集来说是对的 ——
     * 一条规则，1272 个场次，全都自洽。但在这里是错的：体育场 380 的基准价会把最好
     * 的座位推到 684，而 showcase 的要点是这些数字得像一场真有人会买票的演唱会。
     * 40 排分四档也是体育场演出的卖法，小一点的场馆才卖三档。
     */
    private static final List<PriceTier> SHOWCASE_TIERS = List.of(
            showcaseTier("内场VIP", 1980, 1, 8, "#e91e63"),
            showcaseTier("内场", 1280, 9, 20, "#ff6700"),
            showcaseTier("看台A", 880, 21, 32, "#2196f3"),
            showcaseTier("看台B", 580, 33, 40, "#4caf50"));

    private static PriceTier showcaseTier(String name, float price, int rowStart,
                                          int rowEnd, String color) {
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

    public record GenerateResult(int sessionCount, int seatCount, int tierCount, long elapsedMs) {
    }

    /**
     * 只清除本生成器造出来的东西，别的都不动。
     *
     * <p>它以前会清掉所有场次，在它是唯一的创建者时这是对的。现在管理员可以通过
     * 后台界面把一场演出上架，而一次重置会一声不吭地把它删掉 —— 录入的人根本没法
     * 判断是不是自己哪一步做错了。
     *
     * <p>座位和票档跟着场次走。它们是通过场次 id 找到的，而不是清空整张表，理由
     * 相同。
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearSchedules() {
        List<Session> generated = sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                .eq(Session::getSource, SOURCE_GENERATED));
        if (generated.isEmpty()) {
            log.info("no generated sessions to clear");
            return;
        }

        List<Long> ids = generated.stream().map(Session::getId).toList();
        sessionSeatMapper.delete(Wrappers.<SessionSeat>lambdaQuery()
                .in(SessionSeat::getSessionId, ids));
        priceTierMapper.delete(Wrappers.<PriceTier>lambdaQuery()
                .in(PriceTier::getSessionId, ids));
        sessionMapper.deleteByIds(ids);

        log.info("generated sessions cleared: count={}", ids.size());
    }

    /**
     * showcase：一个体育场、一位艺人、2000 个座位、两场。
     *
     * <p>它和 {@link #generate} 分开，而不是用个开关挂在上面，因为两者想要的东西
     * 正好相反。通用生成器先清空整个数据集，再从场馆自身的配置推导一切 —— 票档、
     * 座位数、售票窗口 —— 这正是 1272 个场次能廉价产出而又彼此自洽的原因。
     * showcase 则是一个必须恰好 2000 个座位、价格还得让人一眼认得的场次，所以它
     * 把数字写死，而不是推导出来。
     *
     * <p>只清除自己这个项目的场次，因此可以反复重跑而不打扰其余数据 —— 但它必须
     * 在 {@code generate-schedule} <b>之后</b>运行，那个会把一切都清掉。参见
     * {@code docs/sql/demo/03_showcase.sql}。
     */
    public GenerateResult generateShowcase() {
        long started = System.currentTimeMillis();

        Hall place = hallMapper.selectById(SHOWCASE_PLACE_ID);
        Film project = filmMapper.selectById(SHOWCASE_PROJECT_ID);
        if (place == null || project == null) {
            throw new IllegalStateException(
                    "showcase venue or project is missing; run docs/sql/demo/03_showcase.sql first");
        }

        // 只对本项目幂等。
        List<Session> existing = sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                .eq(Session::getProjectId, SHOWCASE_PROJECT_ID));
        for (Session stale : existing) {
            sessionSeatMapper.delete(Wrappers.<SessionSeat>lambdaQuery()
                    .eq(SessionSeat::getSessionId, stale.getId()));
            priceTierMapper.delete(Wrappers.<PriceTier>lambdaQuery()
                    .eq(PriceTier::getSessionId, stale.getId()));
            sessionMapper.deleteById(stale.getId());
        }

        List<SessionSeatFactory.SeatPosition> layout = seatFactory.layoutOf(place);
        int sessionCount = 0;
        int seatCount = 0;
        int tierCount = 0;

        LocalDate firstNight = project.getShowDate() == null
                ? LocalDate.now().plusDays(56) : project.getShowDate();
        LocalDateTime now = LocalDateTime.now();

        // 同一场演出演两晚，只有卖票方式不同。两场都有，差别才演示得出来：同样的
        // 座位、同样的票档，一场有排队、一场没有。演唱会一晚只演一场，所以它们落在
        // 不同的日期上，而不是同一天的不同时间。
        for (int i = 0; i < SHOWCASE_NIGHTS; i++) {
            LocalDate showDate = firstNight.plusDays(i);
            boolean rush = i == 0;

            Session session = buildShowcaseSession(project, place, showDate,
                    LocalDateTime.of(showDate, SHOWCASE_SLOT), layout.size(), rush, now);
            sessionMapper.insert(session);

            for (PriceTier tier : showcaseTiers()) {
                tier.setSessionId(session.getId());
                priceTierMapper.insert(tier);
            }
            tierCount += SHOWCASE_TIERS.size();

            // 一张都不预卖。showcase 的要点就是这 2000 张全都挂在售 —— 像通用
            // 生成器那样为了让座位图看着有人气而预卖四分之一，恰好把这件事抵消掉。
            List<SessionSeat> seats = seatFactory.buildSeats(session.getId(),
                    sessionTiers(session), layout);
            seatFactory.insert(seats);

            sessionCount++;
            seatCount += seats.size();
            log.info("showcase session: id={}, rush={}, seats={}, starts={}",
                    session.getId(), rush, seats.size(), session.getStartTime());
        }

        return new GenerateResult(sessionCount, seatCount, tierCount,
                System.currentTimeMillis() - started);
    }

    /**
     * 某一场实际写进去的票档。
     *
     * <p>{@link #showcaseTiers} 每次调用都新建，这样两场之间不共享行对象 —— 票档 id
     * 是插入时分配的，复用会让第二场的座位指向第一场的票档。
     */
    private List<PriceTier> sessionTiers(Session session) {
        return priceTierMapper.selectList(Wrappers.<PriceTier>lambdaQuery()
                .eq(PriceTier::getSessionId, session.getId())
                .orderByAsc(PriceTier::getRowStart));
    }

    private Session buildShowcaseSession(Film project, Hall place, LocalDate showDate,
                                         LocalDateTime startTime, int totalSeat,
                                         boolean rush, LocalDateTime now) {
        Session session = new Session();
        session.setProjectId(project.getId());
        session.setVenueId(place.getVenueId());
        session.setPlaceId(place.getId());
        session.setShowDate(showDate);
        session.setStartTime(startTime);
        session.setEndTime(startTime.plusMinutes(
                project.getDuration() == null ? 180 : project.getDuration()));

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
            // 还没开抢，这样队列是可以观察到的，而不是已经结束：要演示的正是等待
            // 室，而它需要有一段真的有人在里面的时间。
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

    /**
     * 构建演示数据集。
     *
     * <p>电影和演出分开生成，这个「分开」本身就是要点，而不是实现细节。以前一个
     * 循环依次走过日期、场地、时段，每个时段填上一个放进该场馆还合适的项目 ——
     * 这对电影院完全正确，对其他一切都不对。电影院把同一部片子一天放很多场、连放
     * 好几周，这才叫排片。演唱会则是提前公布、一晚在一个场馆演一场。
     *
     * <p>两者混在一起跑时，那个循环会给巡演的一站安排单日十二场、一周五十四场，
     * 还分布在六个不同的场馆。什么都没报错 —— 场次合法、座位也卖得出去，只是结果
     * 描述出来是一支乐队连着一周每晚跑六个场子。
     */
    public GenerateResult generate(int days, double soldRatio, boolean rushSchedule) {
        long started = System.currentTimeMillis();

        List<Hall> places = hallMapper.selectList(Wrappers.<Hall>lambdaQuery()
                .eq(Hall::getStatus, Hall.STATUS_ACTIVE));
        List<Film> projects = filmMapper.selectList(Wrappers.<Film>lambdaQuery()
                .in(Film::getStatus, Film.STATUS_UPCOMING, Film.STATUS_ON_SALE)
                .orderByAsc(Film::getId));

        if (places.isEmpty() || projects.isEmpty()) {
            throw new IllegalStateException(
                    "need places and projects before generating sessions; "
                            + "run docs/sql/schema/*.sql and docs/sql/demo/*.sql first");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        GenerateResult screenings = generateScreenings(places, projects, days, soldRatio, today, now);
        GenerateResult runs = generatePerformanceRuns(places, projects, days, soldRatio,
                today, now, rushSchedule);

        GenerateResult total = new GenerateResult(
                screenings.sessionCount() + runs.sessionCount(),
                screenings.seatCount() + runs.seatCount(),
                screenings.tierCount() + runs.tierCount(),
                System.currentTimeMillis() - started);

        log.info("demo sessions generated: screenings={}, performance runs={}, seats={}, "
                        + "tiers={}, elapsed={}ms",
                screenings.sessionCount(), runs.sessionCount(), total.seatCount(),
                total.tierCount(), total.elapsedMs());
        return total;
    }

    /**
     * 电影院的排片网格：每个影厅、一天里的大部分时段、每天都排。
     *
     * <p>给电影排片本来就是这件事，这部分没有改过。
     */
    private GenerateResult generateScreenings(List<Hall> places, List<Film> projects, int days,
                                              double soldRatio, LocalDate today,
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

                    WrittenSession written = writeSession(project, place, showDate, startTime,
                            soldRatio);
                    sessionCount++;
                    seatCount += written.seatCount();
                    tierCount += written.tierCount();
                }
            }
        }

        return new GenerateResult(sessionCount, seatCount, tierCount, 0);
    }

    /**
     * 公布出来的日期，不是网格。
     *
     * <p>一场演出有一个主场馆和少数几个日期。几个取决于场地：体育场或体育馆是巡演
     * 的一站，演一两晚；小剧场或 livehouse 则撑得起驻演，演好几场，而且散布在整个
     * 档期里而不是连着来 —— 两种场地实际都是这么卖的。
     *
     * <p>无论哪种，一晚都只演一场。演两场那是日场，是电影院和剧院的做法；一场演唱会
     * 一晚上演两遍不成立。
     */
    private GenerateResult generatePerformanceRuns(List<Hall> places, List<Film> projects,
                                                   int days, double soldRatio, LocalDate today,
                                                   LocalDateTime now, boolean rushSchedule) {
        List<Hall> performanceVenues = places.stream()
                .filter(place -> !isCinemaPlace(place))
                .toList();
        // 已经被人工排过期的项目不动。后台界面的存在就是为了说明一场演出什么时候
        // 演，而生成器再往同一个演出上加自己的日期，等于跟用过那个界面的人对着干 ——
        // 陈奕迅那场订在 12 月的演出，在演示数据一重跑的时候平白多出一对 9 月的日期。
        Set<Long> handScheduled = sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                        .eq(Session::getSource, SOURCE_ADMIN))
                .stream().map(Session::getProjectId).collect(java.util.stream.Collectors.toSet());

        List<Film> performances = projects.stream()
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

        // 一个场地同一时间只装得下一件事，数据库用 (place, start_time) 上的唯一键
        // 来强制这一点。演出数量多于场馆，必有两条落进同一个场地 —— 没有这段逻辑
        // 时，它们会落在同一个晚上、同一个钟点，插入在这一轮跑到一半就失败。把已
        // 占用的记下来、顺延到下一个空闲的晚上，就是修复的全部。
        Set<String> taken = new HashSet<>();
        for (Session existing : sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                .in(Session::getPlaceId, performanceVenues.stream().map(Hall::getId).toList()))) {
            taken.add(existing.getPlaceId() + "@" + existing.getStartTime());
        }

        for (int i = 0; i < performances.size(); i++) {
            Film project = performances.get(i);
            Hall venue = performanceVenues.get(i % performanceVenues.size());

            // 只能坐几百人的场子给驻演，能坐几千人的馆只给一晚，因为两者算下来
            // 的账实际就长这样。
            boolean bigRoom = "ARENA".equals(venue.getPlaceType());
            int nights = bigRoom ? (i % 2 == 0 ? 2 : 1) : 2 + (i % 3);

            // 把日期摊开。巡演是路过，不会在同一个场地连着一星期每晚都演。
            int gap = Math.max(1, (days - 1) / Math.max(1, nights));
            LocalTime slot = slotsFor(venue)[0];

            int placed = 0;
            int offset = 2 + (i % 3);
            // 一天一天往前找，直到这一轮凑够场次。边界就是这个窗口本身：看到窗口
            // 之外没有意义。
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

                WrittenSession written = writeSession(project, venue, showDate, startTime,
                        soldRatio);
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

    /**
     * 把一部分座位标成已售，让座位图看起来有人气。
     *
     * <p>这属于演示生成器而不属于座位工厂：真实场次一开始是空的，预卖是编造出来的
     * 数据才有的属性。计数器要跟着一起动，因为场次上的数字必须能描述座位行里的事实。
     */
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

    /** 写入一个场次的产出。id 仍用 long；Snowflake 塞不进 int。 */
    private record WrittenSession(Session session, int seatCount, int tierCount) {
    }

    /**
     * 写入一个场次，连同它的票档和座位。
     *
     * <p>两个生成器共用，因为抛开「怎么排出来的」这一层，排片和演出就是同一种东西：
     * 一个时间、一个场地、一组座位。调用方在意的差别是它挑的日期，而不是它写了
     * 哪些行。
     */
    private WrittenSession writeSession(Film project, Hall place, LocalDate showDate,
                                        LocalDateTime startTime, double soldRatio) {
        List<PriceTier> tiers = buildTiers(project, place);

        // 先算布局，因为它决定这个场次有多少个座位。改从场馆的 seat_count 取数，
        // 只要模板里声明了过道或坏座，它就会和实际写出的座位行对不上 —— 而
        // total_seat 正是防超卖判断所比较的那个值，必须一致。
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

    // ------------------------------------------------------------
    // 项目 / 场地 配对
    // ------------------------------------------------------------

    /**
     * 挑出下一个这个场地接得住的项目。
     *
     * <p>从游标处往后扫，而不是直接取下一个项目，这样每个项目仍然轮得到 —— 只不过
     * 轮到它的是真装得下它的场地。
     */
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

    /**
     * 某个场地的场次都排在什么时段。
     *
     * <p>决定这件事的是场馆类型，不是座位形式：影厅全天排，剧场和体育馆排在晚上。
     * 若拿 {@code standing} 当判据，就会给对号入座的剧场排上电影的时刻表、给站席的
     * 体育馆排上演出的时刻表 —— 答案碰巧对了，理由却不对，而且一旦加进一个对号
     * 入座的体育馆就立刻错。
     */
    private LocalTime[] slotsFor(Hall place) {
        return isCinemaPlace(place) ? FILM_SLOTS : SHOW_SLOTS;
    }

    // ------------------------------------------------------------
    // 定价
    // ------------------------------------------------------------

    /**
     * 一个场次的票档。
     *
     * <p>电影只拿到一个覆盖全部排的票档，这样座位图和下单流程都不必按类型分支 ——
     * 它们读票档，而电影恰好只有一个可读。
     */
    private List<PriceTier> buildTiers(Film project, Hall place) {
        int rows = place.getRowCount() == null ? 10 : place.getRowCount();
        String placeType = place.getPlaceType() == null ? "NORMAL" : place.getPlaceType();

        int base = switch (placeType) {
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

        // 站席区按构造就只有一排，所以它自然收成单个票档，不需要特例。
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

    private Session buildSession(Film project, Hall place, LocalDate showDate,
                                  LocalDateTime startTime, List<PriceTier> tiers,
                                  int totalSeat) {
        Session session = new Session();
        session.setSource(SOURCE_GENERATED);
        session.setProjectId(project.getId());
        session.setVenueId(place.getVenueId());
        session.setPlaceId(place.getId());
        session.setShowDate(showDate);
        session.setStartTime(startTime);
        session.setEndTime(startTime.plusMinutes(project.getDuration() == null ? 120 : project.getDuration()));

        // 列表页只显示一个数字；一个座位实际花多少钱由它的票档决定。
        session.setPrice(tiers.stream().map(PriceTier::getPrice)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));

        session.setTotalSeat(totalSeat);
        session.setLockedSeat(0);
        session.setSoldSeat(0);
        session.setStatus(Session.STATUS_ON_SALE);
        session.setRushMode(0);

        // 入场规则因类型而异，默认值保持电影的行为不变，而不是让每个下游都去判断
        // 一次类型。
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
