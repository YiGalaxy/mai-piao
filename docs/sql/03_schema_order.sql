-- ============================================================
-- maoyan_order : order-service's private schema
--
-- Order status machine (see docs/design-decisions.md):
--   0 PENDING_PAY    order created, no payment initiated yet
--   1 PAYING         payment order created, waiting for the channel callback
--   2 PAID           payment succeeded, tickets issued
--   3 COMPLETED      screening finished
--   4 CANCELLED      timed out or cancelled by the user
--   5 REFUNDING      refund requested, waiting for the channel
--   6 REFUNDED       refund succeeded
--
-- EVERY transition goes through one CAS statement:
--   UPDATE t_order_order SET status=?
--    WHERE order_no=? AND status IN (...)
-- and MUST assert affected rows = 1.
-- A bare `UPDATE t_order_order SET status=...` with no status guard
-- is forbidden anywhere in the codebase.
-- ============================================================

CREATE DATABASE IF NOT EXISTS maoyan_order
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maoyan_order;

-- ------------------------------------------------------------
-- order
--
-- order_no is a snowflake id rendered as a string. It is the future
-- sharding gene; show_date is the time dimension for sharding.
-- idx_status_expire backs the timeout-cancel scanner.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_order_order;
CREATE TABLE t_order_order (
  id              BIGINT        NOT NULL              COMMENT 'primary key, snowflake id',
  order_no        VARCHAR(32)   NOT NULL              COMMENT 'business order number, snowflake based',
  user_id         BIGINT        NOT NULL              COMMENT 'buyer',
  schedule_id     BIGINT        NOT NULL              COMMENT 'screening being booked',
  film_id         BIGINT        NOT NULL              COMMENT 'denormalized for list display',
  film_name       VARCHAR(128)  NOT NULL DEFAULT ''   COMMENT 'denormalized snapshot, avoids a Feign call on every list page',
  cinema_id       BIGINT        NOT NULL              COMMENT 'denormalized',
  cinema_name     VARCHAR(128)  NOT NULL DEFAULT ''   COMMENT 'denormalized snapshot',
  hall_name       VARCHAR(64)   NOT NULL DEFAULT ''   COMMENT 'denormalized snapshot',
  show_time       DATETIME(3)   NULL                  COMMENT 'screening start time, snapshot',
  seat_count      INT           NOT NULL DEFAULT 0    COMMENT 'number of seats',
  seat_labels     VARCHAR(512)  NOT NULL DEFAULT ''   COMMENT 'e.g. "5排7座,5排8座", snapshot for display',
  total_amount    DECIMAL(10,2) NOT NULL DEFAULT 0    COMMENT 'sum of ticket prices before discount',
  discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0    COMMENT 'coupon discount applied',
  pay_amount      DECIMAL(10,2) NOT NULL DEFAULT 0    COMMENT 'amount actually payable',
  coupon_id       BIGINT        NULL                  COMMENT 'coupon used, if any',
  status          TINYINT       NOT NULL DEFAULT 0    COMMENT 'see header comment',
  lock_expire_time DATETIME(3)  NOT NULL              COMMENT 'payment deadline; after this the order is cancelled',
  pay_time        DATETIME(3)   NULL                  COMMENT 'when payment succeeded',
  refund_time     DATETIME(3)   NULL                  COMMENT 'when refund succeeded',
  refund_amount   DECIMAL(10,2) NULL                  COMMENT 'amount refunded',
  show_date       DATE          NOT NULL              COMMENT 'screening date, future sharding key',
  create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user_status (user_id, status, create_time),
  KEY idx_status_expire (status, lock_expire_time),
  KEY idx_schedule (schedule_id),
  KEY idx_show_date (show_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='order';

-- ------------------------------------------------------------
-- order item : one row per seat
--
-- uk_order_seat prevents the same seat being added twice to one order.
-- ticket_no is the admission code, generated at issue time (G2).
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_order_item;
CREATE TABLE t_order_item (
  id           BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  order_no     VARCHAR(32)   NOT NULL             COMMENT 'owning order',
  schedule_id  BIGINT        NOT NULL             COMMENT 'screening',
  seat_id      VARCHAR(16)   NOT NULL             COMMENT 'seat id, matches t_movie_schedule_seat.seat_id',
  seat_index   INT           NOT NULL             COMMENT 'bitmap offset, kept for reconciliation',
  seat_label   VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT 'human readable, e.g. "5排7座"',
  price        DECIMAL(10,2) NOT NULL DEFAULT 0   COMMENT 'ticket price for this seat',
  ticket_no    VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT 'admission code, generated on issue',
  check_status TINYINT       NOT NULL DEFAULT 0   COMMENT '0=not checked in 1=checked in',
  check_time   DATETIME(3)   NULL                 COMMENT 'when the ticket was scanned',
  create_time  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_seat (order_no, seat_id),
  KEY idx_schedule (schedule_id),
  KEY idx_ticket_no (ticket_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='order item (one per seat)';

-- ------------------------------------------------------------
-- order status log
-- Append-only audit trail. Written asynchronously AFTER the CAS
-- succeeds; losing a log row must never break the main flow.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_order_status_log;
CREATE TABLE t_order_status_log (
  id          BIGINT      NOT NULL             COMMENT 'primary key, snowflake id',
  order_no    VARCHAR(32) NOT NULL             COMMENT 'owning order',
  from_status TINYINT     NOT NULL             COMMENT 'status before the transition',
  to_status   TINYINT     NOT NULL             COMMENT 'status after the transition',
  operator    VARCHAR(32) NOT NULL DEFAULT ''  COMMENT 'who triggered it: USER / SYSTEM / MQ / ADMIN',
  remark      VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'free text',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order_no (order_no, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='order status transition log';
