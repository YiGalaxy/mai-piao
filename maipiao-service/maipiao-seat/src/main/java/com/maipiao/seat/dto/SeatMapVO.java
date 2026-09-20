package com.maipiao.seat.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the seat-selection page needs, in one response.
 *
 * <p>Layout (rows, columns, aisles) comes from the hall's template; seat state
 * comes from Redis. Sending them together avoids a second round trip at the
 * one moment the user is waiting.
 */
@Data
public class SeatMapVO {

    private Long scheduleId;

    private String filmName;
    private String cinemaName;
    private String hallName;
    private String hallType;

    private LocalDateTime startTime;
    private BigDecimal price;

    /** Hall geometry, used to lay the grid out including its aisles. */
    private Integer rowCount;
    private Integer colCount;
    private List<Integer> aisleCols;

    private List<SeatItem> seats;

    private Integer totalSeat;
    private Integer remainingSeat;

    /** 1 = rush sale, meaning the client must hold a queue token first. */
    private Integer rushMode;

    /**
     * One seat on the map.
     *
     * @param seatId    "{row}_{col}", stable and human readable
     * @param seatIndex bitmap offset; what the lock call sends back
     * @param type      0 normal, 1 couple, 2 accessible
     * @param status    0 available, 1 taken (locked or sold - the client does
     *                  not need to distinguish, and should not, since telling
     *                  a buyer "someone has this in their cart" invites
     *                  refreshing until it frees up)
     */
    public record SeatItem(
            String seatId,
            int seatIndex,
            int row,
            int col,
            int type,
            int status
    ) {
    }
}
