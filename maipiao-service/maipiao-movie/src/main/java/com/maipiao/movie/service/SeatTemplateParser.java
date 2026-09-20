package com.maipiao.movie.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.movie.dto.SeatTemplate;
import com.maipiao.movie.entity.ScheduleSeat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a hall's JSON layout into the concrete seat list of a screening.
 *
 * <p>This runs once per schedule, at generation time. Nothing here is on a
 * request path.
 *
 * <p>The {@code seatIndex} it assigns is the Redis bitmap offset and is the
 * single most load-bearing number in the seat-selection design:
 *
 * <ul>
 *   <li>It is <b>contiguous</b>, starting at 0 and incrementing only for
 *       seats that exist. Aisle columns and broken seats are skipped without
 *       consuming an index.</li>
 *   <li>It is <b>never recomputed</b> later. Deriving it at runtime as
 *       {@code (row-1)*cols + (col-1)} would be wrong the moment a hall has an
 *       aisle - the indexes would collide or leave gaps - and the bug would
 *       show up as two users being sold the same seat.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatTemplateParser {

    private final ObjectMapper objectMapper;

    /** A seat that exists, with its permanent bitmap offset. */
    public record SeatSpec(String seatId, int seatIndex, int row, int col, int seatType) {
    }

    public SeatTemplate parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "影厅缺少座位模板");
        }
        try {
            return objectMapper.readValue(json, SeatTemplate.class);
        } catch (JsonProcessingException e) {
            log.error("seat template is not valid JSON: {}", json, e);
            throw new BizException(ErrorCode.PARAM_ERROR, "座位模板格式错误");
        }
    }

    /**
     * Expands the layout into seats, in row-major order.
     *
     * @return seats with contiguous indexes, ready to be inserted as the
     *         screening's seat rows
     */
    public List<SeatSpec> expand(SeatTemplate template) {
        Set<Integer> aisles = new HashSet<>(template.getAisleCols());
        Set<String> broken = new HashSet<>(template.getBrokenSeats());
        Set<String> couple = new HashSet<>();
        for (List<String> pair : template.getCoupleSeats()) {
            couple.addAll(pair);
        }

        List<SeatSpec> seats = new ArrayList<>();
        int index = 0;

        for (int row = 1; row <= template.getRows(); row++) {
            for (int col = 1; col <= template.getCols(); col++) {
                if (aisles.contains(col)) {
                    continue;
                }
                String seatId = row + "_" + col;
                if (broken.contains(seatId)) {
                    continue;
                }
                int seatType = couple.contains(seatId)
                        ? ScheduleSeat.TYPE_COUPLE
                        : ScheduleSeat.TYPE_NORMAL;

                // `index` advances only for seats that exist, which is what
                // keeps the bitmap dense.
                seats.add(new SeatSpec(seatId, index++, row, col, seatType));
            }
        }

        return seats;
    }

    /**
     * Cross-checks the expanded seat count against the hall's stored
     * {@code seat_count} so a mismatch is caught at generation time rather
     * than as a mysterious "the seat map is short by 2 seats" later.
     */
    public void verifyAgainstHall(SeatTemplate template, List<SeatSpec> seats, Integer hallSeatCount) {
        if (hallSeatCount == null) {
            return;
        }
        if (seats.size() != hallSeatCount) {
            log.warn("hall seat_count={} but template expands to {} seats; "
                            + "the template changed without updating the hall row",
                    hallSeatCount, seats.size());
        }
    }
}
