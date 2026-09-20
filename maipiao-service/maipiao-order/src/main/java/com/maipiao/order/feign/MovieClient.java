package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Calls into movie-service for the inventory operations of the global
 * transactions (G1, G2, G3).
 *
 * <p>These endpoints are transactional branches. Their service-side
 * implementations assert the affected row count and throw when it does not
 * match, which is what lets Seata roll the whole transaction back - so a
 * failure surfacing here as an exception is the intended behaviour, not
 * something to swallow.
 */
@FeignClient(name = "maipiao-movie", path = "/inner")
public interface MovieClient {

    /**
     * G1 branch: reserve seats against a screening's inventory.
     *
     * <p>{@code expireTime} is a String, not a {@code LocalDateTime}, on
     * purpose. Feign serialises a temporal by calling toString(), which yields
     * {@code 2026/9/20 20:31} - slashes and a space. The receiving endpoint
     * expects ISO-8601 ({@code 2026-09-20T20:31:00}) and rejects anything else
     * with "parameter has the wrong type", which reads like a mapping bug
     * rather than a serialisation one. Formatting explicitly at the call site
     * keeps the wire format visible and unambiguous.
     *
     * @param userId     recorded as the holder of the lock, for the timeout sweep
     * @param expireTime ISO-8601 local date-time, e.g. 2026-09-20T20:31:00
     */
    @PostMapping("/schedule/occupy")
    R<Void> occupy(@RequestParam Long sessionId,
                   @RequestParam String orderNo,
                   @RequestParam Long userId,
                   @RequestParam int count,
                   @RequestParam String expireTime,
                   @RequestParam java.util.List<Integer> seatIndexes);

    /** G2 branch: locked seats become sold. */
    @PostMapping("/schedule/sold")
    R<Void> confirmSold(@RequestParam Long sessionId,
                        @RequestParam String orderNo,
                        @RequestParam int count);

    /**
     * G3 branch: give seats back.
     *
     * @param releaseToPool true only for a normal refund; false when the seats
     *                      were already released (a late payment) and releasing
     *                      again would corrupt the sold counter
     */
    @PostMapping("/schedule/release")
    R<Void> release(@RequestParam Long sessionId,
                    @RequestParam String orderNo,
                    @RequestParam int count,
                    @RequestParam boolean releaseToPool);

    /** Screening snapshot for building an order. */
    @org.springframework.web.bind.annotation.GetMapping("/schedule/{sessionId}/snapshot")
    R<Map<String, Object>> scheduleSnapshot(@org.springframework.web.bind.annotation.PathVariable Long sessionId);
}
