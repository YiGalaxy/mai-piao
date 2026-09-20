package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 调用 movie-service，执行全局事务（G1、G2、G3）里的库存操作。
 *
 * <p>这些接口都是事务分支。它们在服务端的实现会断言影响行数，对不上就抛异常，
 * 而正是这一点让 Seata 能把整个事务回滚掉 —— 所以一个失败在这里以异常的形式浮出来，
 * 是设计中的行为，不是该被吞掉的东西。
 */
@FeignClient(name = "maipiao-movie", path = "/inner")
public interface MovieClient {

    /**
     * G1 分支：针对某个场次的库存预占座位。
     *
     * <p>{@code expireTime} 是 String 而不是 {@code LocalDateTime}，这是故意的。
     * Feign 序列化时间类型时调用 toString()，得到的是 {@code 2026/9/20 20:31} ——
     * 斜杠加一个空格。接收端期望的是 ISO-8601（{@code 2026-09-20T20:31:00}），
     * 别的一律以「parameter has the wrong type」拒绝，而这个报错读起来像是映射写错了，
     * 而不像序列化问题。在调用点显式格式化，能让线上格式保持可见、不带歧义。
     *
     * @param userId     记录为这次锁的持有者，供超时清扫使用
     * @param expireTime ISO-8601 本地日期时间，例如 2026-09-20T20:31:00
     */
    @PostMapping("/schedule/occupy")
    R<Void> occupy(@RequestParam Long sessionId,
                   @RequestParam String orderNo,
                   @RequestParam Long userId,
                   @RequestParam int count,
                   @RequestParam String expireTime,
                   @RequestParam java.util.List<Integer> seatIndexes);

    /** G2 分支：已锁定的座位变成已售出。 */
    @PostMapping("/schedule/sold")
    R<Void> confirmSold(@RequestParam Long sessionId,
                        @RequestParam String orderNo,
                        @RequestParam int count);

    /**
     * G3 分支：把座位还回去。
     *
     * @param releaseToPool 只有正常退款时才为 true；当座位已经被释放过（迟到的支付）、
     *                      再释放一次会把售出计数弄坏时为 false
     */
    @PostMapping("/schedule/release")
    R<Void> release(@RequestParam Long sessionId,
                    @RequestParam String orderNo,
                    @RequestParam int count,
                    @RequestParam boolean releaseToPool);

    /** 用来组装订单的场次快照。 */
    @org.springframework.web.bind.annotation.GetMapping("/schedule/{sessionId}/snapshot")
    R<Map<String, Object>> scheduleSnapshot(@org.springframework.web.bind.annotation.PathVariable Long sessionId);
}
