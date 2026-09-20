package com.maipiao.order.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.order.dto.OrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.feign.PayClient;
import com.maipiao.order.service.OrderRefundService;
import com.maipiao.order.service.OrderService;
import com.maipiao.order.service.OrderStateMachine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRefundService orderRefundService;
    private final OrderStateMachine stateMachine;
    private final PayClient payClient;

    /**
     * 为调用方已经持有的座位创建订单。
     *
     * <p>这是 G1 的入口：service 方法会开启一个跨越本服务、movie-service
     * 和 user-service 的 Seata 全局事务。
     */
    @PostMapping("/create")
    public R<OrderDtos.CreateOrderResponse> create(@Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        return R.ok(orderService.create(request, UserContext.require()));
    }

    /** 我的订单，最新的在前，可选按状态过滤。 */
    @GetMapping("/list")
    public R<List<Order>> list(@RequestParam(required = false) Integer status) {
        return R.ok(orderService.listByUser(UserContext.require(), status));
    }

    @GetMapping("/{orderNo}")
    public R<OrderDtos.OrderDetail> detail(@PathVariable String orderNo) {
        return R.ok(orderService.detail(orderNo, UserContext.require()));
    }

    /**
     * 取消一笔未支付的订单并释放它的座位。
     *
     * <p>返回本次调用是不是完成取消的那一次。返回 false 不是错误 ——
     * 它意味着订单已经被取消了，通常是被超时任务抢在用户按下按钮前的一秒干掉的。
     */
    /**
     * 申请退款。
     *
     * <p>各种关卡在这里判定，而不是信客户端：还在退款窗口内、尚未验票、确实已支付。
     * 浏览器里置灰的按钮是礼貌，不是规则。
     *
     * <p>状态的动作和钱的动作是两个分开的调用，这是故意的。订单先变成 REFUNDING，
     * 之后才去问支付渠道，所以一个迟缓或宕掉的渠道，留下的是「有人申请过什么」的记录，
     * 而不是一个按了按钮却什么都没发生的客户。
     */
    @PostMapping("/{orderNo}/refund")
    public R<Map<String, Object>> refund(@PathVariable String orderNo,
                                         @RequestParam(required = false) String reason) {
        Long userId = UserContext.require();
        Order order = stateMachine.require(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        BigDecimal amount = orderRefundService.startRefund(orderNo,
                reason == null || reason.isBlank() ? "USER_REQUEST" : reason);

        // 然后交给 pay-service，退款事务归它管。
        String refundNo = payClient.applyRefund(orderNo, amount).getData();

        Map<String, Object> body = new HashMap<>();
        body.put("refundNo", refundNo);
        body.put("amount", amount);
        return R.ok(body);
    }

    /** 能不能退款，好让页面把按钮置灰。 */
    @GetMapping("/{orderNo}/refundable")
    public R<Map<String, Object>> refundable(@PathVariable String orderNo) {
        Long userId = UserContext.require();
        Order order = stateMachine.require(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        OrderRefundService.RefundCheck check = orderRefundService.checkRefundable(order);

        Map<String, Object> body = new HashMap<>();
        body.put("allowed", check.allowed());
        body.put("reason", check.reason());
        body.put("amount", check.amount());
        return R.ok(body);
    }

    @PostMapping("/{orderNo}/cancel")
    public R<Boolean> cancel(@PathVariable String orderNo) {
        return R.ok(orderService.cancel(orderNo, UserContext.require(), true));
    }
}
