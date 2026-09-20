package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.List;
import java.util.Random;

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

    private static final int BATCH_SIZE = 1000;

    private final FilmMapper filmMapper;
    private final HallMapper hallMapper;
    private final SessionMapper sessionMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final PriceTierMapper priceTierMapper;

    private final Random random = new Random(20260920L);

    public record GenerateResult(int sessionCount, int seatCount, int tierCount, long elapsedMs) {
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearSchedules() {
        sessionMapper.delete(Wrappers.<Session>lambdaQuery());
        sessionSeatMapper.delete(Wrappers.<SessionSeat>lambdaQuery());
        priceTierMapper.delete(Wrappers.<PriceTier>lambdaQuery());
        log.info("demo sessions cleared");
    }

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

        int sessionCount = 0;
        int seatCount = 0;
        int tierCount = 0;
        int cursor = 0;
        Session rushTarget = null;

        for (int dayOffset = 0; dayOffset < days; dayOffset++) {
            LocalDate showDate = today.plusDays(dayOffset);

            for (Hall place : places) {
                for (LocalTime slot : slotsFor(place)) {
                    LocalDateTime startTime = LocalDateTime.of(showDate, slot);
                    if (!startTime.isAfter(now)) {
                        continue;
                    }

                    // Pair the project to the place by kind: a film plays in a
                    // cinema, a performance does not. Without this the
                    // generator books a stand-up set into a cinema screen.
                    Film project = nextMatching(projects, place, cursor++);
                    if (project == null) {
                        continue;
                    }

                    List<PriceTier> tiers = buildTiers(project, place);

                    Session session = buildSession(project, place, showDate, startTime, tiers);
                    sessionMapper.insert(session);
                    for (PriceTier tier : tiers) {
                        tier.setSessionId(session.getId());
                        priceTierMapper.insert(tier);
                    }
                    tierCount += tiers.size();

                    List<SessionSeat> seats = buildSeats(session.getId(), place, tiers,
                            place.getSeatCount(), soldRatio);
                    batchInsert(seats);

                    sessionCount++;
                    seatCount += seats.size();
                    rushTarget = session;
                }
            }
        }

        if (rushSchedule && rushTarget != null) {
            rushTarget.setRushMode(1);
            rushTarget.setRushStartTime(LocalDateTime.now().plusMinutes(2));
            sessionMapper.updateById(rushTarget);
            log.info("rush session marked: sessionId={}", rushTarget.getId());
        }

        long elapsed = System.currentTimeMillis() - started;
        log.info("demo sessions generated: sessions={}, seats={}, tiers={}, elapsed={}ms",
                sessionCount, seatCount, tierCount, elapsed);

        return new GenerateResult(sessionCount, seatCount, tierCount, elapsed);
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
                                  LocalDateTime startTime, List<PriceTier> tiers) {
        Session session = new Session();
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

        session.setTotalSeat(place.getSeatCount());
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

    /**
     * Seat rows for one session.
     *
     * <p>Each seat is assigned to exactly one tier, once, here. That assignment
     * is what the seat map colours by and what the order prices by, so it is
     * resolved at generation time rather than derived per request.
     */
    private List<SessionSeat> buildSeats(Long sessionId, Hall place, List<PriceTier> tiers,
                                          int capacity, double soldRatio) {
        List<SessionSeat> seats = new ArrayList<>(capacity);
        boolean standing = place.isStanding();
        int cols = Math.max(1, place.getColCount());
        // Aisles take seats out of every row, so the row a given index lands on
        // is not index/cols. Two aisles per hall in this dataset.
        int usableCols = standing ? 1 : Math.max(1, cols - 2);

        for (int index = 0; index < capacity; index++) {
            int row = standing ? 1 : Math.min(place.getRowCount(), (index / usableCols) + 1);
            int col = standing ? index + 1 : (index % usableCols) + 1;

            SessionSeat seat = new SessionSeat();
            seat.setId(SnowflakeIdGenerator.next());
            seat.setSessionId(sessionId);
            seat.setSeatIndex(index);
            seat.setRowNum(row);
            seat.setColNum(col);
            seat.setSeatId(row + "_" + col);
            seat.setSeatType(0);
            seat.setTierId(tierForRow(tiers, row));
            seat.setStatus(soldRatio > 0 && random.nextDouble() < soldRatio
                    ? SessionSeat.STATUS_SOLD : SessionSeat.STATUS_AVAILABLE);
            seat.setVersion(0);
            seats.add(seat);
        }

        long sold = seats.stream().filter(s -> s.getStatus() == SessionSeat.STATUS_SOLD).count();
        Session counter = new Session();
        counter.setId(sessionId);
        counter.setSoldSeat((int) sold);
        sessionMapper.updateById(counter);

        return seats;
    }

    private Long tierForRow(List<PriceTier> tiers, int row) {
        for (PriceTier tier : tiers) {
            if (tier.covers(row)) {
                return tier.getId();
            }
        }
        return tiers.isEmpty() ? null : tiers.get(tiers.size() - 1).getId();
    }

    private void batchInsert(List<SessionSeat> seats) {
        for (int from = 0; from < seats.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, seats.size());
            sessionSeatMapper.batchInsert(seats.subList(from, to));
        }
    }
}
