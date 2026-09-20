-- Force utf8mb4 on this connection.
--
-- Without it the mysql client negotiates latin1, the server re-interprets the
-- UTF-8 bytes of Chinese text as latin1 characters, and stores them
-- double-encoded (C3A6 C2B7 C2B1 where E6 B7 B1 was intended). The damage
-- happens on write; reading with the correct charset afterwards cannot undo it.
SET NAMES utf8mb4;

-- ============================================================
-- maipiao_pay : pay-service's private schema
--
-- Idempotency is spread over four layers, each backed by an index:
--   L1 uk_channel_trade_type  duplicate channel notification
--   L2 status CAS on t_pay_payment   concurrent / out-of-order callbacks
--   L3 uk_channel_trade       one channel trade bound to two payments
--   L4 uk_payment_no on refund       duplicate refund request
--
-- L1 alone is NOT enough to declare idempotency: if the first insert
-- succeeds but the process dies before business handling completes,
-- process_status stays 0. Returning success on the duplicate key
-- would silently drop that payment. L1 only dedups the log; L2 is
-- what actually guarantees idempotency.
-- ============================================================

CREATE DATABASE IF NOT EXISTS maipiao_pay
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_pay;

-- ------------------------------------------------------------
-- payment
--
-- status: 0=pending 1=success 2=failed 3=closed
--
-- The success CAS is `WHERE status IN (0,2) AND amount=?`:
--   0 pending  -> normal first-time handling
--   2 failed   -> a late SUCCESS callback may overwrite a failure
--   1 success  -> never matches, falls into the idempotent-hit branch
--   3 closed   -> never matches, falls into the late-payment branch
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_pay_payment;
CREATE TABLE t_pay_payment (
  id               BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  payment_no       VARCHAR(32)   NOT NULL             COMMENT 'business payment number',
  order_no         VARCHAR(32)   NOT NULL             COMMENT 'owning order',
  user_id          BIGINT        NOT NULL             COMMENT 'payer',
  channel          VARCHAR(16)   NOT NULL             COMMENT 'MOCK / ALIPAY',
  amount           DECIMAL(10,2) NOT NULL             COMMENT 'amount to pay, verified on every callback',
  status           TINYINT       NOT NULL DEFAULT 0   COMMENT '0=pending 1=success 2=failed 3=closed',
  channel_trade_no VARCHAR(64)   NULL                 COMMENT 'trade number issued by the channel',
  expire_time      DATETIME(3)   NOT NULL             COMMENT 'payment deadline',
  pay_time         DATETIME(3)   NULL                 COMMENT 'when the channel reports success',
  notify_time      DATETIME(3)   NULL                 COMMENT 'when the last callback arrived',
  create_time      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_no (payment_no),
  -- L3: a channel trade number may only ever bind to one payment
  UNIQUE KEY uk_channel_trade (channel, channel_trade_no),
  KEY idx_order_no (order_no),
  KEY idx_status_expire (status, expire_time),
  KEY idx_status_create (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='payment order';

-- ------------------------------------------------------------
-- refund
--
-- status: 0=pending 1=refunding 2=success 3=failed
--
-- release_seat tells G3 whether the seats should go back to the pool.
-- It is 1 for a normal pre-show refund, and 0 for a late payment that
-- arrived after the seats were already released - releasing twice
-- would corrupt sold_seat.
--
-- uk_payment_no (L4) makes a repeated refund request a no-op: the
-- insert collides and the caller returns the existing refund.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_pay_refund;
CREATE TABLE t_pay_refund (
  id                BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  refund_no         VARCHAR(32)   NOT NULL             COMMENT 'business refund number',
  payment_no        VARCHAR(32)   NOT NULL             COMMENT 'payment being refunded',
  order_no          VARCHAR(32)   NOT NULL             COMMENT 'owning order',
  user_id           BIGINT        NOT NULL             COMMENT 'payee',
  channel           VARCHAR(16)   NOT NULL             COMMENT 'MOCK / ALIPAY',
  refund_amount     DECIMAL(10,2) NOT NULL             COMMENT 'amount to refund',
  reason            VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT 'USER_APPLY / TIME_OUT_PAID / SCHEDULE_CANCELLED',
  status            TINYINT       NOT NULL DEFAULT 0   COMMENT '0=pending 1=refunding 2=success 3=failed',
  release_seat      TINYINT       NOT NULL DEFAULT 1   COMMENT '1=return the seats to the pool, 0=seats already released',
  channel_refund_no VARCHAR(64)   NULL                 COMMENT 'refund number issued by the channel',
  retry_count       INT           NOT NULL DEFAULT 0   COMMENT 'how many times a retry has been attempted',
  next_retry_time   DATETIME(3)   NULL                 COMMENT 'next retry timestamp, exponential backoff',
  last_error        VARCHAR(512)  NULL                 COMMENT 'last failure message',
  refund_time       DATETIME(3)   NULL                 COMMENT 'when the refund succeeded',
  create_time       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (refund_no),
  -- L4: one payment can only ever have one refund record
  UNIQUE KEY uk_payment_no (payment_no),
  KEY idx_order_no (order_no),
  KEY idx_status_retry (status, retry_count, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='refund';

-- ------------------------------------------------------------
-- payment notification log
--
-- L1 of the idempotency stack. The handler inserts with
--   INSERT ... ON DUPLICATE KEY UPDATE retry_times = retry_times + 1
-- and gets back the row to inspect process_status:
--   process_status=1 -> already handled, safe to return success
--   process_status=0 -> first attempt still in flight (or crashed), reprocess
--   process_status=2 -> previous attempt failed, reprocess
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_pay_notify_log;
CREATE TABLE t_pay_notify_log (
  id               BIGINT       NOT NULL             COMMENT 'primary key, snowflake id',
  channel          VARCHAR(16)  NOT NULL             COMMENT 'MOCK / ALIPAY',
  channel_trade_no VARCHAR(64)  NOT NULL             COMMENT 'trade number from the channel',
  notify_type      VARCHAR(16)  NOT NULL             COMMENT 'PAY / REFUND',
  raw_body         TEXT         NULL                 COMMENT 'raw callback payload, kept for replay and forensics',
  sign_verified    TINYINT      NOT NULL DEFAULT 0   COMMENT '0=signature invalid 1=verified',
  process_status   TINYINT      NOT NULL DEFAULT 0   COMMENT '0=not processed 1=success 2=failed',
  process_result   VARCHAR(512) NULL                 COMMENT 'failure reason or branch taken',
  retry_times      INT          NOT NULL DEFAULT 0   COMMENT 'how many times the channel has pushed this',
  create_time      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- L1: the same trade + notification type is only ever logged once
  UNIQUE KEY uk_channel_trade_type (channel, channel_trade_no, notify_type),
  KEY idx_process_status (process_status, retry_times, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='payment notification log';

-- ------------------------------------------------------------
-- daily reconciliation result
-- Compares order amounts vs payment amounts vs refund amounts.
-- Any row here means money does not add up and needs a human.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_pay_reconcile_diff;
CREATE TABLE t_pay_reconcile_diff (
  id            BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  bill_date     DATE          NOT NULL             COMMENT 'business date being reconciled',
  diff_type     VARCHAR(32)   NOT NULL             COMMENT 'ORDER_GT_PAY / PAY_GT_ORDER / REFUND_MISMATCH',
  order_no      VARCHAR(32)   NULL                 COMMENT 'related order, if any',
  payment_no    VARCHAR(32)   NULL                 COMMENT 'related payment, if any',
  order_amount  DECIMAL(10,2) NULL                 COMMENT 'amount recorded on the order side',
  pay_amount    DECIMAL(10,2) NULL                 COMMENT 'amount recorded on the payment side',
  detail        VARCHAR(512)  NOT NULL DEFAULT ''  COMMENT 'human readable description',
  create_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_bill_date (bill_date, diff_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='daily reconciliation difference';
