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

/** 座位 bitmap 的唯一写入口；状态变更通过 Lua 保证原子性。 */
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
                CommonConstants.SOLD_OUT_KEY + sessionId);

        List<String> args = new ArrayList<>(seatIndexes.size() + 3);
        args.add(orderNo);
        args.add(String.valueOf(System.currentTimeMillis() + ttl.toMillis()));
        args.add(String.valueOf(totalSeat));
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

    /** 按候选连座段分配座位；Lua 会再次校验 bitmap，避免快照过期导致超卖。 */
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
                CommonConstants.SOLD_OUT_KEY + sessionId);

        List<String> args = new ArrayList<>(5 + runs.size() * 2);
        args.add(orderNo);
        args.add(String.valueOf(System.currentTimeMillis() + ttl.toMillis()));
        args.add(String.valueOf(count));
        args.add(String.valueOf(totalSeat));
        args.add(adjacent ? "1" : "0");
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

    /** 校验订单是否仍持有全部指定座位。最终库存一致性由账本 CAS 保证。 */
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

    /** 释放订单座位；脚本按 owner 校验保证幂等。{@code includeSold} 仅用于退款。 */
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

    /** 将订单持有标记转换为已售标记，bitmap bit 保持置位。 */
    public int confirm(Long sessionId, String orderNo) {
        List<String> keys = List.of(
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo);

        Long confirmed = execute(CONFIRM_SCRIPT, keys, List.of(orderNo));
        return confirmed == null ? 0 : confirmed.intValue();
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

    /** 从账本播种缺失 bitmap，只置位不清除；清理由 {@link #rebuild} 完成。 */
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

    /** 用账本重建 bitmap；通过临时 key + RENAME 原子替换，仅用于修复。 */
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

    /** 补写 bitmap 对应的 owner 标记；已售标记使用 {@code SOLD:} 前缀。 */
    public void markOwners(Long sessionId, Map<String, String> owners) {
        if (owners.isEmpty()) {
            return;
        }
        String ownerKey = CommonConstants.SEAT_OWNER_KEY + sessionId;
        redis.opsForHash().putAll(ownerKey, owners);
    }

    // ------------------------------------------------------------

    /** 执行 Redis 脚本；Redis 不可用时拒绝请求。 */
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
