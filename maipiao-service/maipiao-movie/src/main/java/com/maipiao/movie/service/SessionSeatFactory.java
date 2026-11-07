package com.maipiao.movie.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.movie.entity.Hall;
import com.maipiao.movie.entity.PriceTier;
import com.maipiao.movie.entity.SessionSeat;
import com.maipiao.movie.mapper.SessionSeatMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 根据场馆模板生成场次座位快照；只负责布局和票档归属。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSeatFactory {

    private static final int BATCH_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 场馆中的座位位置；相邻判断必须使用排列坐标。 */
    public record SeatPosition(int row, int col, int type) {}

    private final SessionSeatMapper sessionSeatMapper;

    /** 按模板生成真实座位；bitmap 下标连续，排列坐标保留过道信息。 */
    public List<SeatPosition> layoutOf(Hall place) {
        if (place.isStanding()) {
            // 无网格时按容量生成一排座位。
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
            // 模板无有效座位时按声明容量兜底。
            int capacity = Math.max(1, place.getSeatCount() == null ? 0 : place.getSeatCount());
            log.warn(
                    "hall {} has an unusable seat template, falling back to {} packed seats",
                    place.getId(),
                    capacity);
            for (int i = 1; i <= capacity; i++) {
                layout.add(
                        new SeatPosition(
                                (i - 1) / Math.max(1, cols) + 1,
                                (i - 1) % Math.max(1, cols) + 1,
                                0));
            }
        }

        return layout;
    }

    /** 生成场次座位行并绑定票档；初始状态为可售。 */
    public List<SessionSeat> buildSeats(
            Long sessionId, List<PriceTier> tiers, List<SeatPosition> layout) {
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

    /** 根据排号匹配票档区间；按传入顺序取首个命中项。 */
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
