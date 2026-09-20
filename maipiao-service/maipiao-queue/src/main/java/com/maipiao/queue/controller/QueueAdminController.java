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
 * 暂停开关。
 *
 * <p>它存在的意义是：出了问题能一个调用就把销售停下来 —— 异常流量、价格配错、艺人
 * 团队叫停。没有它，想停一场抢购就只能把场次下架，而下架是取消它，不是把它按住。
 *
 * <p>仅限管理员。网关会在查阅公开白名单之前，就把不带 admin 角色的令牌挡在
 * {@code /api/*&#47;admin/**} 之外 —— 所以校验是在边缘做的，不是在这里。这个注释的
 * 早先版本写着"这个检查哪里都没有"；现在有了，而且它放在所有 admin 路由共用的那一个
 * 地方，而不是在每个 controller 里重复一遍。
 */
@Slf4j
@RestController
@RequestMapping("/queue/admin")
@RequiredArgsConstructor
public class QueueAdminController {

    private final QueueService queueService;

    /** 停止放人。排队位置保留，所以恢复后接着卖。 */
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
