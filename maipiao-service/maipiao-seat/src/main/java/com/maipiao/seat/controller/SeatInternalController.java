package com.maipiao.seat.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.seat.service.SeatMapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service-to-service endpoints.
 *
 * <p>Reachable only from inside the cluster. The gateway stops
 * {@code /api/*&#47;inner/**} before the public whitelist is consulted - a
 * whitelist cannot say "public except for these", and {@code /api/movie/**}
 * being open for anonymous browsing is what made that necessary.
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
    public R<Void> confirm(@RequestParam Long sessionId, @RequestParam String orderNo) {
        seatMapService.confirmSeats(sessionId, orderNo);
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
    public R<Integer> release(@RequestParam Long sessionId, @RequestParam String orderNo) {
        int released = seatMapService.releaseSeats(sessionId, orderNo, false);
        if (released > 0) {
            log.debug("seat hold released: schedule={}, order={}, count={}",
                    sessionId, orderNo, released);
        }
        return R.ok(released);
    }

    /**
     * Whether the order still holds the seats it names.
     *
     * <p>Called by order-service before it opens the G1 transaction. A lock
     * token carries no expiry of its own, so without this an order can be
     * placed against seats whose hold lapsed and which somebody else has since
     * taken. Returns a boolean rather than failing, because "no" is an ordinary
     * answer here, not an error.
     */
    @PostMapping("/verify")
    public R<Boolean> verify(@RequestParam Long sessionId,
                             @RequestParam String orderNo,
                             @RequestParam List<Integer> seatIndexes) {
        return R.ok(seatMapService.verifyOwnership(sessionId, orderNo, seatIndexes));
    }
}
