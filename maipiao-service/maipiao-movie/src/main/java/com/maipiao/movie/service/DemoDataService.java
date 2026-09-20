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
 * Generates demo sessions, their price tiers and their seat rows.
 *
 * <p>Handles films and performances through one path, which is the point of
 * the unified model:
 *
 * <ul>
 *   <li>A <b>film</b> session gets one tier covering every row. Nothing
 *       downstream branches on that - the seat map reads a tier like any
 *       other, it is simply the only one.</li>
 *   <li>A <b>performance</b> gets several tiers by row band, each with its own
 *       price, which is how venues actually sell them.</li>
 *   <li>A <b>standing</b> place gets no grid at all. Its bitmap is used as an
 *       admission counter: seat_index still identifies one unit of capacity,
 *       so locking, ordering and refunds need no special case.</li>
 * </ul>
 *
 * <p>Not transactional as a whole. One transaction spanning a hundred thousand
 * inserts holds an enormous undo log and fails entirely on a single bad row;
 * each session is written on its own, and re-running is safe because the
 * generator clears first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private static final LocalTime[] FILM_SLOTS = {
            LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(16, 0),
            LocalTime.of(19, 0), LocalTime.of(21, 30),
    };

    /** Performances run at night; two slots keeps the demo dataset readable. */
    private static final LocalTime[] SHOW_SLOTS = {
            LocalTime.of(19, 30), LocalTime.of(20, 30),
    };

    /** Marks what this generator created, so its reset can leave the rest alone. */
    private static final String SOURCE_GENERATED = "DEMO";

    /** Marks what an administrator created, which the generator must not touch. */
    private static final String SOURCE_ADMIN = "ADMIN";

    private static final int BATCH_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------
    // showcase: one stadium, one artist, 2000 seats
    // ------------------------------------------------------------

    private static final long SHOWCASE_PLACE_ID = 3199L;
    private static final long SHOWCASE_PROJECT_ID = 1199L;
    private static final LocalTime SHOWCASE_SLOT = LocalTime.of(19, 30);
    private static final int SHOWCASE_NIGHTS = 2;

    /**
     * Price bands stated outright rather than derived from the hall.
     *
     * <p>{@link #buildTiers} prices off {@code place_type}, which is right for
     * the generated dataset - one rule, 1272 sessions, all consistent. It is
     * wrong here: a stadium's base of 380 would put the best seat at 684, and
     * the point of the showcase is that the numbers look like a concert
     * somebody could actually buy a ticket to. Four bands across 40 rows is
     * also what a stadium show sells, where a smaller hall sells three.
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
     * Clears what this generator made, and nothing else.
     *
     * <p>It used to clear every session there was, which was correct while it
     * was the only thing creating them. An administrator can now put a show on
     * sale through the admin screen, and a reset would delete it without a
     * word - the person who entered it would have no way to tell whether they
     * had done something wrong.
     *
     * <p>Seats and bands go with their session. They are found through the
     * session ids rather than by clearing the tables, for the same reason.
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
     * The showcase: one stadium, one artist, 2000 seats, two screenings.
     *
     * <p>Separate from {@link #generate} rather than a flag on it, because the
     * two want opposite things. The general generator clears the whole
     * dataset first and derives everything - price bands, seat counts, sale
     * windows - from the venue's own configuration, which is what makes 1272
     * sessions cheap to produce and consistent with each other. The showcase
     * is one session that has to be exactly 2000 seats at prices somebody
     * would recognise, so it states its numbers instead of deriving them.
     *
     * <p>Only its own project's sessions are cleared, so it can be re-run
     * without disturbing the rest - but it must run <b>after</b>
     * {@code generate-schedule}, which clears everything. See
     * {@code docs/sql/11_seed_showcase.sql}.
     */
    public GenerateResult generateShowcase() {
        long started = System.currentTimeMillis();

        Hall place = hallMapper.selectById(SHOWCASE_PLACE_ID);
        Film project = filmMapper.selectById(SHOWCASE_PROJECT_ID);
        if (place == null || project == null) {
            throw new IllegalStateException(
                    "showcase venue or project is missing; run docs/sql/11_seed_showcase.sql first");
        }

        // Idempotent for this project alone.
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

        // Two nights of the same show, differing only in how the tickets are
        // sold. Having both is what makes the difference demonstrable: the same
        // seats, the same bands, one with a queue and one without. A concert
        // plays one show a night, so they are on separate dates rather than
        // separate times.
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

            // soldRatio 0: the whole point of the showcase is that all 2000 are
            // on sale. Pre-selling a quarter of them, as the general generator
            // does to make seat maps look lived-in, would undercut it.
            // soldRatio 0: the whole point of the showcase is that all 2000 are
            // on sale, so nothing is pre-sold.
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
     * The tiers actually written for a screening.
     *
     * <p>{@link #showcaseTiers} builds them fresh each call so the two
     * screenings do not share row objects - the tier ids are assigned by the
     * insert, and reusing them would make the second screening's seats point
     * at the first screening's bands.
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

        // 1 = the system assigns, which is the whole point of the showcase: a
        // stadium concert does not let 2000 people pick seats out of a map.
        session.setSeatMode(1);
        session.setSource(SOURCE_GENERATED);
        session.setSaleStartTime(now.minusMinutes(1));
        session.setPurchaseLimit(rush ? 2 : 4);
        session.setRequireRealName(1);

        if (rush) {
            // Not open yet, so the queue is observable rather than already
            // over: the demo is the waiting room, and it needs a moment where
            // people are in it.
            session.setRushMode(1);
            session.setRushStartTime(now.plusMinutes(5));
        } else {
            session.setRushMode(0);
        }
        return session;
    }

    /** Four bands, priced as a stadium concert is rather than derived from the hall. */
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
     * Builds the demo dataset.
     *
     * <p>Films and performances are generated separately, and that separation
     * is the point rather than an implementation detail. One loop used to walk
     * days, then places, then slots, and fill each slot with the next project
     * that fitted the venue - which is exactly right for a cinema and wrong
     * for everything else. A cinema runs the same film many times a day for
     * weeks; that is what a screening is. A concert plays one night at one
     * venue, announced in advance.
     *
     * <p>Run together, the loop gave a tour stop twelve screenings on a single
     * day and fifty-four across a week, at six different venues. Nothing
     * errored - the sessions were valid, the seats were sold, and the result
     * described a band playing six venues a night for a week.
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
                            + "run docs/sql/05_seed_base.sql and 08_seed_event.sql first");
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
     * The cinema grid: every hall, most slots of the day, every day.
     *
     * <p>This is what scheduling a film actually is, and it is unchanged.
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
     * Announced dates, not a grid.
     *
     * <p>A performance gets a home venue and a handful of dates. How many
     * depends on the room: a stadium or arena is a tour stop and plays one or
     * two nights, while a small theatre or club can hold a residency and play
     * several, spread across the run rather than back to back - which is how
     * both are actually sold.
     *
     * <p>One show a night in every case. Two would be a matinee, which is a
     * cinema and theatre thing; a concert that plays twice in an evening is
     * not a thing.
     */
    private GenerateResult generatePerformanceRuns(List<Hall> places, List<Film> projects,
                                                   int days, double soldRatio, LocalDate today,
                                                   LocalDateTime now, boolean rushSchedule) {
        List<Hall> performanceVenues = places.stream()
                .filter(place -> !isCinemaPlace(place))
                .toList();
        // Projects somebody has already scheduled by hand are left alone. The
        // admin screen exists to say when a show is on, and a generator that
        // then adds its own dates to the same show contradicts whoever used it
        // - the 陈奕迅 show booked for December grew an extra pair of
        // September dates the moment the demo data was regenerated.
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

        // A room holds one thing at a time, which the database enforces with a
        // unique key on (place, start_time). Performances outnumber venues, so
        // two of them land on the same room - and without this they landed on
        // the same evening, at the same hour, and the insert failed halfway
        // through the run. Tracking what is taken and moving to the next free
        // evening is the whole of the fix.
        Set<String> taken = new HashSet<>();
        for (Session existing : sessionMapper.selectList(Wrappers.<Session>lambdaQuery()
                .in(Session::getPlaceId, performanceVenues.stream().map(Hall::getId).toList()))) {
            taken.add(existing.getPlaceId() + "@" + existing.getStartTime());
        }

        for (int i = 0; i < performances.size(); i++) {
            Film project = performances.get(i);
            Hall venue = performanceVenues.get(i % performanceVenues.size());

            // A room that only seats a few hundred gets a residency; a hall
            // that seats thousands gets a night, because that is what the
            // economics of each actually look like.
            boolean bigRoom = "ARENA".equals(venue.getPlaceType());
            int nights = bigRoom ? (i % 2 == 0 ? 2 : 1) : 2 + (i % 3);

            // Spread the dates out. A tour passes through; it does not play
            // the same room on consecutive evenings for a week.
            int gap = Math.max(1, (days - 1) / Math.max(1, nights));
            LocalTime slot = slotsFor(venue)[0];

            int placed = 0;
            int offset = 2 + (i % 3);
            // Walk forward a day at a time until this run has its nights. The
            // bound is the window itself: there is no point looking past it.
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
     * Marks a share of the seats sold, so seat maps look lived-in.
     *
     * <p>Belongs to the demo generator rather than the seat factory: a real
     * session starts empty, and pre-selling is a property of made-up data. The
     * counter moves with it, because the number on the session has to describe
     * the rows on the seats.
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

    /** What writing one session produced. Ids stay longs; snowflakes do not fit in an int. */
    private record WrittenSession(Session session, int seatCount, int tierCount) {
    }

    /**
     * Writes one session with its bands and its seats.
     *
     * <p>Shared by both generators because a screening and a performance are
     * the same kind of thing once you get past how they got scheduled: a time,
     * a place, a set of seats. The difference the caller cares about is the
     * dates it chooses, not the rows it writes.
     */
    private WrittenSession writeSession(Film project, Hall place, LocalDate showDate,
                                        LocalDateTime startTime, double soldRatio) {
        List<PriceTier> tiers = buildTiers(project, place);

        // The layout comes first because it decides how many seats the session
        // has. Taking the count from the hall's seat_count instead would
        // disagree with the rows actually written the moment a template
        // declares aisles or broken seats - and total_seat is what the
        // anti-oversell guard compares against, so it has to match.
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
    // project / place pairing
    // ------------------------------------------------------------

    /**
     * Picks the next project of the kind this place can host.
     *
     * <p>Scans forward from the cursor rather than taking the next project
     * outright, so every project still gets a turn - it just gets its turn at
     * a place that can actually hold it.
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
     * When sessions run at a given place.
     *
     * <p>What decides this is the venue kind, not the seating mode: a cinema
     * screen runs all day, a theatre or arena runs in the evening. Keying it
     * off {@code standing} would have given seated theatres film schedules and
     * standing arenas show schedules - right answer for the wrong reason, and
     * wrong the moment a seated arena is added.
     */
    private LocalTime[] slotsFor(Hall place) {
        return isCinemaPlace(place) ? FILM_SLOTS : SHOW_SLOTS;
    }

    // ------------------------------------------------------------
    // pricing
    // ------------------------------------------------------------

    /**
     * Price bands for a session.
     *
     * <p>A film gets a single band covering every row, so the seat map and the
     * order flow never branch on category - they read a tier, and for a film
     * there is exactly one to read.
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

        // Standing areas are one row by construction, so they collapse to a
        // single band without a special case.
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
    // sessions
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

        // The listing shows one number; what a seat actually costs comes from
        // its tier.
        session.setPrice(tiers.stream().map(PriceTier::getPrice)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));

        session.setTotalSeat(totalSeat);
        session.setLockedSeat(0);
        session.setSoldSeat(0);
        session.setStatus(Session.STATUS_ON_SALE);
        session.setRushMode(0);

        // Admission controls differ by kind, and the defaults leave film
        // behaviour unchanged rather than making every consumer check.
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
