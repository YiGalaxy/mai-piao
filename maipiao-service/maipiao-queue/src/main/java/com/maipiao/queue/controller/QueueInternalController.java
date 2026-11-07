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
 * 服务间接口，只能从集群内部访问。
 *
 * <p>网关在查阅公开白名单之前就会拒掉 {@code /api/*&#47;inner/**}，正是这一点让它们 留在内网 ——
 * 它们回答的是某个用户是否有权购买，这不是一个该向随便谁敞开的问题。
 */
@Slf4j
@RestController
@RequestMapping("/inner/queue")
@RequiredArgsConstructor
public class QueueInternalController {

    private final QueueService queueService;

    /**
     * 在锁座前原子校验并消费一次准入令牌。
     *
     * <p>如果令牌仍可重复使用，一次准入就能创建多个锁座，失去排队限流的意义。
     */
    @PostMapping("/token/consume")
    public R<Boolean> consumeToken(
            @RequestParam Long scheduleId, @RequestParam Long userId, @RequestParam String token) {
        return R.ok(queueService.consumeToken(scheduleId, userId, token));
    }
}
