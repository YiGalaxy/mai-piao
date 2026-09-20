package com.maipiao.movie.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.movie.entity.Hall;
import com.maipiao.movie.entity.PriceTier;
import com.maipiao.movie.entity.SessionSeat;
import com.maipiao.movie.mapper.SessionSeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把一个场馆变成某个场次的座位行。
 *
 * <p>演示生成器和后台界面共用，因为两者回答的是同一个问题，而这个答案里有规则。
 * 哪些列是过道、哪些座位是坏的或成对的、一个票档到哪一排结束下一个从哪排开始，
 * 全都出自场馆自己的模板；再写第二份实现必然和这份走偏 —— 两者会对「哪些座位存在」
 * 各执一词，而分歧会表现为卖不出去的座位。
 *
 * <p>只管几何。一个座位是不是一开始就卖掉了是调用方的事，由此产生的任何计数也是。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSeatFactory {

    private static final int BATCH_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 一个座位在场馆里的位置。
     *
     * <p>保留排和列而不是只留一个下标，因为两者不可互换：过道会在列号里留出空档，
     * 所以两个下标相差 1 的座位可能根本不挨着。任何要问「这两个是不是相邻」的地方，
     * 都必须按排和列来问。
     */
    public record SeatPosition(int row, int col, int type) {
    }

    private final SessionSeatMapper sessionSeatMapper;

    /**
     * 一个场馆实际拥有的座位，按它们的编号顺序。
     *
     * <p>从 {@code seat_template} 读，那是场馆对自己的描述。早先的版本忽略了它：
     * 它假设每个厅正好有两条过道，从列数里扣掉，再把剩下的座位塞进 1..n-2 列。
     * 声明出来的过道位置从没进到数据里，于是没有哪一排有空档，而座位的列号和房间里
     * 的任何东西都对不上。
     *
     * <p>下标保持连续 0,1,2,...，因为它们是 bitmap 的偏移量，留空会浪费 bit 并破坏
     * 「下标 N 就是第 N 个座位」这个读法。连续性属于编号，不属于几何 —— 这正是相邻
     * 关系无法从它推导出来的原因。
     */
    public List<SeatPosition> layoutOf(Hall place) {
        if (place.isStanding()) {
            // 没有网格要遵守：一排，每一项就是一个容量单位。这里的 bitmap 是当入场
            // 计数器用的，不是平面图。
            int capacity = Math.max(1, place.getSeatCount() == null ? 0 : place.getSeatCount());
            List<SeatPosition> standing = new ArrayList<>(capacity);
            for (int i = 1; i <= capacity; i++) {
                standing.add(new SeatPosition(1, i, 0));
            }
            return standing;
        }

        JsonNode template = parseTemplate(place.getSeatTemplate());
        int rows = intOr(template, "rows", place.getRowCount());
        int cols = intOr(template, "cols", place.getColCount());

        Set<Integer> aisleCols = new HashSet<>();
        for (JsonNode node : arrayOrEmpty(template, "aisleCols")) {
            aisleCols.add(node.asInt());
        }

        Set<String> broken = new HashSet<>();
        for (JsonNode node : arrayOrEmpty(template, "brokenSeats")) {
            broken.add(node.asText());
        }

        Set<String> couple = new HashSet<>();
        for (JsonNode pair : arrayOrEmpty(template, "coupleSeats")) {
            for (JsonNode seat : pair) {
                couple.add(seat.asText());
            }
        }

        List<SeatPosition> layout = new ArrayList<>(Math.max(1, rows * cols));
        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= cols; col++) {
                if (aisleCols.contains(col)) {
                    continue;
                }
                String key = row + "-" + col;
                if (broken.contains(key)) {
                    continue;
                }
                layout.add(new SeatPosition(row, col, couple.contains(key) ? 1 : 0));
            }
        }

        if (layout.isEmpty()) {
            // 模板什么都没描述出来 —— 要么格式坏了，要么场馆有 seat_count 却没有
            // 能用的网格。退回到声明的数量，这样场次还有库存，不至于空着出来。
            int capacity = Math.max(1, place.getSeatCount() == null ? 0 : place.getSeatCount());
            log.warn("hall {} has an unusable seat template, falling back to {} packed seats",
                    place.getId(), capacity);
            for (int i = 1; i <= capacity; i++) {
                layout.add(new SeatPosition((i - 1) / Math.max(1, cols) + 1,
                        (i - 1) % Math.max(1, cols) + 1, 0));
            }
        }

        return layout;
    }

    /**
     * 一个场次的座位行，场馆布局里每个座位一行。
     *
     * <p>每个座位在这里被分配到唯一一个票档，只分一次。座位图按这个着色、下单按这个
     * 定价，所以它在场次创建时就定下来，而不是每次请求再推导一遍。
     *
     * <p>每个座位初始都是可售的。之后怎样是调用方的事：演示生成器会预卖一部分让座位
     * 图看着有人气，后台那条路径不会。
     */
    public List<SessionSeat> buildSeats(Long sessionId, List<PriceTier> tiers,
                                        List<SeatPosition> layout) {
        List<SessionSeat> seats = new ArrayList<>(layout.size());

        for (int index = 0; index < layout.size(); index++) {
            SeatPosition position = layout.get(index);

            SessionSeat seat = new SessionSeat();
            seat.setId(SnowflakeIdGenerator.next());
            seat.setSessionId(sessionId);
            seat.setSeatIndex(index);
            seat.setRowNum(position.row());
            seat.setColNum(position.col());
            seat.setSeatId(position.row() + "_" + position.col());
            seat.setSeatType(position.type());
            seat.setTierId(tierForRow(tiers, position.row()));
            seat.setStatus(SessionSeat.STATUS_AVAILABLE);
            seat.setVersion(0);
            seats.add(seat);
        }

        return seats;
    }

    /** 分批写入座位。每千条一次 insert，SQL 才不至于失控。 */
    public void insert(List<SessionSeat> seats) {
        for (int from = 0; from < seats.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, seats.size());
            sessionSeatMapper.batchInsert(seats.subList(from, to));
        }
    }

    /**
     * 一排落在哪个票档里。
     *
     * <p>票档是按排区间划分的，场馆就是这么做买卖的 ——「1 到 8 排是 VIP 区」。
     * 先命中者胜，所以调用方传入的顺序说了算；按约定最后一档覆盖到场馆末尾，这也是
     * 没被任何一档命中的排会落到它头上、而不是返回 null 的原因。
     */
    private Long tierForRow(List<PriceTier> tiers, int row) {
        for (PriceTier tier : tiers) {
            if (tier.covers(row)) {
                return tier.getId();
            }
        }
        return tiers.isEmpty() ? null : tiers.get(tiers.size() - 1).getId();
    }

    private JsonNode parseTemplate(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("could not parse seat template, using hall dimensions instead: {}", json, e);
            return MAPPER.createObjectNode();
        }
    }

    private int intOr(JsonNode node, String field, Integer fallback) {
        JsonNode value = node.get(field);
        if (value != null && value.isInt() && value.asInt() > 0) {
            return value.asInt();
        }
        return fallback == null || fallback <= 0 ? 1 : fallback;
    }

    private JsonNode arrayOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isArray() ? value : MAPPER.createArrayNode();
    }
}
