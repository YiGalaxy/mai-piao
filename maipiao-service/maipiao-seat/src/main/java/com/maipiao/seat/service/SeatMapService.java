package com.maipiao.seat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.seat.dto.SeatDtos;
import com.maipiao.seat.dto.SeatMapVO;
import com.maipiao.seat.feign.QueueClient;
import com.maipiao.seat.mapper.SeatQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 组装座位图并执行锁座、分配和释放；布局来自账本，可用性来自 Redis bitmap。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatMapService {

    private final SeatQueryMapper seatQueryMapper;
    private final SeatBitmapService seatBitmapService;
    private final ObjectMapper objectMapper;
    private final QueueClient queueClient;

    /** 由买家自己挑座位的场次。 */
    private static final int SEAT_MODE_SELF = 0;

    /** 由系统按票档发座位的场次。 */
    private static final int SEAT_MODE_ASSIGNED = 1;

    @Value("${maipiao.seat.lock-minutes:15}")
    private int lockMinutes;

    @Value("${maipiao.seat.rebuild-on-miss:true}")
    private boolean rebuildOnMiss;

    // ------------------------------------------------------------
    // 座位图
    // ------------------------------------------------------------

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
            seats.add(new SeatMapVO.SeatItem(
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

    /** 返回场次票档；座位通过票档 ID关联价格和颜色。 */
    private List<SeatMapVO.TierItem> loadTiers(Long sessionId) {
        List<Map<String, Object>> rows = seatQueryMapper.selectTiers(sessionId);
        List<SeatMapVO.TierItem> tiers = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            tiers.add(new SeatMapVO.TierItem(
                    toLong(row.get("id")),
                    str(row.get("name")),
                    (BigDecimal) row.get("price"),
                    str(row.get("color"))));
        }
        return tiers;
    }

    /** 查询座位可用性；bitmap 缺失时从账本播种。 */
    private List<Integer> loadOccupiedIndexes(Long sessionId, int totalSeat) {
        if (seatBitmapService.isInitialised(sessionId)) {
            return seatBitmapService.findOccupiedIndexes(sessionId, totalSeat);
        }

        if (!rebuildOnMiss) {
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE, "座位数据尚未就绪");
        }

        return seedFromLedger(sessionId, totalSeat);
    }

    /** 从账本播种 bitmap 和 owner 标记；只增不删，避免并发初始化覆盖新锁座。 */
    private List<Integer> seedFromLedger(Long sessionId, int totalSeat) {
        List<Map<String, Object>> rows = seatQueryMapper.selectOccupiedSeats(sessionId);

        List<Integer> indexes = new ArrayList<>(rows.size());
        Map<String, String> owners = new HashMap<>();

        for (Map<String, Object> row : rows) {
            int index = toInt(row.get("seatIndex"));
            indexes.add(index);

            String orderNo = str(row.get("orderNo"));
            if (orderNo.isEmpty()) {
                // 被占用了却没记是谁占的。这不该发生 —— 账本总会记下其中之一 ——
                // 但把座位留成无主，并不比瞎猜更糟，而且它是看得见的。
                log.warn("occupied seat has no order in the ledger: schedule={}, index={}",
                        sessionId, index);
                continue;
            }

            // status 2 = 已售；status 1 = 被未付款订单锁住
            owners.put(String.valueOf(index),
                    toInt(row.get("status")) == 2 ? "SOLD:" + orderNo : orderNo);
        }

        seatBitmapService.seed(sessionId, indexes);
        seatBitmapService.markOwners(sessionId, owners);

        log.info("seat bitmap rebuilt from ledger: schedule={}, total={}, occupied={}, owned={}",
                sessionId, totalSeat, indexes.size(), owners.size());

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

        int status = toInt(schedule.get("status"));
        if (status != 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE);
        }

        // 抢购场次靠系统分配来卖，绝不走座位图。放自选路径进来，等于谁直接调一次
        // /seat/lock 就能拿到票，整个排队被一个请求绕过去。
        if (toInt(schedule.get("rushMode")) == 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE,
                    "该场次为抢购场次，请由系统分配座位");
        }

        // 先确保 bitmap 存在，再对它加锁，否则锁脚本会心安理得地占下账本上明明写着
        // 已售的那些座位。
        int totalSeat = toInt(schedule.get("totalSeat"));
        loadOccupiedIndexes(sessionId, totalSeat);

        String lockToken = SnowflakeIdGenerator.nextString();
        Duration ttl = Duration.ofMinutes(lockMinutes);

        SeatBitmapService.LockResult result =
                seatBitmapService.lock(sessionId, lockToken, totalSeat, seatIndexes, ttl);

        if (!result.success()) {
            String label = labelOfSeat(sessionId, result.conflictSeatIndex());
            log.info("lock rejected: schedule={}, user={}, conflict={}", sessionId, userId, label);
            throw new BizException(ErrorCode.SEAT_OCCUPIED, "座位 " + label + " 已被选走，请重新选择");
        }

        BigDecimal amount = priceOf(sessionId, seatIndexes).total();

        log.info("seats locked: schedule={}, user={}, token={}, count={}, amount={}",
                sessionId, userId, lockToken, seatIndexes.size(), amount);

        return new SeatDtos.LockSeatResponse(
                lockToken,
                sessionId,
                seatIndexes,
                labelsOfSeats(sessionId, seatIndexes),
                amount,
                (int) ttl.getSeconds());
    }

    /** 为系统分配座位的场次分配并锁定座位；连座不足时返回可用的最长连座数。 */
    public SeatDtos.LockSeatResponse assignSeats(SeatDtos.AssignSeatRequest request, Long userId) {
        Long sessionId = request.scheduleId();
        int quantity = request.quantity();

        Map<String, Object> schedule = seatQueryMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        if (toInt(schedule.get("status")) != 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE);
        }
        if (toInt(schedule.get("seatMode")) != SEAT_MODE_ASSIGNED) {
            // 自选场次没有票档可供分配；来问就是客户端的错，而替它猜想要哪些座位，
            // 比直接说清楚更糟。
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "该场次需要自行选座");
        }

        // 在拿走任何东西之前执行，针对需要排队购票的场次。
        spendQueueAdmission(schedule, sessionId, userId, request.queueToken());

        int totalSeat = toInt(schedule.get("totalSeat"));

        // bitmap 是冷的话，先用账本把它建起来。跳过这一步就会发出账本早已认定为已售的
        // 座位 —— 脚本只看得到 bit，而在一个没建过的 bitmap 上，每一个 bit 读出来都是
        // 空的。
        loadOccupiedIndexes(sessionId, totalSeat);

        Integer limit = toInt(schedule.get("purchaseLimit"));
        if (limit > 0 && quantity > limit) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID,
                    "该场次每单最多购买 " + limit + " 张");
        }

        List<SeatRuns.SeatRef> band = seatsOfTier(sessionId, request.tierId());
        if (band.isEmpty()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "票档下没有可用座位");
        }

        // 相邻连座是对买家的承诺，所以先给它们排序、先拿它们。拆分那一趟复用完全相同
        // 的连座列表，只是让脚本忽略段边界、直接取最先碰到的空位 —— 同样的候选、
        // 同样的原子取座，也不会多出第二条可能对"哪些座位是空的"产生分歧的代码路径。
        List<SeatRuns.Segment> runs = SeatRuns.plan(band);
        boolean adjacent = request.wantsAdjacent();

        Duration ttl = Duration.ofMinutes(lockMinutes);
        String lockToken = SnowflakeIdGenerator.nextString();

        SeatBitmapService.AllocateResult result = seatBitmapService.allocate(
                sessionId, lockToken, quantity, totalSeat, runs, adjacent, ttl);

        if (!result.success()) {
            if (adjacent) {
                throw new BizException(ErrorCode.SEAT_NOT_ADJACENT,
                        "该票档已无 " + quantity + " 个连座，最多可提供 "
                                + result.longestFreeRun() + " 个连座");
            }
            // 拆分取座也用光了，所以这是库存问题，不是几何问题。
            throw new BizException(ErrorCode.SEAT_OCCUPIED, "该票档余票不足");
        }

        List<Integer> seatIndexes = result.seatIndexes();
        BigDecimal amount = priceOf(sessionId, seatIndexes).total();

        log.info("seats assigned: schedule={}, user={}, token={}, tier={}, count={}, amount={}",
                sessionId, userId, lockToken, request.tierId(), quantity, amount);

        return new SeatDtos.LockSeatResponse(
                lockToken,
                sessionId,
                seatIndexes,
                labelsOfSeats(sessionId, seatIndexes),
                amount,
                (int) ttl.getSeconds());
    }

    /** 校验并消费排队令牌；队列服务不可达时拒绝请求。 */
    private void spendQueueAdmission(Map<String, Object> schedule, Long sessionId,
                                     Long userId, String queueToken) {
        if (toInt(schedule.get("rushMode")) != 1) {
            // 普通场次：没有队可排。
            return;
        }

        if (queueToken == null || queueToken.isBlank()) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "请先排队等候叫号");
        }

        boolean admitted;
        try {
            admitted = Boolean.TRUE.equals(
                    queueClient.consumeToken(sessionId, userId, queueToken).getData());
        } catch (Exception e) {
            log.error("could not reach the queue, refusing a rush-sale purchase: "
                    + "schedule={}, user={}", sessionId, userId, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "排队服务暂不可用，请稍后重试");
        }

        if (!admitted) {
            log.info("rush purchase refused, no valid admission: schedule={}, user={}",
                    sessionId, userId);
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "排队资格已失效，请重新排队");
        }
    }

    /** 某个票档下的座位，表示成连座规划器能排序的位置。 */
    private List<SeatRuns.SeatRef> seatsOfTier(Long sessionId, Long tierId) {
        List<SeatRuns.SeatRef> band = new ArrayList<>();
        for (Map<String, Object> row : seatQueryMapper.selectSeatLayout(sessionId)) {
            Long seatTier = toLong(row.get("tierId"));
            if (seatTier != null && seatTier.equals(tierId)) {
                band.add(new SeatRuns.SeatRef(toInt(row.get("seatIndex")),
                        toInt(row.get("rowNum")), toInt(row.get("colNum"))));
            }
        }
        return band;
    }

    /** 按座位所属票档计算明细和总价；价格完全由服务端解析。 */
    public SeatPricing priceOf(Long sessionId, List<Integer> seatIndexes) {
        List<Map<String, Object>> rows = seatQueryMapper.selectSeatPrices(sessionId, seatIndexes);

        if (rows.size() != seatIndexes.size()) {
            // 有请求的座位不属于本场次。给一笔订单只算一部分的价，会得出一个顾客从未
            // 同意过的总价。
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "座位与场次不匹配");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<SeatPricing.Line> lines = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            BigDecimal price = row.get("price") instanceof BigDecimal value
                    ? value : BigDecimal.ZERO;
            lines.add(new SeatPricing.Line(toInt(row.get("seatIndex")),
                    toLong(row.get("tierId")), price));
            total = total.add(price);
        }
        return new SeatPricing(lines, total);
    }

    /** 已解析票档和价格的座位明细。 */
    public record SeatPricing(List<Line> lines, BigDecimal total) {

        public record Line(int seatIndex, Long tierId, BigDecimal price) {
        }
    }

    /**
     * 释放一个持有。
     *
     * <p>从两个地方调用：用户从支付页退出，以及 order-service 对失败的 G1 做补偿。
     * 两者传的是同一个标识 —— 锁令牌，它同时也是订单号 —— 于是只有一条代码路径，也只有
     * 一种"谁拥有这个座位"的说法。
     *
     * @return 真正被释放的座位数；0 表示它们早已被释放或已售出，这不是错误
     */
    public int releaseSeats(Long sessionId, String lockToken, boolean force) {
        return releaseSeats(sessionId, lockToken, force, false);
    }

    /** @param includeSold 退款时传 true；见 release 脚本。 */
    public int releaseSeats(Long sessionId, String lockToken, boolean force,
                            boolean includeSold) {
        if (lockToken == null || lockToken.isBlank()) {
            return 0;
        }
        return seatBitmapService.release(sessionId, lockToken, force, includeSold);
    }

    /** 由 order-service 在支付成功后调用（G2）。 */
    public void confirmSeats(Long sessionId, String orderNo) {
        seatBitmapService.confirm(sessionId, orderNo);
    }

    /**
     * 由 order-service 在开启 G1 之前调用。
     *
     * <p>锁令牌是一个不带任何有效期的普通标识，所以单看它自己，在它背后的持有早已过期
     * 之后，它依然可用。在这里问一声，才能把它重新变回"持有关系"的证据。
     */
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
     * <p>数据库里为 null 的列拿到手就是 null，而不是一个 LocalDateTime，直接强转就会
     * 抛异常。这里三个时间戳，null 都是它们的正常取值 —— 大多数场次既不是抢购，也没有
     * 提前排期。
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
