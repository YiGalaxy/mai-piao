package com.maipiao.pay.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.pay.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 服务间调用的退款接口。
 *
 * <p>只能从集群内部访问：网关会在查白名单之前就把 {@code /api/*&#47;inner/**} 拒掉。
 * 这一点在这里比在任何地方都更要紧 —— 这些接口是要动钱的。
 */
@Slf4j
@RestController
@RequestMapping("/inner/pay")
@RequiredArgsConstructor
public class PayInternalController {

    private final RefundService refundService;

    /**
     * 记下一笔退款，然后发给渠道方。
     *
     * <p>由 order-service 在把订单推进到 REFUNDING 之后调用。这个先后顺序不是偶然：
     * 先写下意图，再去问渠道方，这样渠道方挂掉时留下的是「一笔可以重试的退款」，
     * 而不是一个按了按钮却什么都没发生的用户。
     *
     * @return 退款单号，可能是已有的，也可能是新建的
     */
    @PostMapping("/refund")
    public R<String> refund(@RequestParam String orderNo,
                            @RequestParam(required = false) BigDecimal amount) {
        return R.ok(refundService.refund(orderNo, amount));
    }
}
