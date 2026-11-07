package com.maipiao.order.service;

import static com.maipiao.common.core.result.RemoteResult.requireSuccess;

import com.maipiao.order.feign.PayClient;
import com.maipiao.order.mapper.RefundRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 状态变更和 outbox 同事务提交；发送与确认允许重复，整单退款以 orderNo 为稳定申请键。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundRequestDispatcher {
    private final RefundRequestMapper requests;
    private final PayClient pay;

    @Scheduled(fixedDelayString = "${maipiao.order.refund-dispatch-ms:10000}")
    @SchedulerLock(name = "order-refund-dispatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void dispatchPending() {
        for (var request : requests.pending()) dispatch(request.getOrderNo());
    }

    public void dispatch(String orderNo) {
        var request = requests.find(orderNo);
        if (request == null || request.getRefundNo() != null) return;
        try {
            String refundNo =
                    requireSuccess(pay.applyRefund(orderNo, request.getAmount()), "投递退款申请");
            if (refundNo == null || refundNo.isBlank())
                throw new IllegalStateException("支付服务未返回退款号");
            requests.sent(orderNo, refundNo);
        } catch (Exception e) {
            requests.failed(orderNo, "退款投递未确认");
            log.error("refund request awaiting retry: orderNo={}", orderNo, e);
        }
    }
}
