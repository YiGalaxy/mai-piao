package com.maipiao.seat.service;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.redis.script.LuaScriptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 座位 bitmap，同时是唯一会写它的地方。
 *
 * <p>每一次变更都走 Lua 脚本。这不是风格上的选择：并发问题就靠它撑着。Redis 把脚本
 * 作为单个整体执行，所以 {@code seat_lock.lua} 里的"先检查后写入"不可能被另一个请求
 * 插进来。拆成 GETBIT 和 SETBIT 两次调用，就会留下一段窗口：两个用户都读到"空着"，
 * 然后都往下走，结果就是同一个座位被卖了两次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatBitmapService {

    private static final RedisScript<List> LOCK_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_lock.lua");
    private static final RedisScript<Long> RELEASE_SCRIPT =
            LuaScriptLoader.ofLong("lua/seat_release.lua");
    private static final RedisScript<Long> CONFIRM_SCRIPT =
            LuaScriptLoader.ofLong("lua/seat_confirm.lua");
    private static final RedisScript<List> MAP_QUERY_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_map_query.lua");
    private static final RedisScript<Long> VERIFY_SCRIPT =
            LuaScriptLoader.ofLong("lua/seat_verify.lua");
    private static final RedisScript<List> ALLOCATE_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_allocate.lua");
    private static final RedisScript<List> SWEEP_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_sweep.lua");

    private final StringRedisTemplate redis;

    /** 一次加锁尝试的结果。 */
    public record LockResult(boolean success, int lockedCount, int conflictSeatIndex) {

        public static LockResult ok(int count) {
            return new LockResult(true, count, -1);
        }

        public static LockResult conflict(int seatIndex) {
            return new LockResult(false, 0, seatIndex);
        }
    }

    // ------------------------------------------------------------
    // 加锁
    // ------------------------------------------------------------

    /**
     * 请求的座位要么全拿到，要么一个都不拿。
     *
     * @return 成功时带上拿到的数量；冲突时给出第一个已被占用的座位索引
     */
    public LockResult lock(Long sessionId, String orderNo, int totalSeat,
                           List<Integer> seatIndexes, Duration ttl) {

        if (seatIndexes == null || seatIndexes.isEmpty()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "未选择座位");
        }

        List<String> keys = List.of(
                CommonConstants.SEAT_MAP_KEY + sessionId,
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo,
                CommonConstants.SOLD_OUT_KEY + sessionId,
                CommonConstants.SEAT_ACTIVE_KEY);

        List<String> args = new ArrayList<>(seatIndexes.size() + 4);
        args.add(orderNo);
        args.add(String.valueOf(System.currentTimeMillis() + ttl.toMillis()));
        args.add(String.valueOf(totalSeat));
        args.add(String.valueOf(sessionId));
        for (Integer index : seatIndexes) {
            args.add(String.valueOf(index));
        }

        List<?> result = execute(LOCK_SCRIPT, keys, args);
        if (result == null || result.size() < 2) {
            // 脚本总是返回一个两元素表；不是这样的话，说明 Redis 或脚本并不是我们
            // 以为的那个东西。
            log.error("unexpected lock script result: {}", result);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }

        long flag = toLong(result.get(0));
        long value = toLong(result.get(1));

        if (flag == 1L) {
            log.debug("seats locked: schedule={}, order={}, count={}", sessionId, orderNo, value);
            return LockResult.ok((int) value);
        }

        log.debug("seat conflict: schedule={}, order={}, seatIndex={}", sessionId, orderNo, value);
        return LockResult.conflict((int) value);
    }

    // ------------------------------------------------------------
    // 分配
    // ------------------------------------------------------------

    /** 一次分配尝试的结果。 */
    public record AllocateResult(boolean success, List<Integer> seatIndexes, int longestFreeRun) {

        public static AllocateResult ok(List<Integer> seatIndexes) {
            return new AllocateResult(true, seatIndexes, seatIndexes.size());
        }

        public static AllocateResult noRun(int longestFreeRun) {
            return new AllocateResult(false, List.of(), longestFreeRun);
        }
    }

    /**
     * 从一份排好序的候选连座段里取座。
     *
     * <p>候选来自 {@link SeatRuns}，是一份快照：从算出它们到执行这一步之间，可能已经
     * 有人把其中某个座位拿走了。脚本会自己把每一个 bit 重新校验一遍，所以一份过期的
     * 候选最多只会带来一次重试，绝不会带来一次重复售出。这正是允许把几何计算放在原子
     * 区之外的唯一理由。
     *
     * @param runs     候选连座段，最优的排在最前；每一段都必须在索引和几何两个意义上
     *                 都相邻，而这正是 {@link SeatRuns} 产出的东西
     * @param adjacent 座位是否必须来自同一段；为 false 时可以从候选顺序中的任意位置
     *                 挑选
     */
    public AllocateResult allocate(Long sessionId, String orderNo, int count, int totalSeat,
                                   List<SeatRuns.Segment> runs, boolean adjacent, Duration ttl) {

        if (count < 1 || runs == null || runs.isEmpty()) {
            return AllocateResult.noRun(0);
        }

        List<String> keys = List.of(
                CommonConstants.SEAT_MAP_KEY + sessionId,
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo,
                CommonConstants.SOLD_OUT_KEY + sessionId,
                CommonConstants.SEAT_ACTIVE_KEY);

        List<String> args = new ArrayList<>(6 + runs.size() * 2);
        args.add(orderNo);
        args.add(String.valueOf(System.currentTimeMillis() + ttl.toMillis()));
        args.add(String.valueOf(count));
        args.add(String.valueOf(totalSeat));
        args.add(adjacent ? "1" : "0");
        args.add(String.valueOf(sessionId));
        for (SeatRuns.Segment run : runs) {
            args.add(String.valueOf(run.startIndex()));
            args.add(String.valueOf(run.length()));
        }

        List<?> result = execute(ALLOCATE_SCRIPT, keys, args);
        if (result == null || result.isEmpty()) {
            log.error("unexpected allocate script result: {}", result);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }

        if (toLong(result.get(0)) != 1L) {
            int longest = result.size() < 2 ? 0 : (int) toLong(result.get(1));
            log.info("could not assign {} seats (adjacent={}): schedule={}, longest run={}",
                    count, adjacent, sessionId, longest);
            return AllocateResult.noRun(longest);
        }

        // 脚本报告的是它拿到的座位，而不是一个起点，恰恰因为在拆分模式下不存在一个
        // 能推出其余座位的起点。
        List<Integer> seatIndexes = new ArrayList<>(result.size() - 1);
        for (int i = 1; i < result.size(); i++) {
            seatIndexes.add((int) toLong(result.get(i)));
        }

        if (seatIndexes.size() != count) {
            log.error("allocate returned {} seats for a request of {}: {}",
                    seatIndexes.size(), count, seatIndexes);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分配结果与请求数量不一致");
        }

        log.debug("seats assigned: schedule={}, order={}, count={}, seats={}",
                sessionId, orderNo, count, seatIndexes);
        return AllocateResult.ok(seatIndexes);
    }

    // ------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------

    /**
     * 当该订单仍然持有它点名的每一个座位时为 true。
     *
     * <p>正是这个检查，让锁令牌在创建订单时有意义。令牌在座位加锁时签发，本身不带
     * 任何有效期，所以单看它自己，在它所指向的持有早已过期、座位已经被别人拿走之后
     * 很久，它依然是"有效"的。
     *
     * <p>它只是把竞争窗口收窄，并没有关掉：一次释放仍然可能落在本次调用和紧随其后的
     * 事务之间。真正阻止超卖的是账本自己的 {@code status = 0} CAS。这个调用存在的意义
     * 在于：那种最现实的情形 —— 页面开过了持有期才提交 —— 会被直接拒掉，而不是让
     * 当前的持有者赔上他的座位。
     */
    public boolean verifyOwnership(Long sessionId, String orderNo, List<Integer> seatIndexes) {
        if (seatIndexes == null || seatIndexes.isEmpty()) {
            return false;
        }

        List<String> keys = List.of(CommonConstants.SEAT_OWNER_KEY + sessionId);
        List<String> args = new ArrayList<>(seatIndexes.size() + 1);
        args.add(orderNo);
        for (Integer index : seatIndexes) {
            args.add(String.valueOf(index));
        }

        Long held = execute(VERIFY_SCRIPT, keys, args);
        boolean ok = held != null && held == 1L;
        if (!ok) {
            log.debug("hold no longer owned by order: schedule={}, order={}", sessionId, orderNo);
        }
        return ok;
    }

    // ------------------------------------------------------------
    // 释放 / 确认
    // ------------------------------------------------------------

    /**
     * 释放某订单持有的座位。
     *
     * <p>幂等，且在座位已经易主之后再跑也是安全的：脚本只会清掉那些 owner 标记仍然
     * 指向本订单的座位。
     *
     * @param force       即使 owner 标记已经不在了也照样释放；只给对账任务用，正常
     *                    流程绝不能传
     * @param includeSold 连本订单已经卖掉的座位一起释放，也就是标记为 {@code SOLD:}
     *                    的那些。只给退款用：退掉的座位要重新回到市场上，没有这个
     *                    参数的话 bit 就一直置着，账本说它空着，实际上却卖不出去。
     * @return 真正被释放掉的座位数
     */
    public int release(Long sessionId, String orderNo, boolean force, boolean includeSold) {
        List<String> keys = List.of(
                CommonConstants.SEAT_MAP_KEY + sessionId,
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo,
                CommonConstants.SOLD_OUT_KEY + sessionId);

        Long released = execute(RELEASE_SCRIPT, keys,
                List.of(orderNo, force ? "1" : "0", includeSold ? "1" : "0"));
        int count = released == null ? 0 : released.intValue();

        if (count > 0) {
            log.debug("seats released: schedule={}, order={}, count={}", sessionId, orderNo, count);
        }
        return count;
    }

    /**
     * 把座位标记为"已售"，而不只是"已锁"。
     *
     * <p>bitmap 上的 bit 保持置位 —— 已售的座位并不比已锁的座位更可选。变的是 owner
     * 标记，好让后续的释放能分辨出已付款的座位和只是被持有的座位。
     */
    public int confirm(Long sessionId, String orderNo) {
        List<String> keys = List.of(
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo);

        Long confirmed = execute(CONFIRM_SCRIPT, keys, List.of(orderNo));
        return confirmed == null ? 0 : confirmed.intValue();
    }

    // ------------------------------------------------------------
    // 超时回收
    // ------------------------------------------------------------

    /** 还有未释放占用的场次。 */
    public Set<String> activeSessions() {
        Set<String> members = redis.opsForSet().members(CommonConstants.SEAT_ACTIVE_KEY);
        return members == null ? Set.of() : members;
    }

    /**
     * 把一个场次从活跃名单里摘掉。
     *
     * <p>只有回收器在遇到一个读不懂的成员时用它 —— 正常情况下场次是自己退场的，
     * 见 {@code seat_sweep.lua}。
     *
     * <p>注意 {@link #activeSessions()} 返回的是 Redis 那一份的拷贝，改它不等于改 Redis。
     * 想删就得真的发一条 SREM，这个方法是干那个的。
     */
    public void forgetActiveSession(String sessionId) {
        redis.opsForSet().remove(CommonConstants.SEAT_ACTIVE_KEY, sessionId);
    }

    /**
     * 某个场次里已经到期的持有。
     *
     * <p>只查不释放。判定和清理是两件事，而清理那条路径上有 owner 校验、要和取消、
     * 退款、G1 回滚共用 —— 它只能有一个实现，就是 {@code seat_release.lua}。
     *
     * <p>返回空列表时，脚本可能顺手把这个场次从活跃名单里摘掉了。
     */
    @SuppressWarnings("unchecked")
    public List<String> findExpiredHolds(Long sessionId, int limit) {
        List<String> keys = List.of(
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ACTIVE_KEY);

        List<?> raw = execute(SWEEP_SCRIPT, keys, List.of(
                String.valueOf(sessionId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(limit)));

        List<String> expired = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                expired.add(String.valueOf(item));
            }
        }
        return expired;
    }

    // ------------------------------------------------------------
    // 读取 / 重建
    // ------------------------------------------------------------

    /** @return 已占用的座位索引，升序 */
    @SuppressWarnings("unchecked")
    public List<Integer> findOccupiedIndexes(Long sessionId, int totalSeats) {
        List<String> keys = List.of(CommonConstants.SEAT_MAP_KEY + sessionId);
        List<?> raw = execute(MAP_QUERY_SCRIPT, keys, List.of(String.valueOf(totalSeats)));

        List<Integer> occupied = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                occupied.add((int) toLong(item));
            }
        }
        return occupied;
    }

    /** 该场次的 bitmap 是否已经建好。 */
    public boolean isInitialised(Long sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(CommonConstants.SEAT_MAP_KEY + sessionId));
    }

    /**
     * 用账本给一张冷 bitmap 播种，过程中绝不清除任何 bit。
     *
     * <p>这是懒加载路径，场次的 bitmap 一缺失就会被走到 —— 新部署、Redis 被清过、
     * 还没人打开过的场次。冷 bitmap 按定义就是被同一时刻的若干个请求一起撞冷的，
     * 所以这段代码必须能安全地与自己并发，也能与分配操作并发。
     *
     * <p>这就是它只置位的原因。更早的版本是建一个临时 key 再 rename 覆盖掉线上那个，
     * 这对读者是原子的，对写者却不是：每一个并发到达的请求都基于一份账本重建，而那份
     * 账本还不知道其他人刚刚发出去的座位，紧接着的 rename 把它们全抹掉了。在一次抢购
     * 中实测过 —— 300 个买家、100 并发 —— 结果是 271 个座位卖进了 240 个位置，其中
     * 21 个座位发给了两个人。bitmap 预热的情况下，同一个测试是把 160 个座位卖进 160
     * 个位置，一个都没重复。
     *
     * <p>合并的代价是：脏掉的 bit 没法用这个办法清掉。这个取舍的方向是对的：什么是
     * 已售，以账本为准，而一个保持占用状态的座位，就是一个不可能被卖两次的座位。
     * 清 bit 属于修复，修复走 {@link #rebuild}。
     */
    public void seed(Long sessionId, List<Integer> occupiedIndexes) {
        if (occupiedIndexes == null || occupiedIndexes.isEmpty()) {
            return;
        }
        String mapKey = CommonConstants.SEAT_MAP_KEY + sessionId;
        for (Integer index : occupiedIndexes) {
            redis.opsForValue().setBit(mapKey, index, true);
        }
        log.info("seat bitmap seeded from ledger: schedule={}, occupied={}",
                sessionId, occupiedIndexes.size());
    }

    /**
     * 用账本重建 bitmap，原有内容一律丢弃。
     *
     * <p>用于修复，不用于冷启动。先写进临时 key 再 rename，所以读的人要么看到旧
     * bitmap、要么看到新 bitmap，永远看不到残缺的 —— 但并发运行的写操作会丢掉自己的
     * bit，所以场次正在售票时不能跑这个。懒加载时该伸手去够的是 {@link #seed}。
     *
     * <p>空的情况被显式处理了。一个什么都还没卖出去的场次根本不会产生任何 SETBIT
     * 调用，于是临时 key 从没被创建，RENAME 就会以 "no such key" 失败 —— 而这恰恰是
     * 最常见的那种场次。写一个值为 0 的 bit 就能把 key 建出来，同时所有座位依然可用。
     */
    public void rebuild(Long sessionId, List<Integer> occupiedIndexes) {
        String tempKey = CommonConstants.SEAT_MAP_KEY + sessionId + ":rebuild";
        String finalKey = CommonConstants.SEAT_MAP_KEY + sessionId;

        redis.delete(tempKey);

        if (occupiedIndexes == null || occupiedIndexes.isEmpty()) {
            redis.opsForValue().setBit(tempKey, 0, false);
        } else {
            for (Integer index : occupiedIndexes) {
                redis.opsForValue().setBit(tempKey, index, true);
            }
        }

        // RENAME 是原子的；不存在任何一个瞬间这个 key 是缺失的。
        redis.rename(tempKey, finalKey);

        int count = occupiedIndexes == null ? 0 : occupiedIndexes.size();
        log.info("seat bitmap rebuilt: schedule={}, occupied={}", sessionId, count);
    }

    /**
     * 重建之后把 owner 标记补回来。
     *
     * <p>每一个被占用的座位都需要一个，已售的、被持有的都一样。owner 标记是释放脚本
     * 拿来比对的依据，所以一个重建出来却没有标记的座位永远释放不掉：释放时看不到
     * owner，判定这个座位不属于该订单，于是把那个 bit 留在原地。以这种方式重建出来的
     * 被持有座位，从被取消的那一刻起就是死的，一直要等到下一次重建。
     *
     * <p>已售的座位会带上 {@code SOLD:} 前缀，好让释放路径能把它们和被持有的座位区分
     * 开，拒绝把一个已付款的座位重新放回售卖。
     *
     * @param owners 座位索引到 owner 标记的映射
     */
    public void markOwners(Long sessionId, Map<String, String> owners) {
        if (owners.isEmpty()) {
            return;
        }
        String ownerKey = CommonConstants.SEAT_OWNER_KEY + sessionId;
        redis.opsForHash().putAll(ownerKey, owners);
    }

    // ------------------------------------------------------------

    /**
     * 执行脚本，把 Redis 故障翻译成一个业务错误。
     *
     * <p>刻意 fail-closed。Redis 不可达时，加锁就无法安全完成，而另一条路 —— 退回
     * 数据库行锁 —— 会把整个选座流量压到 MySQL 上，顺带把数据库一起拖垮。拒掉这个
     * 请求是更好的失败方式。
     */
    private <T> T execute(RedisScript<T> script, List<String> keys, List<String> args) {
        try {
            return redis.execute(script, keys, args.toArray());
        } catch (DataAccessException e) {
            log.error("redis unavailable while executing {}", script.getSha1(), e);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
