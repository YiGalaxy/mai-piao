package com.maipiao.user.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 问 order-service：某个用户买了多少。
 *
 * <p>一次调用，只在详情页上用。用户列表是故意不向 order-service 展开的：
 * 一页二十个用户就会变成二十次额外的往返，只为算一个扫列表时没人会看的数字。
 *
 * <p>路径是 {@code /inner}，与承接它的 controller 对得上。
 * Feign 会把客户端路径和方法映射拼接起来，而不是用一个替换掉另一个，
 * 所以一个读起来像是 {@code /inner/order} 加 {@code /stats} 的客户端，
 * 实际请求的是 {@code /inner/order/stats} —— 而那不是接口所在的地方。
 * 这个坑在这个项目里已经咬过两次了，第一次是 SeatClient。
 */
@FeignClient(name = "maipiao-order", path = "/inner")
public interface OrderStatsClient {

    /** @return {@code {"orderCount": n, "paidAmount": decimal}}，字段名保持原样 */
    @GetMapping("/stats")
    R<Map<String, Object>> stats(@RequestParam("userId") Long userId);
}
