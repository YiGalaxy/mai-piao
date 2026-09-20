package com.maipiao.seat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a price band's seats into runs of physically adjacent seats.
 *
 * <p>A run is what the allocator is allowed to hand out as one booking, so
 * "adjacent" has to mean adjacent in the room and not merely next in the
 * numbering. Those are different things and the difference is not exotic: with
 * a two-column aisle, index 2 and index 3 are columns 4 and 6 of the same row,
 * and a booking of two given those seats puts the pair on either side of a
 * walkway.
 *
 * <p>So a run breaks on either discontinuity - the index jumping, or the
 * column jumping by more than one. The index check catches seats removed from
 * the ledger; the column check catches aisles. Neither alone is sufficient,
 * and the column check is the one that cannot be derived from the index, which
 * is why adjacency is read from the geometry rather than computed.
 *
 * <p>Runs never cross a row. Seats at the end of one row and the start of the
 * next are adjacent in neither sense, and the concourse between them is
 * wider than any aisle.
 *
 * <p>Pure: no Spring, no database, no Redis. It takes positions and returns
 * ranges, so the rule above can be tested directly instead of inferred from
 * the behaviour of a live allocation.
 */
final class SeatRuns {

    /** A maximal sequence of seats that are next to each other in the room. */
    record Segment(int startIndex, int length) {

        int endIndex() {
            return startIndex + length - 1;
        }
    }

    /** One seat's place in the band. */
    record SeatRef(int seatIndex, int row, int col) {
    }

    private SeatRuns() {
    }

    /**
     * Runs for one band, in the order seats should be handed out.
     *
     * <p>Rows ascending, then position within the row ascending: front of the
     * house first, which is what a venue does and what a buyer expects. The
     * ranking is a product choice and it lives here alone - the allocator
     * simply tries the runs in the order it is given.
     */
    static List<Segment> plan(List<SeatRef> seats) {
        if (seats == null || seats.isEmpty()) {
            return List.of();
        }

        List<SeatRef> ordered = new ArrayList<>(seats);
        ordered.sort(Comparator.comparingInt(SeatRef::row).thenComparingInt(SeatRef::col));

        List<Segment> segments = new ArrayList<>();
        int start = ordered.get(0).seatIndex();
        int length = 1;
        SeatRef previous = ordered.get(0);

        for (int i = 1; i < ordered.size(); i++) {
            SeatRef current = ordered.get(i);

            boolean nextInRow = current.row() == previous.row();
            boolean indexAdjacent = current.seatIndex() == previous.seatIndex() + 1;
            boolean colAdjacent = current.col() == previous.col() + 1;

            if (nextInRow && indexAdjacent && colAdjacent) {
                length++;
            } else {
                segments.add(new Segment(start, length));
                start = current.seatIndex();
                length = 1;
            }
            previous = current;
        }
        segments.add(new Segment(start, length));

        return segments;
    }

    /**
     * The longest run in a set of segments.
     *
     * <p>Used to answer "how many could you seat together" when a request for
     * more fails. Reported from the same list the allocator searched, so the
     * number it quotes is one the allocator would actually honour.
     */
    static int longest(List<Segment> segments) {
        int longest = 0;
        for (Segment segment : segments) {
            longest = Math.max(longest, segment.length());
        }
        return longest;
    }
}
