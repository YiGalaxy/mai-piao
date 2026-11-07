package com.maipiao.seat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.dto.ReservationDtos;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.seat.dto.SeatDtos;
import com.maipiao.seat.dto.SeatMapVO;
import com.maipiao.seat.feign.QueueClient;
import com.maipiao.seat.feign.ReservationClient;
import com.maipiao.seat.mapper.SeatQueryMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 组装座位图并通过 Redis Lua 完成锁定、分配和释放。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatMapService {

    private final SeatQueryMapper seatQueryMapper;
    private final SeatBitmapService seatBitmapService;
    private final ObjectMapper objectMapper;
    private final QueueClient queueClient;
    private final ReservationClient reservations;

    /** 由买家自己挑座位的场次。 */
    private static final int SEAT_MODE_SELF = 0;

    /** 由系统按票档发座位的场次。 */
    private static final int SEAT_MODE_ASSIGNED = 1;

    @Value("${maipiao.seat.lock-minutes:15}")
    private int lockMinutes;

    @Value("${maipiao.seat.rebuild-on-miss:true}")
    private boolean rebuildOnMiss;

    public SeatMapVO getSeatMap(Long sessionId) {
        Map<String, Object> schedule = seatQueryMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        int totalSeat = toInt(schedule.get("totalSeat"));

        Set<Integer> occupied = new HashSet<>(loadOccupiedIndexes(sessionId, totalSeat));

        List<Map<String, Object>> layout = seatQueryMapper.selectSeatLayout(sessionId);
        List<SeatMapVO.SeatItem> seats = new ArrayList<>(layout.size());

        for (Map<String, Object> row : layout) {
            int seatIndex = toInt(row.get("seatIndex"));
            seats.add(
                    new SeatMapVO.SeatItem(
                            String.valueOf(row.get("seatId")),
                            seatIndex,
                            toInt(row.get("rowNum")),
                            toInt(row.get("colNum")),
                            toInt(row.get("seatType")),
                            occupied.contains(seatIndex) ? 1 : 0,
                            toLong(row.get("tierId"))));
        }

        SeatMapVO vo = new SeatMapVO();
        vo.setScheduleId(sessionId);
        vo.setProjectTitle(str(schedule.get("projectTitle")));
        vo.setVenueName(str(schedule.get("venueName")));
        vo.setPlaceName(str(schedule.get("placeName")));
        vo.setPlaceType(str(schedule.get("placeType")));
        vo.setStartTime((LocalDateTime) schedule.get("startTime"));
        vo.setPrice((BigDecimal) schedule.get("price"));
        vo.setRowCount(toInt(schedule.get("rowCount")));
        vo.setColCount(toInt(schedule.get("colCount")));
        vo.setAisleCols(parseAisleCols(str(schedule.get("seatTemplate"))));
        vo.setSeats(seats);
        vo.setTiers(loadTiers(sessionId));
        vo.setTotalSeat(totalSeat);
        vo.setRemainingSeat(Math.max(0, totalSeat - occupied.size()));
        vo.setRushMode(toInt(schedule.get("rushMode")));
        vo.setRushStartTime(toDateTime(schedule.get("rushStartTime")));
        vo.setSeatMode(toInt(schedule.get("seatMode")));
        vo.setSeatingMode(str(schedule.get("seatingMode")));
        vo.setPurchaseLimit(toInt(schedule.get("purchaseLimit")));
        vo.setRequireRealName(toInt(schedule.get("requireRealName")));
        vo.setSaleStartTime(toDateTime(schedule.get("saleStartTime")));
        return vo;
    }

    /** 查询场次票档，座位仅引用 tierId。 */
    private List<SeatMapVO.TierItem> loadTiers(Long sessionId) {
        List<Map<String, Object>> rows = seatQueryMapper.selectTiers(sessionId);
        List<SeatMapVO.TierItem> tiers = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            tiers.add(
                    new SeatMapVO.TierItem(
                            toLong(row.get("id")),
                            str(row.get("name")),
                            (BigDecimal) row.get("price"),
                            str(row.get("color"))));
        }
        return tiers;
    }

    /** 读取占用位；bitmap 缺失时从数据库账本播种。 */
    private List<Integer> loadOccupiedIndexes(Long sessionId, int totalSeat) {
        if (seatBitmapService.isInitialised(sessionId)) {
            return seatBitmapService.findOccupiedIndexes(sessionId, totalSeat);
        }

        if (!rebuildOnMiss) {
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE, "座位数据尚未就绪");
        }

        return seedFromLedger(sessionId, totalSeat);
    }

    /** 从账本播种 bitmap 和 owner；只增不删，避免并发购票被覆盖。 */
    private List<Integer> seedFromLedger(Long sessionId, int totalSeat) {
        List<Map<String, Object>> rows = seatQueryMapper.selectOccupiedSeats(sessionId);

        List<Integer> indexes = new ArrayList<>(rows.size());
        Map<String, String> owners = new HashMap<>();

        for (Map<String, Object> row : rows) {
            int index = toInt(row.get("seatIndex"));
            indexes.add(index);

            String orderNo = str(row.get("orderNo"));
            if (orderNo.isEmpty()) {
                // 无主占用不猜归属，保留占用并记录告警。
                log.warn(
                        "occupied seat has no order in the ledger: schedule={}, index={}",
                        sessionId,
                        index);
                continue;
            }

            // status 2 = 已售；status 1 = 被未付款订单锁住
            owners.put(
                    String.valueOf(index),
                    toInt(row.get("status")) == 2 ? "SOLD:" + orderNo : orderNo);
        }

        seatBitmapService.seed(sessionId, indexes);
        seatBitmapService.markOwners(sessionId, owners);

        log.info(
                "seat bitmap rebuilt from ledger: schedule={}, total={}, occupied={}, owned={}",
                sessionId,
                totalSeat,
                indexes.size(),
                owners.size());

        return indexes;
    }

    // ------------------------------------------------------------
    // 加锁
    // ------------------------------------------------------------

    public SeatDtos.LockSeatResponse lockSeats(SeatDtos.LockSeatRequest request, Long userId) {
        Long sessionId = request.scheduleId();
        List<Integer> seatIndexes = request.seatIndexes();

        Map<String, Object> schedule = seatQueryMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        validateSale(schedule, seatIndexes.size());

        // 抢购场次必须走系统分配，避免绕过排队。
        if (toInt(schedule.get("rushMode")) == 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "该场次为抢购场次，请由系统分配座位");
        }

        // 先播种 bitmap，避免把账本中的已售座位当成空位。
        int totalSeat = toInt(schedule.get("totalSeat"));
        loadOccupiedIndexes(sessionId, totalSeat);

        String lockToken = SnowflakeIdGenerator.nextString();
        Duration ttl = Duration.ofMinutes(lockMinutes);
        long expiresAt = System.currentTimeMillis() + ttl.toMillis();

        prepareHold(lockToken, sessionId, userId, expiresAt);
        SeatBitmapService.LockResult result =
                seatBitmapService.lock(sessionId, lockToken, totalSeat, seatIndexes, expiresAt);

        if (!result.success()) {
            String label = labelOfSeat(sessionId, result.conflictSeatIndex());
            log.info("lock rejected: schedule={}, user={}, conflict={}", sessionId, userId, label);
            throw new BizException(ErrorCode.SEAT_OCCUPIED, "座位 " + label + " 已被选走，请重新选择");
        }

        publishHold(sessionId, lockToken, seatIndexes);
        BigDecimal amount = priceOf(sessionId, seatIndexes).total();

        log.info(
                "seats locked: schedule={}, user={}, token={}, count={}, amount={}",
                sessionId,
                userId,
                lockToken,
                seatIndexes.size(),
                amount);

        return new SeatDtos.LockSeatResponse(
                lockToken,
                sessionId,
                seatIndexes,
                labelsOfSeats(sessionId, seatIndexes),
                amount,
                (int) Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000),
                expiresAt);
    }

    /** 为系统分配座位的场次按票档取座，并可要求连座。 */
    public SeatDtos.LockSeatResponse assignSeats(SeatDtos.AssignSeatRequest request, Long userId) {
        Long sessionId = request.scheduleId();
        int quantity = request.quantity();

        Map<String, Object> schedule = seatQueryMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        validateSale(schedule, quantity);
        if (toInt(schedule.get("seatMode")) != SEAT_MODE_ASSIGNED) {
            // 自选场次不能调用系统分配。
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "该场次需要自行选座");
        }

        // 先消费排队准入资格，再占座。
        spendQueueAdmission(schedule, sessionId, userId, request.queueToken());

        int totalSeat = toInt(schedule.get("totalSeat"));

        // 冷 bitmap 先从账本播种。
        loadOccupiedIndexes(sessionId, totalSeat);

        List<SeatRuns.SeatRef> band = seatsOfTier(sessionId, request.tierId());
        if (band.isEmpty()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "票档下没有可用座位");
        }

        // 连座和拆分复用同一份候选段，最终由 Lua 原子取座。
        List<SeatRuns.Segment> runs = SeatRuns.plan(band);
        boolean adjacent = request.wantsAdjacent();

        Duration ttl = Duration.ofMinutes(lockMinutes);
        long expiresAt = System.currentTimeMillis() + ttl.toMillis();
        String lockToken = SnowflakeIdGenerator.nextString();

        prepareHold(lockToken, sessionId, userId, expiresAt);
        SeatBitmapService.AllocateResult result =
                seatBitmapService.allocate(
                        sessionId, lockToken, quantity, totalSeat, runs, adjacent, expiresAt);

        if (!result.success()) {
            if (adjacent) {
                throw new BizException(
                        ErrorCode.SEAT_NOT_ADJACENT,
                        "该票档已无 " + quantity + " 个连座，最多可提供 " + result.longestFreeRun() + " 个连座");
            }
            // 候选座位已耗尽。
            throw new BizException(ErrorCode.SEAT_OCCUPIED, "该票档余票不足");
        }

        List<Integer> seatIndexes = result.seatIndexes();
        publishHold(sessionId, lockToken, seatIndexes);
        BigDecimal amount = priceOf(sessionId, seatIndexes).total();

        log.info(
                "seats assigned: schedule={}, user={}, token={}, tier={}, count={}, amount={}",
                sessionId,
                userId,
                lockToken,
                request.tierId(),
                quantity,
                amount);

        return new SeatDtos.LockSeatResponse(
                lockToken,
                sessionId,
                seatIndexes,
                labelsOfSeats(sessionId, seatIndexes),
                amount,
                (int) Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000),
                expiresAt);
    }

    /** 校验并消费抢购准入 token；队列故障按 fail-closed 处理。 */
    private void spendQueueAdmission(
            Map<String, Object> schedule, Long sessionId, Long userId, String queueToken) {
        if (toInt(schedule.get("rushMode")) != 1) {
            // 普通场次无需排队。
            return;
        }

        if (queueToken == null || queueToken.isBlank()) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "请先排队等候叫号");
        }

        boolean admitted;
        try {
            admitted =
                    Boolean.TRUE.equals(
                            queueClient.consumeToken(sessionId, userId, queueToken).getData());
        } catch (Exception e) {
            log.error(
                    "could not reach the queue, refusing a rush-sale purchase: "
                            + "schedule={}, user={}",
                    sessionId,
                    userId,
                    e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "排队服务暂不可用，请稍后重试");
        }

        if (!admitted) {
            log.info(
                    "rush purchase refused, no valid admission: schedule={}, user={}",
                    sessionId,
                    userId);
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "排队资格已失效，请重新排队");
        }
    }

    /** 某个票档下的座位，表示成连座规划器能排序的位置。 */
    private List<SeatRuns.SeatRef> seatsOfTier(Long sessionId, Long tierId) {
        List<SeatRuns.SeatRef> band = new ArrayList<>();
        for (Map<String, Object> row : seatQueryMapper.selectSeatLayout(sessionId)) {
            Long seatTier = toLong(row.get("tierId"));
            if (seatTier != null && seatTier.equals(tierId)) {
                band.add(
                        new SeatRuns.SeatRef(
                                toInt(row.get("seatIndex")),
                                toInt(row.get("rowNum")),
                                toInt(row.get("colNum"))));
            }
        }
        return band;
    }

    /** 按座位票档计算服务端价格，客户端不得提交价格。 */
    public SeatPricing priceOf(Long sessionId, List<Integer> seatIndexes) {
        List<Map<String, Object>> rows = seatQueryMapper.selectSeatPrices(sessionId, seatIndexes);

        if (rows.size() != seatIndexes.size()) {
            // 请求包含不属于本场次的座位。
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "座位与场次不匹配");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<SeatPricing.Line> lines = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            BigDecimal price =
                    row.get("price") instanceof BigDecimal value ? value : BigDecimal.ZERO;
            lines.add(
                    new SeatPricing.Line(
                            toInt(row.get("seatIndex")), toLong(row.get("tierId")), price));
            total = total.add(price);
        }
        return new SeatPricing(lines, total);
    }

    /** 带票档和单价的座位明细。 */
    public record SeatPricing(List<Line> lines, BigDecimal total) {

        public record Line(int seatIndex, Long tierId, BigDecimal price) {}
    }

    private void prepareHold(String token, Long schedule, Long user, long expiresAt) {
        requireReservationOk(
                reservations.prepare(
                        new ReservationDtos.Prepare(token, schedule, user, expiresAt)));
    }

    private void publishHold(Long schedule, String token, List<Integer> seats) {
        // publish 响应丢失可能已成功；保留占座，交给固定截止时间的清扫恢复，不能盲目释放。
        requireReservationOk(
                reservations.publish(
                        new ReservationDtos.Publish(token, seats, labelsOfSeats(schedule, seats))));
    }

    private void requireReservationOk(R<?> result) {
        if (result == null || !result.isSuccess()) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "预约登记失败，请稍后重新查询");
        }
    }

    /** 用户主动退出需经过预约协调，不能直接清 Redis。 */
    public int abandon(Long sessionId, String token, Long userId) {
        var result = reservations.close(token, sessionId, userId, true);
        requireReservationOk(result);
        if (!Boolean.TRUE.equals(result.getData())) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "正在确认下单或订单已支付，请查询原订单");
        }
        return seatBitmapService.release(sessionId, token, false, false);
    }

    /** 释放订单持有；重复释放返回 0。 */
    public int releaseSeats(Long sessionId, String lockToken, boolean force) {
        return releaseSeats(sessionId, lockToken, force, false);
    }

    /**
     * @param includeSold 退款时传 true；见 release 脚本。
     */
    public int releaseSeats(Long sessionId, String lockToken, boolean force, boolean includeSold) {
        if (lockToken == null || lockToken.isBlank()) {
            return 0;
        }
        return seatBitmapService.release(sessionId, lockToken, force, includeSold);
    }

    /** 由 order-service 在支付成功后调用（G2）。 */
    public void confirmSeats(Long sessionId, String orderNo) {
        seatBitmapService.confirm(sessionId, orderNo);
    }

    /** G1 前校验订单仍持有全部座位。 */
    public boolean verifyOwnership(Long sessionId, String orderNo, List<Integer> seatIndexes) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        return seatBitmapService.verifyOwnership(sessionId, orderNo, seatIndexes);
    }

    // ------------------------------------------------------------

    private List<Integer> parseAisleCols(String seatTemplateJson) {
        if (seatTemplateJson == null || seatTemplateJson.isBlank()) {
            return List.of();
        }
        try {
            var node = objectMapper.readTree(seatTemplateJson).get("aisleCols");
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Integer> cols = new ArrayList<>();
            node.forEach(n -> cols.add(n.asInt()));
            return cols;
        } catch (Exception e) {
            // 一个格式不对的模板不能把座位图搞挂 —— 客户端无非就是渲染时没有过道
            // 空隙而已。
            log.warn("could not parse aisleCols from seat template: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> labelsOfSeats(Long sessionId, List<Integer> seatIndexes) {
        Map<Integer, String> byIndex = seatLabelsByIndex(sessionId);
        List<String> labels = new ArrayList<>(seatIndexes.size());
        for (Integer index : seatIndexes) {
            labels.add(byIndex.getOrDefault(index, String.valueOf(index)));
        }
        return labels;
    }

    private String labelOfSeat(Long sessionId, int seatIndex) {
        return seatLabelsByIndex(sessionId).getOrDefault(seatIndex, String.valueOf(seatIndex));
    }

    /** 为一场放映生成 "{row}排{col}座" 形式的座位标签。 */
    private Map<Integer, String> seatLabelsByIndex(Long sessionId) {
        Map<Integer, String> labels = new java.util.HashMap<>();
        for (Map<String, Object> row : seatQueryMapper.selectSeatLayout(sessionId)) {
            int index = toInt(row.get("seatIndex"));
            labels.put(index, toInt(row.get("rowNum")) + "排" + toInt(row.get("colNum")) + "座");
        }
        return labels;
    }

    private void validateSale(Map<String, Object> schedule, int quantity) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime starts = toDateTime(schedule.get("saleStartTime"));
        LocalDateTime show = toDateTime(schedule.get("startTime"));
        LocalDateTime rush = toDateTime(schedule.get("rushStartTime"));
        if (toInt(schedule.get("status")) != 1
                || (starts != null && now.isBefore(starts))
                || (show != null && !now.isBefore(show))
                || (toInt(schedule.get("rushMode")) == 1 && rush != null && now.isBefore(rush))) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "场次尚未开售或已经停售");
        }
        int limit = toInt(schedule.get("purchaseLimit"));
        if (quantity <= 0 || (limit > 0 && quantity > limit)) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "购买数量不符合该场次每单限购规则");
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /** null 安全，因为座位落在所有票档之外是有可能的，绝不能因此抛异常。 */
    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 容忍 null，也容忍驱动实际返回的类型。
     *
     * <p>数据库里为 null 的列拿到手就是 null，而不是一个 LocalDateTime，直接强转就会 抛异常。这里三个时间戳，null 都是它们的正常取值 ——
     * 大多数场次既不是抢购，也没有 提前排期。
     */
    private LocalDateTime toDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
