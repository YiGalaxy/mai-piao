package com.maipiao.seat.job;

import com.maipiao.seat.feign.ReservationClient;
import com.maipiao.seat.service.SeatBitmapService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收过期的座位持有。
 *
 * <h3>它补的是一个安静的窟窿</h3>
 *
 * <p>用户锁了座、关掉标签页、没有下单 —— 这个座位从此就没了。bitmap 上的那一位一直 置着，{@code seat:owner} 里那条记录也一直在，两个 key 都不带
 * TTL，而座位图读的正是 bitmap。于是这个座位对所有人显示为已占，直到这个场次结束。
 *
 * <p>更麻烦的是它安静。没有任何异常、没有任何日志、账本上这个座位还是「可选」， 只有 Redis 和数据库对不上，而平时没有任何东西会去比这两个。
 *
 * <h3>数据一直是对的，缺的只是读它的人</h3>
 *
 * <p>{@code seat_lock.lua} 从头就在把每个持有的过期时刻写进 {@code seat:delay} 这个 ZSet，{@code
 * seat_confirm.lua}（付款）和 {@code seat_release.lua}（取消）也都会把 自己那条摘掉。也就是说这个 ZSet
 * 在任何时刻的内容，恰好就是「还活着、且还没被任何 其他路径处理掉」的持有 —— 一份现成的待回收清单。
 *
 * <p>任务调用 Lua 脚本读取到期订单，再交给订单服务确认是否可以关闭预约。 确认后沿用正常释放路径；释放逻辑包含 owner 校验，并由取消、退款和 G1 回滚共用。
 *
 * <h3>为什么不直接在 bitmap 或 owner hash 上加 TTL</h3>
 *
 * <p>因为那是以场次为单位的，不是以座位为单位的。一张 bitmap 上同时躺着几十个订单的 占用，给整张 key 设过期时间，等于让先到期的那个订单决定所有人的命运 ——
 * 要么误清掉还在有效期内的持有，要么设得足够长以至于根本不起作用。 到期这件事本来就是逐订单的，那就得逐个订单地记（ZSet 的 score）， 而不能指望 key 级别的 TTL 去表达它。
 *
 * <p>订单座位 Set 不再设置独立 TTL。到期回收必须先由订单服务关闭预约， 再按归属清理 Redis；事务未决时保留索引供后续恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatExpiryJob {

    private final SeatBitmapService seatBitmapService;
    private final ReservationClient reservations;

    /**
     * 每个场次每轮最多回收多少个。
     *
     * <p>不限量的话，一个冷了半天的场次（或者一次 Redis 抖动之后）会在第一轮里 攒下几千个待回收的持有，而那几千次释放会在同一个线程里一个接一个跑完 ——
     * 期间这个场次的正常加锁全在排队。分轮次做，最多就是晚几十秒。
     */
    @Value("${maipiao.seat.expiry-batch:200}")
    private int batchSize;

    // lockAtMostFor 给的是「实例死了怎么办」的答案：持锁实例被 kill -9 之后，
    // 锁最多 5 分钟就自动放开，清扫不会因此永远停摆。
    //
    // lockAtLeastFor 防止的是另一件事：如果间隔被调得很短，没有它，锁会在多个实例
    // 之间被抢来抢去，每一轮换个实例跑，日志碎得没法看。
    @SchedulerLock(name = "seat-expiry-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelayString = "${maipiao.seat.expiry-interval-ms:30000}")
    public void releaseExpiredHolds() {
        Set<String> sessions = seatBitmapService.activeSessions();
        if (sessions.isEmpty()) {
            return;
        }

        int released = 0;
        int holds = 0;

        for (String raw : sessions) {
            Long sessionId = parseSessionId(raw);
            if (sessionId == null) {
                continue;
            }

            try {
                List<String> expired = seatBitmapService.findExpiredHolds(sessionId, batchSize);
                for (String orderNo : expired) {
                    holds++;
                    try {
                        var result = reservations.close(orderNo, sessionId, null, false);
                        if (result != null
                                && result.isSuccess()
                                && Boolean.TRUE.equals(result.getData())) {
                            released += seatBitmapService.release(sessionId, orderNo, false, false);
                        }
                    } catch (Exception e) {
                        // DB/TC/订单服务不可用不能解读为“没有订单”。本次跳过，后续重试。
                        log.warn(
                                "deferred seat expiry: schedule={}, token={}",
                                sessionId,
                                orderNo,
                                e);
                    }
                }
            } catch (Exception e) {
                // 一个场次出错不能把整趟扫描停掉 —— 剩下的场次里同样有人在等座位。
                log.error("could not sweep expired seat holds: schedule={}", sessionId, e);
            }
        }

        if (holds > 0) {
            log.info(
                    "seat sweep: released {} seats from {} expired holds across {} schedules",
                    released,
                    holds,
                    sessions.size());
        }
    }

    private Long parseSessionId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            // 正常流程写进去的永远是场次 id，所以走到这里意味着有人手工动过 Redis，
            // 或者写它的那段代码坏了。摘掉它，否则这一条会让每一轮扫描都白跑一次；
            // 而真正让人看见这件事的，是下面这行日志。
            log.error("unexpected member in seat active set, dropping it: {}", raw);
            seatBitmapService.forgetActiveSession(raw);
            return null;
        }
    }
}
