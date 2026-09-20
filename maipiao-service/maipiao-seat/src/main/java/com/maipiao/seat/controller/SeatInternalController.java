package com.maipiao.seat.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.seat.service.SeatMapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间接口。
 *
 * <p>只能从集群内部访问。网关在查阅公开白名单之前就会拦下
 * {@code /api/*&#47;inner/**} —— 白名单没法表达"除了这几个以外都公开"，而
 * {@code /api/movie/**} 为了匿名浏览是开着的，正是这一点让这道拦截成为必要。
 *
 * <p>这些是订单生命周期里属于 Redis 的那一半。它们被刻意放在 Seata 全局事务之外：
 * Redis 不是事务型资源，所以这里没有分支事务，取而代之的是一次显式调用，加上失败路径
 * 上一次显式的补偿。
 */
@Slf4j
@RestController
@RequestMapping("/inner/seat")
@RequiredArgsConstructor
public class SeatInternalController {

    private final SeatMapService seatMapService;

    /**
     * 把一个持有标记为已售。
     *
     * <p>不需要事务，也不需要抛异常：bitmap 上那个 bit 本来就已经读作"被占用"，
     * 所以就算这次调用丢了，座位也照样是不可选的。它修正的是 owner 标记，而正是这个
     * 标记拦住了超时扫描，让它后来不会把一个别人已经付过钱的座位释放掉。
     */
    @PostMapping("/confirm")
    public R<Void> confirm(@RequestParam Long sessionId, @RequestParam String orderNo) {
        seatMapService.confirmSeats(sessionId, orderNo);
        return R.ok();
    }

    /**
     * 交回一个持有；退款时则是交回一个已经卖掉的座位。
     *
     * <p>幂等：释放脚本只清除 owner 标记仍然匹配本订单的座位，所以重复调用不会多释放
     * 什么，也不会扰动一个已经卖给了别人的座位。
     *
     * <p>{@code includeSold} 是一个单独的开关，而不是默认行为，因为被持有的座位和已售
     * 的座位靠 owner 标记区分，而例行释放只允许释放其中一种。一条迟到的超时消息把付过
     * 钱的座位释放掉，正是这个区分要防住的故障。
     *
     * <p>退款是已售座位真正回到市场上的那种场景，也是唯一的一种。
     *
     * @return 实际被释放的座位数
     */
    @PostMapping("/release")
    public R<Integer> release(@RequestParam Long sessionId,
                              @RequestParam String orderNo,
                              @RequestParam(defaultValue = "false") boolean includeSold) {
        int released = seatMapService.releaseSeats(sessionId, orderNo, false, includeSold);
        if (released > 0) {
            log.debug("seat hold released: schedule={}, order={}, count={}, sold={}",
                    sessionId, orderNo, released, includeSold);
        }
        return R.ok(released);
    }

    /**
     * 该订单是否仍然持有它点名的那些座位，以及它们值多少钱。
     *
     * <p>由 order-service 在开启 G1 事务前调用。锁令牌本身不带有效期，没有这个检查，
     * 就能拿着一个持有早已失效、座位也已被别人拿走的令牌去下单。返回
     * {@code held = false} 而不是抛异常，因为在这里"否"是一个平常的答案，不是错误。
     *
     * <p>价格是顺路带回来的，因为这个调用本来就要发，而它需要的映射本来就在这里。
     * 这不仅仅是省一次调用：它意味着总价是从服务端自己解析出的座位推导出来的，而且
     * 与"确认调用方有权购买这些座位"是同一口气完成的。不存在任何一段能让客户端报出
     * 价格的窗口，因为它从来没有这个机会。
     *
     * <p>用 map 而不是强类型 DTO，理由和 {@code MovieInternalController.snapshot}
     * 一样：order-service 并不依赖本模块的类，为这么小的一个响应体给它加一个依赖，
     * 会把两个部署耦合到一起。
     */
    @PostMapping("/verify")
    public R<Map<String, Object>> verify(@RequestParam Long sessionId,
                                         @RequestParam String orderNo,
                                         @RequestParam List<Integer> seatIndexes) {
        boolean held = seatMapService.verifyOwnership(sessionId, orderNo, seatIndexes);

        Map<String, Object> body = new HashMap<>();
        body.put("held", held);
        if (!held) {
            // 没有可定价的东西：这些座位不是本订单该买的。
            body.put("amount", BigDecimal.ZERO);
            body.put("seats", List.of());
            return R.ok(body);
        }

        SeatMapService.SeatPricing pricing = seatMapService.priceOf(sessionId, seatIndexes);
        List<Map<String, Object>> lines = new ArrayList<>(pricing.lines().size());
        for (SeatMapService.SeatPricing.Line line : pricing.lines()) {
            lines.add(Map.of("seatIndex", line.seatIndex(),
                    "tierId", line.tierId() == null ? 0L : line.tierId(),
                    "price", line.price()));
        }
        body.put("amount", pricing.total());
        body.put("seats", lines);
        return R.ok(body);
    }
}
