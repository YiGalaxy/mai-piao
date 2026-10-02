package com.maipiao.movie.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.entity.SessionSeat;
import com.maipiao.movie.mapper.SessionMapper;
import com.maipiao.movie.mapper.SessionSeatMapper;
import com.maipiao.movie.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存的服务间接口。不属于公开 API。
 *
 * <p>三个写接口各自是 Seata 全局事务的一个分支。它们无一例外地遵守一条规则：
 * <b>断言受影响的行数</b>。一个什么都没改却返回成功的分支，会让全局事务提交一个
 * 只建了一半的订单，因为 Seata 只回滚那些大声失败的东西。
 *
 * <p>这些路径不由网关路由 —— 它们挂在 {@code /inner} 下，而网关只转发
 * {@code /api/**}。
 */
@Slf4j
@RestController
@RequestMapping("/inner/schedule")
@RequiredArgsConstructor
public class MovieInternalController {

    private final SessionMapper sessionMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final SessionService sessionService;

    /**
     * G1 分支：预占座位并把对应的账本行标为锁定。
     *
     * <p>两次写入，都是有条件的。库存更新是防超卖的关卡；座位行则带着逐座位的关卡。
     * 只要其中任何一个影响的行数不对，异常就会抛出去，Seata 回滚这个事务里已经做完
     * 的一切。
     */
    @PostMapping("/occupy")
    public R<Void> occupy(@RequestParam Long sessionId,
                          @RequestParam String orderNo,
                          @RequestParam Long userId,
                          @RequestParam int count,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                          LocalDateTime expireTime,
                          @RequestParam List<Integer> seatIndexes) {

        int inventoryRows = sessionMapper.occupySeats(sessionId, count);
        if (inventoryRows != 1) {
            // 要么这个场次已经不在售，要么剩下的座位不够。两种情况都意味着「别往下走」。
            throw new BizException(ErrorCode.SCHEDULE_STOCK_NOT_ENOUGH);
        }

        // 按请求给出的座位下标解析，而不是按锁定标记解析。
        //
        // 此刻座位还只在 Redis 里占着，这张表里还没有 —— 下面那次写入才会把它们
        // 落进来。在这里按 lock_order_no 查会查不到，而这正是它替换掉的那个 bug。
        List<String> seatIds = seatIdsByIndex(sessionId, seatIndexes);
        if (seatIds.size() != seatIndexes.size()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "座位信息与场次不匹配");
        }

        int seatRows = sessionSeatMapper.lockSeats(sessionId, seatIds, orderNo, userId, expireTime);
        if (seatRows != seatIds.size()) {
            // 在 Redis 锁定到这里之间，有人抢走了其中某个座位。Redis 的锁是快路径，
            // 这一句才是账本自己的校验；和它对不上，说明两边已经不同步了。
            throw new BizException(ErrorCode.SEAT_OCCUPIED);
        }

        log.debug("inventory reserved: schedule={}, order={}, seats={}", sessionId, orderNo, seatRows);
        return R.ok();
    }

    /** G2 分支：锁定的账本行转为已售。 */
    @PostMapping("/sold")
    public R<Void> sold(@RequestParam Long sessionId,
                        @RequestParam String orderNo,
                        @RequestParam int count) {

        int inventoryRows = sessionMapper.confirmSold(sessionId, count);
        if (inventoryRows != 1) {
            // 脚底下的占位已经被放掉了 —— 超时任务抢在了前面。这时候出票，等于把
            // 没人占着的座位卖出去。
            throw new BizException(ErrorCode.ORDER_EXPIRED);
        }

        List<String> seatIds = seatIdsOf(sessionId, orderNo);
        int seatRows = sessionSeatMapper.markSold(sessionId, seatIds, orderNo);
        if (seatRows != seatIds.size()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "座位状态与订单不一致");
        }

        log.debug("inventory sold: schedule={}, order={}, seats={}", sessionId, orderNo, seatRows);
        return R.ok();
    }

    /**
     * G3 分支：把座位还回去。
     *
     * @param releaseToPool true 表示正常退款（sold -&gt; available）；false 表示
     *                      这些座位只是被占着、从未售出，或者已经被超时任务释放过
     */
    @PostMapping("/release")
    public R<Void> release(@RequestParam Long sessionId,
                           @RequestParam String orderNo,
                           @RequestParam int count,
                           @RequestParam boolean releaseToPool) {

        // releaseToPool 选的其实就是要查哪个归属列：池子里的座位是已售的，
        // 订单号在 sold_order_no；其余是锁定中的，在 lock_order_no。
        List<String> seatIds = seatIdsOf(sessionId, orderNo, releaseToPool);
        if (seatIds.isEmpty()) {
            // 这些座位已经不再属于这个订单了。不是错误：可能是重复的取消请求，
            // 也可能是超时任务先一步处理了。调用方要的是「它们空出来」，而它们空了。
            log.debug("nothing to release: schedule={}, order={}, soldSeats={}",
                    sessionId, orderNo, releaseToPool);
            return R.ok();
        }

        int released = releaseToPool
                ? sessionSeatMapper.releaseSoldSeats(sessionId, seatIds, orderNo)
                : sessionSeatMapper.releaseLockedSeats(sessionId, seatIds, orderNo);

        if (released == 0) {
            // 账本行没等我们就自己往前走了 —— 要么已被释放，要么卖给了别人。
            // 计数器也不能动，否则它描述的会是这个订单从未持有过的座位。
            return R.ok();
        }

        // 动对应的那个计数器，而且只动对应的那个：占位中的座位记在 locked_seat，
        // 已付款的座位记在 sold_seat。
        if (releaseToPool) {
            sessionMapper.releaseSold(sessionId, released);
        } else {
            sessionMapper.releaseLocked(sessionId, released);
        }

        log.debug("inventory released: schedule={}, order={}, released={}, toPool={}",
                sessionId, orderNo, released, releaseToPool);
        return R.ok();
    }

    /**
     * 快照：order-service 用它拼订单行，queue-service 用它确定抢购放多少人进来。
     *
     * <p>{@code totalSeat} 是队列需要的那个值：它的调度器根据还剩多少座位决定放
     * 多少人通过，而这个「还剩」是按 {@code totalSeat - BITCOUNT(seat:map)} 读的。
     * 改用别的方式推这个总量 —— 从 bitmap 推，或者自己维护一个计数器 —— 会为整个
     * 售卖所系的那一个数字造出第二个事实来源。
     */
    @GetMapping("/{sessionId}/snapshot")
    public R<Map<String, Object>> snapshot(@PathVariable Long sessionId) {
        SessionVO detail = sessionService.detail(sessionId);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("sessionId", detail.getId());
        snapshot.put("projectId", detail.getProjectId());
        snapshot.put("projectTitle", detail.getProjectTitle());
        snapshot.put("category", detail.getCategory());
        snapshot.put("venueId", detail.getVenueId());
        snapshot.put("venueName", detail.getVenueName());
        snapshot.put("placeName", detail.getPlaceName());
        snapshot.put("placeType", detail.getPlaceType());
        snapshot.put("showDate", detail.getShowDate());
        snapshot.put("startTime", detail.getStartTime());
        snapshot.put("price", detail.getPrice());
        snapshot.put("totalSeat", detail.getTotalSeat());
        snapshot.put("remainingSeat", detail.getRemainingSeat());
        snapshot.put("status", detail.getStatus());
        snapshot.put("rushMode", detail.getRushMode());
        snapshot.put("rushStartTime", detail.getRushStartTime());
        snapshot.put("saleStartTime", detail.getSaleStartTime());
        return R.ok(snapshot);
    }

    // ------------------------------------------------------------

    /**
     * 一个订单持有的座位 id，从账本的锁定标记读出。
     *
     * <p>出票和释放两条路径用它，它们都在座位已经被占下之后才跑，所以标记是这里该
     * 查的东西。
     *
     * <p>从数据库读而不是从请求里取：信任调用方给的下标到座位 id 的映射，会让订单
     * 被写到与实际占用的座位不同的座位上去。
     */
    private List<String> seatIdsOf(Long sessionId, String orderNo) {
        return seatIdsOf(sessionId, orderNo, false);
    }

    /**
     * 按订单号找出座位。
     *
     * <p>两个归属列，用哪个取决于座位当前处于什么状态，而这不是可以猜的：
     *
     * <ul>
     *   <li>锁定中的座位把订单号写在 {@code lock_order_no}；</li>
     *   <li>已售座位的 {@code lock_order_no} 在出票时被**置空**了，订单号挪到
     *       {@code sold_order_no}。</li>
     * </ul>
     *
     * <p>只查锁定列的话，退款会一条都找不到，然后按「没有需要释放的」返回成功 ——
     * 账本里的座位永远停在已售，售出计数也永远减不下去，而调用方看到的是 200。
     * 这个 bug 的表现是退款成功、钱退了、票还在账上。
     */
    private List<String> seatIdsOf(Long sessionId, String orderNo, boolean soldSeats) {
        return sessionSeatMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<SessionSeat>lambdaQuery()
                                .eq(SessionSeat::getSessionId, sessionId)
                                .eq(soldSeats, SessionSeat::getSoldOrderNo, orderNo)
                                .eq(!soldSeats, SessionSeat::getLockOrderNo, orderNo))
                .stream()
                .map(SessionSeat::getSeatId)
                .toList();
    }

    /**
     * 把 bitmap 偏移量解析成座位 id。
     *
     * <p>occupy 路径用它，那条路径上座位只在 Redis 里占着、这张表里还没打标记 ——
     * 在那里按锁定订单号查会一条都查不到。
     */
    private List<String> seatIdsByIndex(Long sessionId, List<Integer> seatIndexes) {
        return sessionSeatMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<SessionSeat>lambdaQuery()
                                .eq(SessionSeat::getSessionId, sessionId)
                                .in(SessionSeat::getSeatIndex, seatIndexes))
                .stream()
                .map(SessionSeat::getSeatId)
                .toList();
    }
}
