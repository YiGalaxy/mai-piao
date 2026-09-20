package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 调用 pay-service 把钱要回来。
 *
 * <p>一次调用，而且是故意做成请求而不是命令：退款被记录下来并发给支付渠道，
 * 订单则另外被告知结果。一个同步的「钱已经回来了」，
 * 在渠道响应慢、或者受理之后过一小时才结算的那一刻，就是一句假话。
 */
@FeignClient(name = "maipiao-pay", path = "/inner/pay")
public interface PayClient {

    /**
     * 为一笔订单记录并提交退款。
     *
     * <p>按订单幂等：第二次请求会返回已有的那笔退款，
     * 而不是再向客户打一笔钱。
     *
     * @return 退款单号，无论这次调用是不是它创建的
     */
    @PostMapping("/refund")
    R<String> applyRefund(@RequestParam("orderNo") String orderNo,
                          @RequestParam("amount") BigDecimal amount);
}
