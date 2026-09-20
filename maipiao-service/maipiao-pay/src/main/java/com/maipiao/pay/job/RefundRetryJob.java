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
 * <p>退款单是先落库、再请求渠道的，所以任何一次「以为发了其实没成功」都会留下一行
 * 可重试的记录。这个任务就是那行的出路 —— 没有它，一笔失败在渠道抖动上的退款会永远
 * 停在「退款中」，用户的钱既不在他手上，也没回到账上。
 *
 * <p>退避节奏由 {@code recordFailure} 在 SQL 里算（1、2、4、8、16 分钟封顶），
 * 这里只负责问「哪些到期了」。把节奏放在一个地方，是因为两个地方各算一遍迟早会不一致，
 * 而不一致的退避意味着要么重试太密打爆渠道，要么太疏让用户等。
 *
 * <p>重试满 5 次的会被标记为失败并停止 —— 需要人来看了。一个永远重试的循环会把这件
 * 唯一需要人的事藏起来。
 *
 * <p>多实例同时跑并不会退错钱：{@code refundMapper.insertIfAbsent} 靠 {@code payment_no}
 * 上的唯一键挡住重复，渠道那一侧收到的也还是一笔退款。加锁省下的是重复的渠道调用 ——
 * 而渠道调用是有外部成本的，这一点让它比「省点数据库往返」更值得防。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRetryJob {

    private final RefundService refundService;

    // lockAtLeastFor 刻意取得和 fixedDelay 同一个量级：退款重试要打渠道，
    // 而渠道本身就是这里最容易出问题的一环，不该被多实例轮着打。
    @SchedulerLock(name = "refund-retry-sweep",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
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
