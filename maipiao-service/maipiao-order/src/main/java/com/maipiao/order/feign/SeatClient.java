package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Calls seat-service.
 *
 * <p>Note both operations here are <b>outside</b> the distributed transaction.
 * Seat state lives in Redis, which Seata does not manage, so the Redis side is
 * driven by explicit calls with explicit compensation:
 *
 * <ul>
 *   <li>{@link #confirm} runs after G2 commits. Missing it leaves the seat
 *       marked as merely locked, and the timeout sweep would eventually free a
 *       seat that has been paid for. It is retried by the reconciliation job.</li>
 *   <li>{@link #release} runs from the G1 failure path, not from inside it.</li>
 * </ul>
 */
@FeignClient(name = "maipiao-seat", path = "/seat")
public interface SeatClient {

    /** Marks a hold as sold. Called after the order is paid. */
    @PostMapping("/inner/confirm")
    R<Void> confirm(@RequestParam Long sessionId, @RequestParam String orderNo);

    /**
     * Gives a hold back. Called from the G1 failure path and on cancellation.
     *
     * <p>Idempotent on the seat side: only seats whose owner marker still
     * matches this order are cleared, so running it twice - or after the order
     * was already cancelled - frees nothing extra.
     */
    @PostMapping("/inner/release")
    R<Integer> release(@RequestParam Long sessionId, @RequestParam String orderNo);
}
