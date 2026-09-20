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

/** 等待中的客户端看到的那个队列。 */
@Slf4j
@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /**
     * 加入队列，或者返回已有的位置。
     *
     * <p>每次页面加载都调用它是安全的：第二次加入不会让任何人挪动，所以一个不确定自己是
     * 不是已经排上队的客户端，不必先问一遍。
     */
    @PostMapping("/join")
    public R<QueueDtos.PositionVO> join(@RequestParam Long scheduleId) {
        Long userId = UserContext.require();
        queueService.requireRushSale(scheduleId);
        return R.ok(queueService.join(scheduleId, userId));
    }

    /**
     * 调用方现在排在哪。
     *
     * <p>需要鉴权，和其余浏览类接口不一样。问的是关于调用方本人的事，所以答案取决于知道
     * 他是谁，而网关只会在那些它没放行匿名的路径上去解析身份。
     */
    @GetMapping("/position")
    public R<QueueDtos.PositionVO> position(@RequestParam Long scheduleId) {
        return R.ok(queueService.position(scheduleId, UserContext.require()));
    }

    /** 放弃排队位置。已经发出的令牌不受影响。 */
    @PostMapping("/leave")
    public R<Void> leave(@RequestParam Long scheduleId) {
        queueService.leave(scheduleId, UserContext.require());
        return R.ok();
    }
}
