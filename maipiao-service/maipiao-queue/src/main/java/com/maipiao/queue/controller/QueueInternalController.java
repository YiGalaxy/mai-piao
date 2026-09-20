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
 * <p>网关在查阅公开白名单之前就会拒掉 {@code /api/*&#47;inner/**}，正是这一点让它们
 * 留在内网 —— 它们回答的是某个用户是否有权购买，这不是一个该向随便谁敞开的问题。
 */
@Slf4j
@RestController
@RequestMapping("/inner/queue")
@RequiredArgsConstructor
public class QueueInternalController {

    private final QueueService queueService;

    /**
     * 这个用户此刻是否可以从这个场次购票。
     *
     * <p>令牌缺失或不对时返回 {@code false}，而不是报错：在一场抢购里，"未被放行"是
     * 几乎所有人的常态，不是错误。
     */
    @PostMapping("/token/verify")
    public R<Boolean> verifyToken(@RequestParam Long scheduleId,
                                  @RequestParam Long userId,
                                  @RequestParam String token) {
        return R.ok(queueService.verifyToken(scheduleId, userId, token));
    }

    /**
     * 把一次准入花掉，且只能一次。
     *
     * <p>座位服务在拿到座位之后调用它。用过之后还能继续生效的令牌，会让一次准入反复购买，
     * 而这恰恰就是排队存在的目的所在。
     */
    @PostMapping("/token/consume")
    public R<Boolean> consumeToken(@RequestParam Long scheduleId,
                                   @RequestParam Long userId,
                                   @RequestParam String token) {
        return R.ok(queueService.consumeToken(scheduleId, userId, token));
    }
}
