package com.maipiao.queue.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 从 movie-service 读取场次的抢购配置。
 *
 * <p>只取元数据 —— 是不是抢购、什么时候开始、一共多少个座位。座位本身从不拉取：还剩
 * 多少是从 bitmap 读的，它既更便宜，也正好是座位服务正在据此行动的那个数字。
 */
@FeignClient(name = "maipiao-movie", path = "/inner/schedule")
public interface MovieClient {

    /** 场次的基本事实，包括 {@code totalSeat} 和 {@code rushStartTime}。 */
    @GetMapping("/{sessionId}/snapshot")
    R<Map<String, Object>> snapshot(@PathVariable("sessionId") Long sessionId);
}
