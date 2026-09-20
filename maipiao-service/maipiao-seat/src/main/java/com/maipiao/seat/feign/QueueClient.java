package com.maipiao.seat.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用 queue-service，执行抢购场次的准入检查。
 *
 * <p>排队真正被执行的地方在这里。网关也会挡掉售罄和暂停的流量，但它是从 query string
 * 里读场次 id 的 —— 那是调用方自己给的值 —— 所以它最多只能算个泄压阀。在这里，场次 id
 * 来自座位服务本来就在处理的那个请求，令牌是拿由它派生出来的 key 去校验的。调用方没法
 * 靠撒谎绕过这一关。
 *
 * <p>是消费而不是仅仅校验：一次用完还能继续生效的准入资格，会让一个排队位置反复购买，
 * 而这正是排队要防的事。
 */
@FeignClient(name = "maipiao-queue", path = "/inner/queue")
public interface QueueClient {

    /**
     * 花掉一个准入令牌。
     *
     * @return 令牌有效时为 true；只要匹配上就会被删掉，所以重试不可能成功两次
     */
    @PostMapping("/token/consume")
    R<Boolean> consumeToken(@RequestParam Long scheduleId,
                            @RequestParam Long userId,
                            @RequestParam String token);
}
