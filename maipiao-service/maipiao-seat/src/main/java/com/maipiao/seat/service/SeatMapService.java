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

/**
 * 座位图的组装与加锁。
 *
 * <p>两个来源合到一起：布局（有哪些座位、分别在哪）来自账本；可用性来自 Redis 的
 * bitmap。
 *
 * <p>关于锁令牌：加锁成功后返回的值是一个雪花 id，它同时就是订单服务将要使用的订单号。
 * 这是刻意做的简化 —— 另一种做法是让 order-service 自己生成一个单号，再请 seat-service
 * 把持有关系从令牌转移到订单上，那是多一次往返、多一种失败方式，在当前的规模下换不来
 * 任何好处。如果哪天这两者真的需要不一样，该做的就是那次转移。
 */
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

    /**
     * 场次的票档。
     *
     * <p>电影恰好只有一个，覆盖全部座位；演出有多个，座位图按票档着色。发这个列表
     * 而不是每个座位带一个价格，是为了让响应体小一些 —— 几百个座位共用三个票档，
     * 否则同样三个值要重复几百遍。
     */
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

    /**
     * 取可用性；bitmap 缺失时用账本把它重建出来。
     *
     * <p>冷 bitmap 不是错误状态 —— 刚部署的实例、被清过的 Redis、还没人打开过的
     * 场次，看起来就是这样。在首次读取时重建，意味着不存在一个会被忘掉的独立预热
     * 步骤，也不存在一段场次显示为全部空着的窗口。
     */
    private List<Integer> loadOccupiedIndexes(Long sessionId, int totalSeat) {
        if (seatBitmapService.isInitialised(sessionId)) {
            return seatBitmapService.findOccupiedIndexes(sessionId, totalSeat);
        }

        if (!rebuildOnMiss) {
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE, "座位数据尚未就绪");
        }

        return seedFromLedger(sessionId, totalSeat);
    }

    /**
     * 用 {@code t_event_session_seat} 给 bitmap 和 owner 标记播种。
     *
     * <p>是播种而不是重建：只要 bitmap 缺失就会走到这里，而这按构造就意味着会有若干个
     * 请求同时到达。在这里做一次破坏性的重建，会把它们正并发发出去的座位一起丢掉 ——
     * 实测 300 个买家、100 并发，它把 271 笔售出变成了 240 个占用座位，其中 21 个
     * 各自发给了两个人。
     *
     * <p>owner 标记和 bit 同样重要，两种占用状态都是如此。释放脚本拒绝清除 owner 与
     * 请求方对不上的座位，所以一个播种时没带上 owner 的座位，谁也没法释放它 —— bit
     * 一直置着，这个座位就是死的。这对已售和被持有的座位同样成立，而被持有才是更常见
     * 的情况：一个开了一段时间的场次里，未付款的持有通常比已售的还多。
     *
     * <p>已售座位标成 {@code SOLD:}，好让释放路径拒绝把一个付过钱的座位放回售卖；
     * 被持有的座位就原样带着订单号，这正是它们还能被释放的原因。
     */
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

    /**
     * 为不让买家自己挑座的场次发座位。
     *
     * <p>买家只报一个票档和一个数量；座位在这里选。结果和 {@link #lockSeats} 一模一样
     * —— 一个持有、一个令牌、一个价格 —— 所以它下游的一切，从订单事务到退款，都不用改。
     * 加锁和分配只是通往"一个被持有的座位"的两条路，不是两种座位。
     *
     * <p>凑不出所需长度的连座时宁可拒绝，也不把同行的人拆开。"两个座位"和"两个挨着的
     * 座位"是两个不同的承诺，悄悄拿一个顶替另一个，是那种顾客到了场馆才发现的事。调用方
     * 会被告知最多能有几个人坐在一起，好据此给出一个真实的选择。
     */
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

    /**
     * 为有排队的场次，花掉调用方的排队资格。
     *
     * <p>这里是真正的执行点，而且刻意放在这里、而不是只放在网关。网关从 query string 里
     * 读场次 id —— 那是调用方自己给的值 —— 所以一个谎报自己买的是哪一场的调用方就能绕
     * 过去。在这里，场次 id 就是本请求真正对应的那个，令牌也是拿由它派生出来的 key 去
     * 校验的。
     *
     * <p>是消费掉，不是仅仅校验一下：一次用过还能继续用的准入资格，会让一个排队位置反复
     * 购买。
     *
     * <p>queue-service 不可达时 fail-closed。一次故障不能被解读成"所有人都放行" —— 那等于
     * 恰好在它本该吸收的负载最高的时候，把队列关掉。
     */
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

    /**
     * 一组座位值多少钱，逐座算以及合计。
     *
     * <p>按每个座位所在的票档算，而不是用场次的挂牌价。一场演出会同时卖好几个票档，
     * 唯一正确的总价就是实际拿到的那几个座位各自价格之和 —— {@code schedule.price}
     * 是"¥580 起"那种宣传价，拿它去收一个 1880 的 VIP 座位，是实打实的少收钱，而不是
     * 一点零头差异。
     *
     * <p>之所以暴露出来，是为了让校验调用能在返回持有状态的同时把价格一起带回去。
     * 客户端从不发送价格，也没有价格可发：它是这里根据服务端自己解析出来的座位算出来
     * 的。
     */
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

    /**
     * 一组已定价的座位。
     *
     * <p>给明细行而不是只给一个总额，是因为订单要记下每张票是按哪个票档卖的 —— 退款
     * 必须退回那个座位实际花掉的钱，而这是没法从一个均价里还原出来的。
     */
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
