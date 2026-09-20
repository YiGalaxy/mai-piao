package com.maipiao.movie.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.movie.dto.SeatTemplate;
import com.maipiao.movie.entity.Film;
import com.maipiao.movie.entity.Hall;
import com.maipiao.movie.entity.Schedule;
import com.maipiao.movie.entity.ScheduleSeat;
import com.maipiao.movie.mapper.FilmMapper;
import com.maipiao.movie.mapper.HallMapper;
import com.maipiao.movie.mapper.ScheduleMapper;
import com.maipiao.movie.mapper.ScheduleSeatMapper;
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
 * Generates the demo schedule data.
 *
 * <p>Why this is code rather than SQL: roughly 1400 screenings times ~120
 * seats each is about 168,000 rows. Hand-writing that is not viable, and
 * neither is putting it in a seed script that has to re-run on every fresh
 * checkout.
 *
 * <p>Everything it writes is derived from the seed catalogue (24 films,
 * 8 cinemas, 40 halls) and the hall seat templates, so the same input always
 * produces the same shape of data.
 *
 * <p>Not transactional as a whole on purpose. A single transaction spanning
 * 168k inserts would hold an enormous undo log and, more practically, would
 * fail entirely on any single bad row. Each screening is written on its own,
 * and re-running the generator is safe because it clears first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    /** Start times offered each day, per hall. */
    private static final LocalTime[] TIME_SLOTS = {
            LocalTime.of(10, 0),
            LocalTime.of(13, 0),
            LocalTime.of(16, 0),
            LocalTime.of(19, 0),
            LocalTime.of(21, 30),
    };

    private static final int BATCH_SIZE = 1000;

    private final FilmMapper filmMapper;
    private final HallMapper hallMapper;
    private final ScheduleMapper scheduleMapper;
    private final ScheduleSeatMapper scheduleSeatMapper;
    private final SeatTemplateParser seatTemplateParser;

    private final Random random = new Random(20260920L); // fixed seed: reproducible demo data

    public record GenerateResult(int scheduleCount, int seatCount, long elapsedMs) {
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearSchedules() {
        scheduleMapper.delete(Wrappers.<Schedule>lambdaQuery());
        scheduleSeatMapper.delete(Wrappers.<ScheduleSeat>lambdaQuery());
        log.info("demo schedules cleared");
    }

    /**
     * @param days         how many days ahead to generate, starting today
     * @param soldRatio    share of seats to pre-mark as sold, so the seat map
     *                     does not look uniformly empty
     * @param rushSchedule whether to mark one screening as a rush sale
     */
    public GenerateResult generate(int days, double soldRatio, boolean rushSchedule) {
        long started = System.currentTimeMillis();

        List<Hall> halls = hallMapper.selectList(Wrappers.<Hall>lambdaQuery()
                .eq(Hall::getStatus, Hall.STATUS_ACTIVE));
        List<Film> films = filmMapper.selectList(Wrappers.<Film>lambdaQuery()
                .in(Film::getStatus, Film.STATUS_UPCOMING, Film.STATUS_NOW_SHOWING)
                .orderByAsc(Film::getId));

        if (halls.isEmpty() || films.isEmpty()) {
            throw new IllegalStateException(
                    "need halls and films before generating schedules; "
                            + "run docs/sql/05_seed_base.sql first");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        int scheduleCount = 0;
        int seatCount = 0;
        int filmCursor = 0;
        Schedule rushTarget = null;

        for (int dayOffset = 0; dayOffset < days; dayOffset++) {
            LocalDate showDate = today.plusDays(dayOffset);

            for (Hall hall : halls) {
                SeatTemplate template = seatTemplateParser.parse(hall.getSeatTemplate());
                List<SeatTemplateParser.SeatSpec> seatSpecs = seatTemplateParser.expand(template);
                seatTemplateParser.verifyAgainstHall(template, seatSpecs, hall.getSeatCount());

                for (LocalTime slot : TIME_SLOTS) {
                    LocalDateTime startTime = LocalDateTime.of(showDate, slot);

                    // Skip screenings that have already begun - generating a
                    // bookable screening in the past would produce orders that
                    // can never be fulfilled.
                    if (!startTime.isAfter(now)) {
                        continue;
                    }

                    // Round-robin the catalogue so every film gets screenings
                    // rather than the first one taking every slot.
                    Film film = films.get(filmCursor++ % films.size());

                    Schedule schedule = buildSchedule(film, hall, showDate, startTime);
                    scheduleMapper.insert(schedule);

                    List<ScheduleSeat> seats = buildSeats(schedule.getId(), seatSpecs, soldRatio);
                    batchInsert(seats);

                    scheduleCount++;
                    seatCount += seats.size();
                    rushTarget = schedule;
                }
            }
        }

        if (rushSchedule && rushTarget != null) {
            // Exactly one rush screening, so the queue path has something to
            // exercise without every screening needing an admission token.
            rushTarget.setRushMode(1);
            rushTarget.setRushStartTime(LocalDateTime.now().plusMinutes(2));
            scheduleMapper.updateById(rushTarget);
            log.info("rush screening marked: scheduleId={}", rushTarget.getId());
        }

        long elapsed = System.currentTimeMillis() - started;
        log.info("demo schedules generated: screenings={}, seats={}, elapsed={}ms",
                scheduleCount, seatCount, elapsed);

        return new GenerateResult(scheduleCount, seatCount, elapsed);
    }

    // ------------------------------------------------------------

    private Schedule buildSchedule(Film film, Hall hall, LocalDate showDate, LocalDateTime startTime) {
        Schedule schedule = new Schedule();
        schedule.setFilmId(film.getId());
        schedule.setCinemaId(hall.getCinemaId());
        schedule.setHallId(hall.getId());
        schedule.setShowDate(showDate);
        schedule.setStartTime(startTime);
        schedule.setEndTime(startTime.plusMinutes(film.getDuration() == null ? 120 : film.getDuration()));
        schedule.setPrice(priceFor(hall.getHallType(), startTime.toLocalTime(), film));
        schedule.setTotalSeat(hall.getSeatCount());
        schedule.setLockedSeat(0);
        schedule.setSoldSeat(0);
        schedule.setStatus(Schedule.STATUS_ON_SALE);
        schedule.setRushMode(0);
        return schedule;
    }

    /**
     * Base price by hall type, with a bump for evening screenings.
     *
     * <p>Kept deliberately simple: the point is that different screenings have
     * different prices, so the order and refund paths are exercised with
     * amounts that do not all look alike.
     */
    private BigDecimal priceFor(String hallType, LocalTime startTime, Film film) {
        int base = switch (hallType == null ? "NORMAL" : hallType) {
            case "IMAX" -> 85;
            case "3D" -> 55;
            case "VIP" -> 120;
            default -> 45;
        };

        // Evening and late screenings cost more; early ones less.
        int hour = startTime.getHour();
        if (hour >= 19) {
            base += 10;
        } else if (hour < 12) {
            base -= 5;
        }

        // Popular films (score >= 8) carry a small premium.
        if (film.getScore() != null && film.getScore().compareTo(new BigDecimal("8.0")) >= 0) {
            base += 5;
        }

        return BigDecimal.valueOf(Math.max(20, base)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Builds the seat rows for one screening.
     *
     * <p>The random "already sold" seats are the same ones the Redis bitmap is
     * rebuilt from later, so the seat map a user sees matches the ledger from
     * the first request rather than only after a reconciliation pass.
     */
    private List<ScheduleSeat> buildSeats(Long scheduleId,
                                          List<SeatTemplateParser.SeatSpec> specs,
                                          double soldRatio) {
        List<ScheduleSeat> seats = new ArrayList<>(specs.size());

        for (SeatTemplateParser.SeatSpec spec : specs) {
            ScheduleSeat seat = new ScheduleSeat();
            seat.setId(SnowflakeIdGenerator.next());
            seat.setScheduleId(scheduleId);
            seat.setSeatId(spec.seatId());
            seat.setSeatIndex(spec.seatIndex());
            seat.setRowNum(spec.row());
            seat.setColNum(spec.col());
            seat.setSeatType(spec.seatType());

            boolean preSold = soldRatio > 0 && random.nextDouble() < soldRatio;
            seat.setStatus(preSold ? ScheduleSeat.STATUS_SOLD : ScheduleSeat.STATUS_AVAILABLE);
            seat.setVersion(0);
            seats.add(seat);
        }

        // Keep the counters consistent with the rows, otherwise the first
        // seat-map rebuild would immediately look like a data corruption.
        long sold = seats.stream().filter(s -> s.getStatus() == ScheduleSeat.STATUS_SOLD).count();
        Schedule counter = new Schedule();
        counter.setId(scheduleId);
        counter.setSoldSeat((int) sold);
        scheduleMapper.updateById(counter);

        return seats;
    }

    private void batchInsert(List<ScheduleSeat> seats) {
        for (int from = 0; from < seats.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, seats.size());
            scheduleSeatMapper.batchInsert(seats.subList(from, to));
        }
    }
}
