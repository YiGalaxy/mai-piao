package com.maipiao.movie.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.movie.dto.ScheduleVO;
import com.maipiao.movie.entity.ScheduleSeat;
import com.maipiao.movie.mapper.ScheduleMapper;
import com.maipiao.movie.mapper.ScheduleSeatMapper;
import com.maipiao.movie.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-to-service endpoints for inventory. Not part of the public API.
 *
 * <p>Each of the three write endpoints is a branch of a Seata global
 * transaction. They follow one rule without exception: <b>assert the affected
 * row count</b>. A branch that returns success without changing anything would
 * let the global transaction commit a half-built order, because Seata only
 * rolls back what fails loudly.
 *
 * <p>These are not routed by the gateway - the paths live under {@code /inner}
 * and the gateway only forwards {@code /api/**}.
 */
@Slf4j
@RestController
@RequestMapping("/inner/schedule")
@RequiredArgsConstructor
public class MovieInternalController {

    private final ScheduleMapper scheduleMapper;
    private final ScheduleSeatMapper scheduleSeatMapper;
    private final ScheduleService scheduleService;

    /**
     * G1 branch: reserve seats and mark their ledger rows locked.
     *
     * <p>Two writes, both conditional. The inventory update is the
     * anti-oversell guard; the seat rows carry the per-seat guard. If either
     * affects the wrong number of rows, the exception propagates and Seata
     * rolls back everything already done in this transaction.
     */
    @PostMapping("/occupy")
    public R<Void> occupy(@RequestParam Long scheduleId,
                          @RequestParam String orderNo,
                          @RequestParam Long userId,
                          @RequestParam int count,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                          LocalDateTime expireTime,
                          @RequestParam List<Integer> seatIndexes) {

        int inventoryRows = scheduleMapper.occupySeats(scheduleId, count);
        if (inventoryRows != 1) {
            // Either the screening is no longer on sale, or there are not
            // enough seats left. Both mean "do not proceed".
            throw new BizException(ErrorCode.SCHEDULE_STOCK_NOT_ENOUGH);
        }

        // Resolved from the requested indexes, not from the lock marker.
        //
        // At this point the seats are held in Redis but not yet in this table -
        // the write below is what puts them there. Querying by lock_order_no
        // here finds nothing, which is exactly the bug this replaced.
        List<String> seatIds = seatIdsByIndex(scheduleId, seatIndexes);
        if (seatIds.size() != seatIndexes.size()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "座位信息与场次不匹配");
        }

        int seatRows = scheduleSeatMapper.lockSeats(scheduleId, seatIds, orderNo, userId, expireTime);
        if (seatRows != seatIds.size()) {
            // Somebody took one of these seats between the Redis lock and here.
            // The Redis lock is the fast path; this is the ledger's own check,
            // and disagreeing with it means the two are out of sync.
            throw new BizException(ErrorCode.SEAT_OCCUPIED);
        }

        log.debug("inventory reserved: schedule={}, order={}, seats={}", scheduleId, orderNo, seatRows);
        return R.ok();
    }

    /** G2 branch: locked ledger rows become sold. */
    @PostMapping("/sold")
    public R<Void> sold(@RequestParam Long scheduleId,
                        @RequestParam String orderNo,
                        @RequestParam int count) {

        int inventoryRows = scheduleMapper.confirmSold(scheduleId, count);
        if (inventoryRows != 1) {
            // The hold was released underneath us - the timeout job won the
            // race. Issuing tickets now would sell seats nobody holds.
            throw new BizException(ErrorCode.ORDER_EXPIRED);
        }

        List<String> seatIds = seatIdsOf(scheduleId, orderNo);
        int seatRows = scheduleSeatMapper.markSold(scheduleId, seatIds, orderNo);
        if (seatRows != seatIds.size()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "座位状态与订单不一致");
        }

        log.debug("inventory sold: schedule={}, order={}, seats={}", scheduleId, orderNo, seatRows);
        return R.ok();
    }

    /**
     * G3 branch: give seats back.
     *
     * @param releaseToPool true for a normal refund (sold -&gt; available);
     *                      false when the seats were only held and never sold,
     *                      or were already released by the timeout job
     */
    @PostMapping("/release")
    public R<Void> release(@RequestParam Long scheduleId,
                           @RequestParam String orderNo,
                           @RequestParam int count,
                           @RequestParam boolean releaseToPool) {

        List<String> seatIds = seatIdsOf(scheduleId, orderNo);
        if (seatIds.isEmpty()) {
            // Nothing holds these seats any more. Not an error: a retried
            // cancellation, or the timeout job having got there first. The
            // caller wanted them free, and they are.
            log.debug("nothing to release: schedule={}, order={}", scheduleId, orderNo);
            return R.ok();
        }

        int released = releaseToPool
                ? scheduleSeatMapper.releaseSoldSeats(scheduleId, seatIds, orderNo)
                : scheduleSeatMapper.releaseLockedSeats(scheduleId, seatIds, orderNo);

        if (released == 0) {
            // The ledger rows moved on without us - already released, or sold
            // to somebody else. Counter must not move either, or it would
            // describe seats this order never held.
            return R.ok();
        }

        // Move the matching counter, and only the matching one: held seats
        // live in locked_seat, paid-for seats live in sold_seat.
        if (releaseToPool) {
            scheduleMapper.releaseSold(scheduleId, released);
        } else {
            scheduleMapper.releaseLocked(scheduleId, released);
        }

        log.debug("inventory released: schedule={}, order={}, released={}, toPool={}",
                scheduleId, orderNo, released, releaseToPool);
        return R.ok();
    }

    /** Snapshot used by order-service to build an order row. */
    @GetMapping("/{scheduleId}/snapshot")
    public R<Map<String, Object>> snapshot(@PathVariable Long scheduleId) {
        ScheduleVO detail = scheduleService.detail(scheduleId);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("scheduleId", detail.getId());
        snapshot.put("filmId", detail.getFilmId());
        snapshot.put("filmName", detail.getFilmName());
        snapshot.put("cinemaId", detail.getCinemaId());
        snapshot.put("cinemaName", detail.getCinemaName());
        snapshot.put("hallName", detail.getHallName());
        snapshot.put("hallType", detail.getHallType());
        snapshot.put("showDate", detail.getShowDate());
        snapshot.put("startTime", detail.getStartTime());
        snapshot.put("price", detail.getPrice());
        snapshot.put("remainingSeat", detail.getRemainingSeat());
        snapshot.put("status", detail.getStatus());
        return R.ok(snapshot);
    }

    // ------------------------------------------------------------

    /**
     * The seat ids held by an order, read from the ledger's lock marker.
     *
     * <p>Used by the sold and release paths, which run after the seats have
     * been claimed, so the marker is the right thing to look up.
     *
     * <p>Read from the database rather than taken from the request: trusting a
     * client-supplied mapping between indexes and seat ids would let an order
     * be written against different seats than the ones actually held.
     */
    private List<String> seatIdsOf(Long scheduleId, String orderNo) {
        return scheduleSeatMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<ScheduleSeat>lambdaQuery()
                                .eq(ScheduleSeat::getScheduleId, scheduleId)
                                .eq(ScheduleSeat::getLockOrderNo, orderNo))
                .stream()
                .map(ScheduleSeat::getSeatId)
                .toList();
    }

    /**
     * Resolves bitmap offsets to seat ids.
     *
     * <p>Used by the occupy path, where the seats are held in Redis but not
     * yet marked in this table - looking them up by lock order number there
     * would find nothing.
     */
    private List<String> seatIdsByIndex(Long scheduleId, List<Integer> seatIndexes) {
        return scheduleSeatMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<ScheduleSeat>lambdaQuery()
                                .eq(ScheduleSeat::getScheduleId, scheduleId)
                                .in(ScheduleSeat::getSeatIndex, seatIndexes))
                .stream()
                .map(ScheduleSeat::getSeatId)
                .toList();
    }
}
