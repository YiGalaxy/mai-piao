package com.maipiao.pay.job;

import static com.maipiao.common.core.result.RemoteResult.requireSuccess;

import com.maipiao.pay.feign.OrderClient;
import com.maipiao.pay.mapper.PaymentMapper;
import com.maipiao.pay.mapper.RefundMapper;
import com.maipiao.pay.service.PaymentSeatSyncService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 全局事务提交后的 Redis 操作可重复执行；只有远端明确成功才完成本地标记。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatSyncJob {
    private final PaymentMapper payments;
    private final RefundMapper refunds;
    private final PaymentSeatSyncService seatSync;
    private final OrderClient orders;

    @Scheduled(fixedDelayString = "${maipiao.pay.seat-sync-ms:10000}")
    @SchedulerLock(name = "pay-seat-sync", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void synchronizeSeats() {
        var before = LocalDateTime.now().minusSeconds(30);
        for (var payment : payments.selectSeatSyncPending(before)) {
            try {
                payments.touchSeatSync(payment.getPaymentNo());
                seatSync.synchronize(payment.getPaymentNo());
            } catch (Exception e) {
                log.error("paid seats awaiting sync: {}", payment.getPaymentNo(), e);
            }
        }
        for (var refund : refunds.selectSeatReleasePending(before)) {
            try {
                refunds.touchSeatRelease(refund.getRefundNo());
                requireSuccess(orders.releaseRefundedSeats(refund.getOrderNo()), "释放退款座位");
                refunds.markSeatReleased(refund.getRefundNo());
            } catch (Exception e) {
                log.error("refunded seats awaiting release: {}", refund.getRefundNo(), e);
            }
        }
    }
}
