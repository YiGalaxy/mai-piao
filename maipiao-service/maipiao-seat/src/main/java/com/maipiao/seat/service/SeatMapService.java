package com.maipiao.seat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.seat.dto.SeatDtos;
import com.maipiao.seat.dto.SeatMapVO;
import com.maipiao.seat.mapper.SeatQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seat map assembly and locking.
 *
 * <p>Two sources are combined: the layout (which seats exist, and where) comes
 * from the ledger; availability comes from the Redis bitmap.
 *
 * <p>On the lock token: the value returned by a successful lock is a snowflake
 * id, and it is also the order number the order service will use. That is a
 * deliberate simplification - the alternative is for order-service to mint its
 * own number and then ask seat-service to transfer the hold from the token to
 * the order, which is an extra round trip and an extra failure mode for no
 * benefit at this scale. If the two ever needed to differ, the transfer is the
 * change to make.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatMapService {

    private final SeatQueryMapper seatQueryMapper;
    private final SeatBitmapService seatBitmapService;
    private final ObjectMapper objectMapper;

    @Value("${maipiao.seat.lock-minutes:15}")
    private int lockMinutes;

    @Value("${maipiao.seat.rebuild-on-miss:true}")
    private boolean rebuildOnMiss;

    // ------------------------------------------------------------
    // seat map
    // ------------------------------------------------------------

    public SeatMapVO getSeatMap(Long sessionId) {
        Map<String, Object> schedule = seatQueryMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        int totalSeat = toInt(schedule.get("totalSeat"));

        Set<Integer> occupied = new HashSet<>(loadOccupiedIndexes(sessionId, totalSeat));

        List<Map<String, Object>> layout = seatQueryMapper.selectSeatLayout(sessionId);
        List<SeatMapVO.SeatItem> seats = new ArrayList<>(layout.size());

        for (Map<String, Object> row : layout) {
            int seatIndex = toInt(row.get("seatIndex"));
            seats.add(new SeatMapVO.SeatItem(
                    String.valueOf(row.get("seatId")),
                    seatIndex,
                    toInt(row.get("rowNum")),
                    toInt(row.get("colNum")),
                    toInt(row.get("seatType")),
                    occupied.contains(seatIndex) ? 1 : 0,
                    toLong(row.get("tierId"))));
        }

        SeatMapVO vo = new SeatMapVO();
        vo.setScheduleId(sessionId);
        vo.setProjectTitle(str(schedule.get("projectTitle")));
        vo.setVenueName(str(schedule.get("venueName")));
        vo.setPlaceName(str(schedule.get("placeName")));
        vo.setPlaceType(str(schedule.get("placeType")));
        vo.setStartTime((LocalDateTime) schedule.get("startTime"));
        vo.setPrice((BigDecimal) schedule.get("price"));
        vo.setRowCount(toInt(schedule.get("rowCount")));
        vo.setColCount(toInt(schedule.get("colCount")));
        vo.setAisleCols(parseAisleCols(str(schedule.get("seatTemplate"))));
        vo.setSeats(seats);
        vo.setTiers(loadTiers(sessionId));
        vo.setTotalSeat(totalSeat);
        vo.setRemainingSeat(Math.max(0, totalSeat - occupied.size()));
        vo.setRushMode(toInt(schedule.get("rushMode")));
        vo.setSeatingMode(str(schedule.get("seatingMode")));
        vo.setPurchaseLimit(toInt(schedule.get("purchaseLimit")));
        vo.setRequireRealName(toInt(schedule.get("requireRealName")));
        vo.setSaleStartTime((LocalDateTime) schedule.get("saleStartTime"));
        return vo;
    }

    /**
     * Price bands for the session.
     *
     * <p>A film has exactly one covering every seat; a performance has several
     * and the map is coloured by them. Sending the list rather than a price
     * per seat keeps the payload small - hundreds of seats sharing three bands
     * would otherwise repeat the same three values hundreds of times.
     */
    private List<SeatMapVO.TierItem> loadTiers(Long sessionId) {
        List<Map<String, Object>> rows = seatQueryMapper.selectTiers(sessionId);
        List<SeatMapVO.TierItem> tiers = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            tiers.add(new SeatMapVO.TierItem(
                    toLong(row.get("id")),
                    str(row.get("name")),
                    (BigDecimal) row.get("price"),
                    str(row.get("color"))));
        }
        return tiers;
    }

    /**
     * Availability, rebuilding the bitmap from the ledger when it is missing.
     *
     * <p>A cold bitmap is not an error state - it is what a freshly deployed
     * instance, a flushed Redis, or a screening nobody has opened yet looks
     * like. Rebuilding on first read means there is no separate warm-up step
     * to forget, and no window where a screening shows as entirely free.
     */
    private List<Integer> loadOccupiedIndexes(Long sessionId, int totalSeat) {
        if (seatBitmapService.isInitialised(sessionId)) {
            return seatBitmapService.findOccupiedIndexes(sessionId, totalSeat);
        }

        if (!rebuildOnMiss) {
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE, "座位数据尚未就绪");
        }

        return rebuildFromLedger(sessionId, totalSeat);
    }

    /**
     * Rebuilds the bitmap and the owner markers from
     * {@code t_event_session_seat}.
     *
     * <p>The owner markers matter as much as the bits, and for both kinds of
     * occupancy. The release script refuses to clear a seat whose owner does
     * not match the order asking, so a rebuilt seat rebuilt without its owner
     * can never be freed by anyone - the bit stays set and the seat is dead.
     * That applies to held seats as much as sold ones, and a held seat is the
     * more common case: on a screening that has been open a while there are
     * usually more unpaid holds than sales.
     *
     * <p>Sold seats are marked {@code SOLD:} so the release path can refuse to
     * put a paid-for seat back on sale; held seats carry their order number
     * plainly, which is what makes them releasable again.
     */
    private List<Integer> rebuildFromLedger(Long sessionId, int totalSeat) {
        List<Map<String, Object>> rows = seatQueryMapper.selectOccupiedSeats(sessionId);

        List<Integer> indexes = new ArrayList<>(rows.size());
        Map<String, String> owners = new HashMap<>();

        for (Map<String, Object> row : rows) {
            int index = toInt(row.get("seatIndex"));
            indexes.add(index);

            String orderNo = str(row.get("orderNo"));
            if (orderNo.isEmpty()) {
                // Occupied with nobody named. Should not happen - the ledger
                // always records one or the other - but leaving the seat
                // unowned is no worse than guessing, and it stays visible.
                log.warn("occupied seat has no order in the ledger: schedule={}, index={}",
                        sessionId, index);
                continue;
            }

            // status 2 = sold; status 1 = locked by an unpaid order
            owners.put(String.valueOf(index),
                    toInt(row.get("status")) == 2 ? "SOLD:" + orderNo : orderNo);
        }

        seatBitmapService.rebuild(sessionId, indexes);
        seatBitmapService.markOwners(sessionId, owners);

        log.info("seat bitmap rebuilt from ledger: schedule={}, total={}, occupied={}, owned={}",
                sessionId, totalSeat, indexes.size(), owners.size());

        return indexes;
    }

    // ------------------------------------------------------------
    // locking
    // ------------------------------------------------------------

    public SeatDtos.LockSeatResponse lockSeats(SeatDtos.LockSeatRequest request, Long userId) {
        Long sessionId = request.scheduleId();
        List<Integer> seatIndexes = request.seatIndexes();

        Map<String, Object> schedule = seatQueryMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        int status = toInt(schedule.get("status"));
        if (status != 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE);
        }

        // Ensure the bitmap exists before locking against it, otherwise the
        // lock script would happily claim seats that the ledger says are sold.
        loadOccupiedIndexes(sessionId, toInt(schedule.get("totalSeat")));

        String lockToken = SnowflakeIdGenerator.nextString();
        Duration ttl = Duration.ofMinutes(lockMinutes);

        SeatBitmapService.LockResult result =
                seatBitmapService.lock(sessionId, lockToken, seatIndexes, ttl);

        if (!result.success()) {
            String label = labelOfSeat(sessionId, result.conflictSeatIndex());
            log.info("lock rejected: schedule={}, user={}, conflict={}", sessionId, userId, label);
            throw new BizException(ErrorCode.SEAT_OCCUPIED, "座位 " + label + " 已被选走，请重新选择");
        }

        BigDecimal price = (BigDecimal) schedule.get("price");
        BigDecimal amount = price.multiply(BigDecimal.valueOf(seatIndexes.size()));

        log.info("seats locked: schedule={}, user={}, token={}, count={}",
                sessionId, userId, lockToken, seatIndexes.size());

        return new SeatDtos.LockSeatResponse(
                lockToken,
                sessionId,
                seatIndexes,
                labelsOfSeats(sessionId, seatIndexes),
                amount,
                (int) ttl.getSeconds());
    }

    /**
     * Releases a hold.
     *
     * <p>Called from two places: the user backing out of the payment page, and
     * order-service compensating a failed G1. Both pass the same identifier -
     * the lock token, which is also the order number - so there is one code
     * path and one notion of who owns a seat.
     *
     * @return how many seats were actually freed; 0 means they had already
     *         been released or sold, which is not an error
     */
    public int releaseSeats(Long sessionId, String lockToken, boolean force) {
        if (lockToken == null || lockToken.isBlank()) {
            return 0;
        }
        return seatBitmapService.release(sessionId, lockToken, force);
    }

    /** Called by order-service after payment succeeds (G2). */
    public void confirmSeats(Long sessionId, String orderNo) {
        seatBitmapService.confirm(sessionId, orderNo);
    }

    /**
     * Called by order-service before it opens G1.
     *
     * <p>A lock token is a plain identifier with no expiry attached, so on its
     * own it stays usable after the hold behind it has lapsed. Asking here is
     * what turns it back into evidence of a hold.
     */
    public boolean verifyOwnership(Long sessionId, String orderNo, List<Integer> seatIndexes) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        return seatBitmapService.verifyOwnership(sessionId, orderNo, seatIndexes);
    }

    // ------------------------------------------------------------

    private List<Integer> parseAisleCols(String seatTemplateJson) {
        if (seatTemplateJson == null || seatTemplateJson.isBlank()) {
            return List.of();
        }
        try {
            var node = objectMapper.readTree(seatTemplateJson).get("aisleCols");
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Integer> cols = new ArrayList<>();
            node.forEach(n -> cols.add(n.asInt()));
            return cols;
        } catch (Exception e) {
            // A malformed template must not break the seat map - the client
            // simply renders without aisle gaps.
            log.warn("could not parse aisleCols from seat template: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> labelsOfSeats(Long sessionId, List<Integer> seatIndexes) {
        Map<Integer, String> byIndex = seatLabelsByIndex(sessionId);
        List<String> labels = new ArrayList<>(seatIndexes.size());
        for (Integer index : seatIndexes) {
            labels.add(byIndex.getOrDefault(index, String.valueOf(index)));
        }
        return labels;
    }

    private String labelOfSeat(Long sessionId, int seatIndex) {
        return seatLabelsByIndex(sessionId).getOrDefault(seatIndex, String.valueOf(seatIndex));
    }

    /** Builds "{row}排{col}座" labels for a screening. */
    private Map<Integer, String> seatLabelsByIndex(Long sessionId) {
        Map<Integer, String> labels = new java.util.HashMap<>();
        for (Map<String, Object> row : seatQueryMapper.selectSeatLayout(sessionId)) {
            int index = toInt(row.get("seatIndex"));
            labels.put(index, toInt(row.get("rowNum")) + "排" + toInt(row.get("colNum")) + "座");
        }
        return labels;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /** null-safe, because a seat outside every band is possible and must not throw. */
    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
