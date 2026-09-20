package com.maipiao.queue.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.queue.dto.QueueDtos;
import com.maipiao.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The queue as the waiting client sees it. */
@Slf4j
@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /**
     * Joins the line, or returns the existing place.
     *
     * <p>Safe to call on every page load: a second join does not move anybody,
     * so a client that is unsure whether it is already queued does not have to
     * ask first.
     */
    @PostMapping("/join")
    public R<QueueDtos.PositionVO> join(@RequestParam Long scheduleId) {
        Long userId = UserContext.require();
        queueService.requireRushSale(scheduleId);
        return R.ok(queueService.join(scheduleId, userId));
    }

    /**
     * Where the caller stands.
     *
     * <p>Authenticated, unlike the rest of the browsing surface. The question
     * is about the caller, so the answer depends on knowing who they are, and
     * the gateway only resolves that for paths it is not letting through
     * anonymously.
     */
    @GetMapping("/position")
    public R<QueueDtos.PositionVO> position(@RequestParam Long scheduleId) {
        return R.ok(queueService.position(scheduleId, UserContext.require()));
    }

    /** Gives up the place in line. A token already issued is unaffected. */
    @PostMapping("/leave")
    public R<Void> leave(@RequestParam Long scheduleId) {
        queueService.leave(scheduleId, UserContext.require());
        return R.ok();
    }
}
