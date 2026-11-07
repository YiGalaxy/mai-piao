package com.maipiao.pay.job;

import com.maipiao.pay.channel.*;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.service.PaymentSeatSyncService;
import com.maipiao.pay.service.PaymentTxService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 回调丢失或预创建响应丢失均从稳定支付身份恢复；未知结果保持未决。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileJob {
    private final PaymentMapper payments;
    private final PaymentChannelFactory channels;
    private final PaymentTxService transactions;
    private final PaymentSeatSyncService seatSync;

    @Scheduled(fixedDelayString = "${maipiao.pay.reconcile-ms:10000}")
    @SchedulerLock(name = "payment-reconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void reconcile() {
        for (Payment payment :
                payments.selectForReconciliation(LocalDateTime.now().minusSeconds(30))) {
            try {
                reconcileOne(payment);
            } catch (Exception e) {
                log.error("payment awaiting reconciliation: {}", payment.getPaymentNo(), e);
            }
        }
    }

    public void reconcileOne(Payment payment) {
        payments.touch(payment.getPaymentNo());
        PaymentChannel channel = channels.get(payment.getChannel());
        var result = channel.query(payment.getPaymentNo());
        if (result == null || !result.found()) return;
        if ("PENDING".equals(result.status())
                && !payment.getExpireTime().isAfter(LocalDateTime.now())) {
            result = channel.close(payment.getPaymentNo());
        }
        if (result == null
                || !result.found()
                || result.amount() == null
                || result.amount().compareTo(payment.getAmount()) != 0
                || result.channelTradeNo() == null) return;
        if (payment.getChannelTradeNo() == null) {
            if (payments.bindChannel(payment.getPaymentNo(), result.channelTradeNo(), null) != 1)
                return;
        } else if (!payment.getChannelTradeNo().equals(result.channelTradeNo())) {
            throw new IllegalStateException("渠道支付身份发生变化");
        }
        if ("SUCCESS".equals(result.status())) {
            if (transactions.markPaid(
                    new PaymentChannel.NotifyResult(
                            true,
                            payment.getPaymentNo(),
                            payment.getOrderNo(),
                            result.channelTradeNo(),
                            result.amount(),
                            "SUCCESS",
                            "channel query"))) {
                seatSync.synchronize(payment.getPaymentNo());
            }
        } else if ("CLOSED".equals(result.status())) {
            payments.closePayment(payment.getPaymentNo());
        }
    }
}
