package com.maipiao.queue.service;

import com.maipiao.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 推动队列往前走。
 *
 * <p>它不是一个钉在旁边的清理任务 —— 队列之所以能前进，全靠它在跑。没有人是靠"申请"
 * 被放行的；他们是在调度器下一次张望时正好站在队首，才被放行的。
 *
 * <p>在每个实例上都跑，没有选主，也没有锁。这是安全的，因为准入在一个 Lua 脚本里完成：
 * Redis 把整个脚本当作一个操作执行，所以两个同时醒来的实例是把一批人分掉，而不是都
 * 拿走同一批。加锁反而更慢，而且只会把它本该防住的那种故障重新引进来 —— 一个卡住的
 * 持锁者把整场销售停住。
 *
 * <p>（这里原本写的是 {@code ZPOPMIN}。那个命令在本项目的 Redis 客户端上用不了 ——
 * Redisson 解不了它的应答，弹出会在服务端发生而客户端拿不到结果，排队位置因此凭空消失。
 * 原子性的结论没变，变的只是实现它的东西。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdmissionDispatcher {

    private final QueueService queueService;
    private final QueueProperties properties;

    /**
     * 这一轮放多少人进来。
     *
     * <p>抽出来、且是纯函数，因为整个设计就压在这一个数字上，而它应该能在不读一遍调度器
     * 的情况下被讨论。
     *
     * <p>定目标看的是库存，不是队列长度：按等待人数的固定比例去放，等于这场销售越是没戏，
     * 放人放得越快。{@code admitFactor} 之所以要超发一点，是因为被放进来的人并不是个个
     * 都会买 —— 犹豫、关标签页、反复动摇都很正常 —— 而严格按座位数放人，最后几个座位
     * 就卖不掉。在 0 处截断，是因为一个本来就已经超发的队列，就该原地不动。
     */
    int computeQuota(int remainingSeats, int inFlight) {
        if (remainingSeats <= 0) {
            return 0;
        }
        int target = (int) Math.ceil(remainingSeats * properties.getAdmitFactor());
        int quota = target - inFlight;
        if (quota <= 0) {
            return 0;
        }
        return Math.min(quota, properties.getMaxBatch());
    }

    @Scheduled(fixedDelayString = "${maipiao.queue.dispatch-interval-ms:200}")
    public void dispatch() {
        List<Long> schedules;
        try {
            schedules = queueService.rushScheduleIds();
        } catch (Exception e) {
            // Redis 就是队列本身。它不可达时就没什么可调度的，也没什么值得每 200ms
            // 说一遍的话。
            log.error("could not read the rush registry", e);
            return;
        }

        for (Long scheduleId : schedules) {
            try {
                dispatchOne(scheduleId);
            } catch (Exception e) {
                // 一个场次出问题不能把其他场次也拖住 —— 别的每一条队列都一样是真的。
                log.error("dispatch failed: schedule={}", scheduleId, e);
            }
        }
    }

    private void dispatchOne(Long scheduleId) {
        if (queueService.isSoldOut(scheduleId)) {
            // 队列原地留着，不去清空。里面每个人马上就会通过 position 调用得知销售已经
            // 结束，留着他们的代价不过是一个会过期的 key —— 而清空他们，一旦售罄标记本身
            // 是错的（或者因为退款把座位放回来而被撤掉），队列就再也恢复不了了。
            return;
        }
        if (queueService.isPaused(scheduleId)) {
            return;
        }

        // 在统计之前先把过期的准入释放掉，否则那些已经放弃的人会继续占着名额、顶着库存。
        queueService.reapExpired(scheduleId);

        int remaining = queueService.remaining(scheduleId);
        int inFlight = queueService.inflight(scheduleId);
        int quota = computeQuota(remaining, inFlight);

        if (quota <= 0) {
            if (queueService.waiting(scheduleId) == 0 && inFlight == 0) {
                // 两个集合里都没人了。这个场次还挂在注册表里，只是因为最后一个人走的时候
                // 没人注意到。
                queueService.deregister(scheduleId);
            }
            return;
        }

        queueService.admit(scheduleId, quota);
    }
}
