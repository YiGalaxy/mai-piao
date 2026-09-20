package com.maipiao.seat.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Calls queue-service to enforce a rush sale's admission.
 *
 * <p>This is where the line is actually enforced. The gateway also turns away
 * sold-out and paused traffic, but it reads the schedule id from the query
 * string - a value the caller supplies - so it can only ever be a pressure
 * valve. Here the schedule id comes from the request the seat service is
 * already processing, and the token is checked against the key derived from
 * it. A caller cannot lie their way past this.
 *
 * <p>Consumed rather than merely verified: an admission that stayed valid
 * after use would let one place in line buy repeatedly, which is the thing the
 * line exists to prevent.
 */
@FeignClient(name = "maipiao-queue", path = "/inner/queue")
public interface QueueClient {

    /**
     * Spends an admission token.
     *
     * @return true when the token was valid; it is deleted either way it
     *         matched, so a retry cannot succeed twice
     */
    @PostMapping("/token/consume")
    R<Boolean> consumeToken(@RequestParam Long scheduleId,
                            @RequestParam Long userId,
                            @RequestParam String token);
}
