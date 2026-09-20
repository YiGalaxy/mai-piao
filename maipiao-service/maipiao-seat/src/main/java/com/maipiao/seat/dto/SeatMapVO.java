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

    private Long sessionId;

    private String projectTitle;
    private String venueName;
    private String placeName;
    private String placeType;

    private LocalDateTime startTime;
    private BigDecimal price;

    /** Hall geometry, used to lay the grid out including its aisles. */
    private Integer rowCount;
    private Integer colCount;
    private List<Integer> aisleCols;

    private List<SeatItem> seats;

    /**
     * Price bands for this session.
     *
     * <p>A film has exactly one covering every seat; a performance has
     * several, and the client colours the map by them. Sending the list rather
     * than a price per seat keeps the payload small - hundreds of seats
     * sharing three bands would otherwise repeat the same three objects
     * hundreds of times.
     *
     * <p>Empty for a session that predates tiered pricing, which the client
     * treats as "one price for everything".
     */
    private List<TierItem> tiers;

    private Integer totalSeat;
    private Integer remainingSeat;

    /** 1 = rush sale, meaning the client must hold a queue token first. */
    private Integer rushMode;

    /** SEATED / STANDING. Standing sessions have no map to draw. */
    private String seatingMode;

    /** Max tickets per order; 0 means unlimited. */
    private Integer purchaseLimit;

    /** 1 = every ticket must name an attendee. */
    private Integer requireRealName;

    /** When tickets open, for a session that has not opened yet. */
    private LocalDateTime saleStartTime;

    /**
     * A price band.
     *
     * @param id    tier id; seats reference this
     * @param color hex hint used to tint the seats in this band
     */
    public record TierItem(
            Long id,
            String name,
            java.math.BigDecimal price,
            String color
    ) {
    }

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
     * @param tierId    which price band this seat belongs to; resolved from
     *                  {@link #tiers}
     */
    public record SeatItem(
            String seatId,
            int seatIndex,
            int row,
            int col,
            int type,
            int status,
            Long tierId
    ) {
    }
}
