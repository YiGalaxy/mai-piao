package com.maipiao.seat.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.seat.service.SeatMapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Gives a hold back, or - on a refund - a seat that was sold.
     *
     * <p>Idempotent: the release script only clears seats whose owner marker
     * still matches this order, so a repeated call frees nothing extra and
     * cannot disturb a seat that has since been sold to someone else.
     *
     * <p>{@code includeSold} is a separate flag rather than the default
     * behaviour, because a held seat and a sold one are distinguished by their
     * owner marker and only one of them may be freed by a routine release. A
     * late timeout message that could free a paid-for seat is the failure that
     * distinction prevents.
     *
     * <p>A refund is the case where a sold seat genuinely goes back on the
     * market, and it is the only one.
     *
     * @return how many seats were actually freed
     */
    @PostMapping("/release")
    public R<Integer> release(@RequestParam Long sessionId,
                              @RequestParam String orderNo,
                              @RequestParam(defaultValue = "false") boolean includeSold) {
        int released = seatMapService.releaseSeats(sessionId, orderNo, false, includeSold);
        if (released > 0) {
            log.debug("seat hold released: schedule={}, order={}, count={}, sold={}",
                    sessionId, orderNo, released, includeSold);
        }
        return R.ok(released);
    }

    /**
     * Whether the order still holds the seats it names, and what they cost.
     *
     * <p>Called by order-service before it opens the G1 transaction. A lock
     * token carries no expiry of its own, so without this an order can be
     * placed against seats whose hold lapsed and which somebody else has since
     * taken. Returning {@code held = false} rather than throwing, because "no"
     * is an ordinary answer here, not an error.
     *
     * <p>Price rides along because this call was already being made and the
     * mapping it needs was already here. That is not just an optimisation: it
     * means the total is derived from the seats the server resolved, in the
     * same breath as confirming the caller is entitled to them. There is no
     * window in which a client could name a price, because it is never given
     * the chance to.
     *
     * <p>Shaped as a map rather than a typed DTO for the same reason
     * {@code MovieInternalController.snapshot} is: order-service has no
     * dependency on this module's classes, and giving it one for a payload
     * this small would couple the two deployments.
     */
    @PostMapping("/verify")
    public R<Map<String, Object>> verify(@RequestParam Long sessionId,
                                         @RequestParam String orderNo,
                                         @RequestParam List<Integer> seatIndexes) {
        boolean held = seatMapService.verifyOwnership(sessionId, orderNo, seatIndexes);

        Map<String, Object> body = new HashMap<>();
        body.put("held", held);
        if (!held) {
            // Nothing to price: the seats are not this order's to buy.
            body.put("amount", BigDecimal.ZERO);
            body.put("seats", List.of());
            return R.ok(body);
        }

        SeatMapService.SeatPricing pricing = seatMapService.priceOf(sessionId, seatIndexes);
        List<Map<String, Object>> lines = new ArrayList<>(pricing.lines().size());
        for (SeatMapService.SeatPricing.Line line : pricing.lines()) {
            lines.add(Map.of("seatIndex", line.seatIndex(),
                    "tierId", line.tierId() == null ? 0L : line.tierId(),
                    "price", line.price()));
        }
        body.put("amount", pricing.total());
        body.put("seats", lines);
        return R.ok(body);
    }
}
