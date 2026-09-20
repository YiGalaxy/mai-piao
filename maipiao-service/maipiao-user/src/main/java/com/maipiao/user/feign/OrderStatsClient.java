package com.maipiao.user.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Asks order-service how much a user has bought.
 *
 * <p>One call, on the detail view only. The user list deliberately does not
 * fan out to order-service: a page of twenty users would become twenty extra
 * round trips for a number nobody looks at while scanning a list.
 *
 * <p>The path is {@code /inner}, matching the controller that serves it.
 * Feign concatenates the client path with the method mapping rather than
 * replacing one with the other, so a client that reads as
 * {@code /inner/order} plus {@code /stats} actually requests
 * {@code /inner/order/stats} - which is not where the endpoint lives. This is
 * the second time that has bitten in this project; the first was SeatClient.
 */
@FeignClient(name = "maipiao-order", path = "/inner")
public interface OrderStatsClient {

    /** @return {@code {"orderCount": n, "paidAmount": decimal}} */
    @GetMapping("/stats")
    R<Map<String, Object>> stats(@RequestParam("userId") Long userId);
}
