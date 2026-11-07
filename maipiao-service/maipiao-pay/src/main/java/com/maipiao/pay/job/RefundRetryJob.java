package com.maipiao.pay.job;

import com.maipiao.pay.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把没退成功的钱再退一次。
 *
 * <p>退款单是先落库、再请求渠道的，所以任何一次「以为发了其实没成功」都会留下一行 可重试的记录。这个任务就是那行的出路 —— 没有它，一笔失败在渠道抖动上的退款会永远
 * 停在「退款中」，用户的钱既不在他手上，也没回到账上。
 *
 * <p>失败后按 SQL 计算的退避时间重试；渠道结果未知时继续核对原退款单。 ShedLock 避免多实例重复扫描和调用渠道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRetryJob {

    private final RefundService refundService;

    @SchedulerLock(name = "refund-retry-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelayString = "${maipiao.pay.refund-retry-interval-ms:60000}")
    public void retryPendingRefunds() {
        try {
            refundService.retryPending();
        } catch (Exception e) {
            // 数据库或 Redis 抖一下不该让整个任务死掉 —— 下一轮还会来。
            log.error("refund retry sweep failed", e);
        }
    }
}
