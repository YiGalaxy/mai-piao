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

/** 库存服务间接口；写操作通过影响行数参与 Seata 分支校验。 */
@Slf4j
@RestController
@RequestMapping("/inner/schedule")
@RequiredArgsConstructor
public class MovieInternalController {

    private final SessionMapper sessionMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final SessionService sessionService;

    /** G1 分支：预占库存并锁定对应座位。 */
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

    /** G3 分支：释放锁定或已售座位。 */
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

    /** 返回订单快照及队列计算所需的座位总量。 */
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

    /** 从账本归属标记查询订单座位。 */
    private List<String> seatIdsOf(Long sessionId, String orderNo) {
        return seatIdsOf(sessionId, orderNo, false);
    }

    /** 按座位状态选择 lock_order_no 或 sold_order_no 查询。 */
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
