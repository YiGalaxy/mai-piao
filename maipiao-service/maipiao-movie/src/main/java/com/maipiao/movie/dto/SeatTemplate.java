package com.maipiao.movie.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Java view of the JSON stored in {@code t_event_place.seat_template}.
 *
 * <pre>
 * {
 *   "rows": 14,
 *   "cols": 16,
 *   "aisleCols": [5, 12],
 *   "brokenSeats": ["1-1", "1-16", "14-1", "14-16"],
 *   "coupleSeats": [["7-8", "7-9"], ["8-8", "8-9"]]
 * }
 * </pre>
 */
@Data
public class SeatTemplate {

    private int rows;

    private int cols;

    /** Columns with no seats at all - the walkways. 1-based. */
    private List<Integer> aisleCols = new ArrayList<>();

    /** Individually unusable seats, as "{row}-{col}". */
    private List<String> brokenSeats = new ArrayList<>();

    /** Seats sold as a pair; each entry is the two "{row}-{col}" ids. */
    private List<List<String>> coupleSeats = new ArrayList<>();
}
