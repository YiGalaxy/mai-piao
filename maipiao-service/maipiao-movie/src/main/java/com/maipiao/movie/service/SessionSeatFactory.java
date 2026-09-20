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
 * Turns a hall into the seat rows of one session.
 *
 * <p>Shared by the demo generator and the admin screen, because both are
 * answering the same question and the answer has rules in it. Which columns
 * are walkways, which seats are broken or paired, and where one price band
 * ends and the next begins all come out of the hall's own template, and a
 * second implementation of that would drift from this one - the two would
 * disagree about which seats exist, and the disagreement would show up as
 * seats that cannot be sold.
 *
 * <p>Geometry only. Whether a seat starts out sold is the caller's business,
 * and so is any counter that follows from it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSeatFactory {

    private static final int BATCH_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One seat's place in the hall.
     *
     * <p>Row and column are kept rather than a bare index because they are not
     * interchangeable: an aisle puts a gap in the column numbers, so two seats
     * whose indexes differ by one may be nowhere near each other. Anything
     * asking "are these adjacent" has to ask in rows and columns.
     */
    public record SeatPosition(int row, int col, int type) {
    }

    private final SessionSeatMapper sessionSeatMapper;

    /**
     * The seats a hall actually has, in the order they are numbered.
     *
     * <p>Read from {@code seat_template}, which is the venue's own description
     * of itself. An earlier version ignored it: it assumed every hall has
     * exactly two aisles, deducted them from the column count, and packed the
     * remaining seats into columns 1..n-2. The declared aisle positions never
     * reached the data, so no row had a gap, and a seat's column number did
     * not correspond to anything in the room.
     *
     * <p>Indexes stay contiguous 0,1,2,... because they are bitmap offsets and
     * a gap would waste bits and break the "index N is the Nth seat" reading.
     * Contiguity is a property of the numbering, not of the geometry - which
     * is exactly why adjacency cannot be derived from it.
     */
    public List<SeatPosition> layoutOf(Hall place) {
        if (place.isStanding()) {
            // No grid to honour: one row, one unit of capacity each. The bitmap
            // is used as an admission counter here, not as a floor plan.
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
            // A template that describes nothing - malformed, or a hall with a
            // seat_count but no usable grid. Fall back to the declared count so
            // the session still has inventory rather than coming out empty.
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
     * Seat rows for one session, one per seat of the hall's layout.
     *
     * <p>Each seat is assigned to exactly one band, once, here. That assignment
     * is what the seat map colours by and what the order prices by, so it is
     * resolved when the session is created rather than derived per request.
     *
     * <p>Every seat starts available. What happens next is the caller's: the
     * demo generator pre-sells a share of them so maps look lived-in, and the
     * admin path does not.
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

    /** Writes the seats in batches. One insert per thousand keeps the SQL sane. */
    public void insert(List<SessionSeat> seats) {
        for (int from = 0; from < seats.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, seats.size());
            sessionSeatMapper.batchInsert(seats.subList(from, to));
        }
    }

    /**
     * Which band a row falls in.
     *
     * <p>Bands are row ranges, which is how a venue sells them - "rows 1 to 8
     * are the VIP block". The first match wins, so the order the caller passes
     * them in decides; the last band covers to the end of the hall by
     * convention, which is why an unmatched row falls through to it rather
     * than coming back null.
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
