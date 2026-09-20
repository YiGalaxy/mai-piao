package com.maipiao.queue.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints, reachable only from inside the cluster.
 *
 * <p>The gateway rejects {@code /api/*&#47;inner/**} before consulting its
 * public whitelist, which is what keeps these off the internet - they answer
 * whether a given user is allowed to buy, and that is not a question to expose
 * to whoever asks.
 */
@Slf4j
@RestController
@RequestMapping("/inner/queue")
@RequiredArgsConstructor
public class QueueInternalController {

    private final QueueService queueService;

    /**
     * Whether this user may buy from this screening right now.
     *
     * <p>Answers {@code false} rather than failing when the token is missing
     * or wrong: "not admitted" is the ordinary state of almost everybody
     * during a rush sale, not an error.
     */
    @PostMapping("/token/verify")
    public R<Boolean> verifyToken(@RequestParam Long scheduleId,
                                  @RequestParam Long userId,
                                  @RequestParam String token) {
        return R.ok(queueService.verifyToken(scheduleId, userId, token));
    }

    /**
     * Spends an admission, once.
     *
     * <p>The seat service calls this after it has taken the seats. A token
     * left valid after use would let one admission buy repeatedly, which is
     * exactly what the line exists to stop.
     */
    @PostMapping("/token/consume")
    public R<Boolean> consumeToken(@RequestParam Long scheduleId,
                                   @RequestParam Long userId,
                                   @RequestParam String token) {
        return R.ok(queueService.consumeToken(scheduleId, userId, token));
    }
}
