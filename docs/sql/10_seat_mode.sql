-- ============================================================
-- Seat mode: who picks the seat.
--
-- Two different axes were being conflated:
--
--   t_event_place.seating_mode   does this venue HAVE fixed seats?
--                                SEATED / STANDING / MIXED
--   t_event_session.seat_mode    may the buyer CHOOSE their seat?
--                                0 = SEAT_SELECT, 1 = AUTO_ASSIGN
--
-- A seated stadium sells by assignment; a standing area sells by assignment
-- too, for a different reason. Same sale behaviour, different venue fact, so
-- they cannot share a column. It is also a per-session decision: one venue
-- can run a pick-your-seat show and an assigned-seat show.
--
-- Films and small theatres stay 0. Large concerts become 1, where the buyer
-- picks a price band and a quantity and the system hands out adjacent seats
-- from that band.
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_event;

ALTER TABLE t_event_session
  ADD COLUMN seat_mode TINYINT NOT NULL DEFAULT 0
    COMMENT '0=buyer picks seats, 1=system assigns adjacent seats';

-- ------------------------------------------------------------
-- Which price band a ticket was sold at.
--
-- t_order_item.price was derived as "order total / seat count", which is only
-- correct when every seat costs the same. A concert with a 1880 VIP band next
-- to a 580 stand band is the normal case, not the exception, and a refund has
-- to give back what that seat actually cost - which means the band has to be
-- recorded on the line, not reconstructed later.
-- ------------------------------------------------------------

USE maipiao_order;

ALTER TABLE t_order_item
  ADD COLUMN tier_id BIGINT NULL COMMENT 'price band the seat was sold at';
