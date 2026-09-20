-- 强制本连接使用 utf8mb4。
--
-- 不加这一句，mysql 客户端会协商成 latin1，服务端随即把中文文本的 UTF-8
-- 字节按 latin1 字符重新解释，再以双重编码的形式存进去（本该是 E6 B7 B1，
-- 实际存成了 C3A6 C2B7 C2B1）。损坏发生在写入的那一刻；事后再用正确的
-- 字符集读取，也已经挽不回来了。
SET NAMES utf8mb4;

-- ============================================================
-- maipiao_pay : pay-service 的私有库
--
-- 幂等分散在四层上，每一层背后都是一个索引：
--   L1 uk_channel_trade_type  重复的渠道通知
--   L2 t_pay_payment 上的状态 CAS  并发 / 乱序到达的回调
--   L3 uk_channel_trade       一个渠道交易号绑到两笔支付
--   L4 退款表上的 uk_payment_no   重复的退款请求
--
-- 光有 L1 不足以宣称幂等：如果第一次插入成功了，可进程在业务处理完成前就死了，
-- process_status 会停在 0。这时对重复键直接返回成功，等于悄悄丢掉那笔支付。
-- L1 只给日志去重；真正保证幂等的是 L2。
-- ============================================================

CREATE DATABASE IF NOT EXISTS maipiao_pay
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_pay;

-- ------------------------------------------------------------
-- 支付单
--
-- status: 0=pending 1=success 2=failed 3=closed
--
-- 支付成功的 CAS 条件是 `WHERE status IN (0,2) AND amount=?`：
--   0 pending  -> 正常的首次处理
--   2 failed   -> 迟到的 SUCCESS 回调可以覆盖一次失败
--   1 success  -> 永远匹配不上，落进幂等命中的分支
--   3 closed   -> 永远匹配不上，落进迟到支付的分支
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
  -- L3：一个渠道交易号只能绑定一笔支付
  UNIQUE KEY uk_channel_trade (channel, channel_trade_no),
  KEY idx_order_no (order_no),
  KEY idx_status_expire (status, expire_time),
  KEY idx_status_create (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='payment order';

-- ------------------------------------------------------------
-- 退款单
--
-- status: 0=pending 1=refunding 2=success 3=failed
--
-- release_seat 告诉 G3 这些座位要不要放回座位池。
-- 正常开场前退款它是 1；而对于座位早已释放之后才到的迟到支付，它是 0 ——
-- 释放两次会弄坏 sold_seat。
--
-- uk_payment_no（L4）让重复的退款请求变成空操作：插入会撞上唯一键，
-- 调用方直接返回已有的那条退款记录。
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
  -- L4：一笔支付只能有一条退款记录
  UNIQUE KEY uk_payment_no (payment_no),
  KEY idx_order_no (order_no),
  KEY idx_status_retry (status, retry_count, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='refund';

-- ------------------------------------------------------------
-- 支付通知日志
--
-- 幂等体系里的 L1。处理器先用
--   INSERT ... ON DUPLICATE KEY UPDATE retry_times = retry_times + 1
-- 插入，再取回那一行来检查 process_status：
--   process_status=1 -> 已经处理过了，可以安全返回成功
--   process_status=0 -> 第一次尝试还在进行中（或者已经崩了），重新处理
--   process_status=2 -> 上一次尝试失败了，重新处理
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
  -- L1：同一个交易号 + 通知类型只会被记录一次
  UNIQUE KEY uk_channel_trade_type (channel, channel_trade_no, notify_type),
  KEY idx_process_status (process_status, retry_times, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='payment notification log';

-- ------------------------------------------------------------
-- 每日对账差异
-- 比对订单金额、支付金额和退款金额。
-- 这张表里只要有行，就说明钱对不上，需要人工介入。
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
