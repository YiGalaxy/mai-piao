package com.maipiao.seat.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.seat.service.SeatMapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. Not routed by the gateway - the paths live
 * under {@code /inner} and the gateway only forwards {@code /api/**}.
 *
 * <p>These are the Redis half of the order lifecycle. They sit deliberately
 * outside the Seata global transaction: Redis is not a transactional resource,
 * so instead of a branch there is an explicit call plus an explicit
 * compensation on the failure path.
 */
@Slf4j
@RestController
@RequestMapping("/inner/seat")
@RequiredArgsConstructor
public class SeatInternalController {

    private final SeatMapService seatMapService;

    /**
     * Marks a hold as sold.
     *
     * <p>Does not need to be transactional and does not need to throw: the
     * bitmap bit already reads as taken, so a missed call leaves the seat
     * unavailable either way. What it fixes is the owner marker, which is what
     * stops the timeout sweep from later freeing a seat somebody paid for.
     */
    @PostMapping("/confirm")
    public R<Void> confirm(@RequestParam Long scheduleId, @RequestParam String orderNo) {
        seatMapService.confirmSeats(scheduleId, orderNo);
        return R.ok();
    }

    /**
     * Gives a hold back.
     *
     * <p>Idempotent: the release script only clears seats whose owner marker
     * still matches this order, so a repeated call frees nothing extra and
     * cannot disturb a seat that has since been sold to someone else.
     *
     * @return how many seats were actually freed
     */
    @PostMapping("/release")
    public R<Integer> release(@RequestParam Long scheduleId, @RequestParam String orderNo) {
        int released = seatMapService.releaseSeats(scheduleId, orderNo, false);
        if (released > 0) {
            log.debug("seat hold released: schedule={}, order={}, count={}",
                    scheduleId, orderNo, released);
        }
        return R.ok(released);
    }
}
