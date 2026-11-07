package com.maipiao.movie.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.entity.SessionSeat;
import com.maipiao.movie.mapper.SessionMapper;
import com.maipiao.movie.mapper.SessionSeatMapper;
import com.maipiao.movie.service.SessionService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存服务间接口；写操作通过影响行数参与 Seata 事务校验。 */
@Slf4j
@RestController
@RequestMapping("/inner/schedule")
@RequiredArgsConstructor
public class MovieInternalController {

    private final SessionMapper sessionMapper;
    private final SessionSeatMapper sessionSeatMapper;
    private final SessionService sessionService;

    /** G1：原子预占库存并锁定对应座位。 */
    @PostMapping("/occupy")
    public R<Void> occupy(
            @RequestParam Long sessionId,
            @RequestParam String orderNo,
            @RequestParam Long userId,
            @RequestParam int count,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime expireTime,
            @RequestParam List<Integer> seatIndexes) {

        int inventoryRows = sessionMapper.occupySeats(sessionId, count);
        if (inventoryRows != 1) {
            // 不在售或库存不足，终止全局事务。
            throw new BizException(ErrorCode.SCHEDULE_STOCK_NOT_ENOUGH);
        }

        // G1 时数据库尚未写入锁定标记，只能按 seatIndex 找座位。
        List<String> seatIds = seatIdsByIndex(sessionId, seatIndexes);
        if (seatIds.size() != seatIndexes.size()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "座位信息与场次不匹配");
        }

        int seatRows = sessionSeatMapper.lockSeats(sessionId, seatIds, orderNo, userId, expireTime);
        if (seatRows != seatIds.size()) {
            // 数据库账本是最终校验，防止 Redis 与数据库不一致。
            throw new BizException(ErrorCode.SEAT_OCCUPIED);
        }

        log.debug(
                "inventory reserved: schedule={}, order={}, seats={}",
                sessionId,
                orderNo,
                seatRows);
        return R.ok();
    }

    /** G2 分支：锁定的账本行转为已售。 */
    @PostMapping("/sold")
    public R<Void> sold(
            @RequestParam Long sessionId, @RequestParam String orderNo, @RequestParam int count) {

        int inventoryRows = sessionMapper.confirmSold(sessionId, count);
        if (inventoryRows != 1) {
            // 占位已被超时任务释放，不能继续出票。
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
     * G3：释放锁定座位或将已售座位退回库存。
     *
     * @param releaseToPool true 表示退款释放已售座位；false 表示释放锁定座位
     */
    @PostMapping("/release")
    public R<Void> release(
            @RequestParam Long sessionId,
            @RequestParam String orderNo,
            @RequestParam int count,
            @RequestParam boolean releaseToPool) {

        // 已售和锁定座位分别使用 sold_order_no、lock_order_no。
        List<String> seatIds = seatIdsOf(sessionId, orderNo, releaseToPool);
        if (seatIds.isEmpty()) {
            // 重复取消或超时任务已先释放，按幂等成功处理。
            log.debug(
                    "nothing to release: schedule={}, order={}, soldSeats={}",
                    sessionId,
                    orderNo,
                    releaseToPool);
            return R.ok();
        }

        int released =
                releaseToPool
                        ? sessionSeatMapper.releaseSoldSeats(sessionId, seatIds, orderNo)
                        : sessionSeatMapper.releaseLockedSeats(sessionId, seatIds, orderNo);

        if (released == 0) {
            // 状态已变化，不能重复扣减库存计数。
            return R.ok();
        }

        // 只调整与座位状态对应的计数器。
        if (releaseToPool) {
            sessionMapper.releaseSold(sessionId, released);
        } else {
            sessionMapper.releaseLocked(sessionId, released);
        }

        log.debug(
                "inventory released: schedule={}, order={}, released={}, toPool={}",
                sessionId,
                orderNo,
                released,
                releaseToPool);
        return R.ok();
    }

    /** 返回订单和队列使用的场次库存快照。 */
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

    /** 从数据库账本读取订单持有的座位，避免信任请求中的座位映射。 */
    private List<String> seatIdsOf(Long sessionId, String orderNo) {
        return seatIdsOf(sessionId, orderNo, false);
    }

    /** 按座位当前状态，从锁定或已售归属列读取座位。 */
    private List<String> seatIdsOf(Long sessionId, String orderNo, boolean soldSeats) {
        return sessionSeatMapper
                .selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<SessionSeat>lambdaQuery()
                                .eq(SessionSeat::getSessionId, sessionId)
                                .eq(soldSeats, SessionSeat::getSoldOrderNo, orderNo)
                                .eq(!soldSeats, SessionSeat::getLockOrderNo, orderNo))
                .stream()
                .map(SessionSeat::getSeatId)
                .toList();
    }

    /** 将 Redis bitmap 偏移量解析为数据库座位 id。 */
    private List<String> seatIdsByIndex(Long sessionId, List<Integer> seatIndexes) {
        return sessionSeatMapper
                .selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<SessionSeat>lambdaQuery()
                                .eq(SessionSeat::getSessionId, sessionId)
                                .in(SessionSeat::getSeatIndex, seatIndexes))
                .stream()
                .map(SessionSeat::getSeatId)
                .toList();
    }
}
