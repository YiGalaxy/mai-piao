package com.maipiao.queue.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pause switch.
 *
 * <p>Exists so a sale can be stopped in one call when something is wrong -
 * abnormal traffic, a pricing mistake, an artist's team calling it off. Without
 * it the only way to stop a rush sale is to take the screening off sale, which
 * cancels it rather than holding it.
 *
 * <p><b>Authorization is not implemented.</b> These endpoints require a logged
 * in user, because the gateway authenticates the path, but nothing checks that
 * the user is an administrator - the role travels in a header the gateway
 * injects and no service reads. There is no admin-service in this project yet;
 * when there is, this check belongs there. Until then the honest statement is
 * that the pause switch is reachable by any logged-in user.
 */
@Slf4j
@RestController
@RequestMapping("/queue/admin")
@RequiredArgsConstructor
public class QueueAdminController {

    private final QueueService queueService;

    /** Stops admitting. Places in line are kept, so resuming continues the sale. */
    @PostMapping("/{scheduleId}/pause")
    public R<Void> pause(@PathVariable Long scheduleId) {
        queueService.pause(scheduleId);
        return R.ok();
    }

    @PostMapping("/{scheduleId}/resume")
    public R<Void> resume(@PathVariable Long scheduleId) {
        queueService.resume(scheduleId);
        return R.ok();
    }
}
