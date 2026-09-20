-- 强制本连接使用 utf8mb4。
--
-- 不加这一句，mysql 客户端会协商成 latin1，服务端随即把中文文本的 UTF-8
-- 字节按 latin1 字符重新解释，再以双重编码的形式存进去（本该是 E6 B7 B1，
-- 实际存成了 C3A6 C2B7 C2B1）。损坏发生在写入的那一刻；事后再用正确的
-- 字符集读取，也已经挽不回来了。
SET NAMES utf8mb4;

-- ============================================================
-- maipiao_order : order-service 的私有库
--
-- 订单状态机（见 docs/design-decisions.md）：
--   0 PENDING_PAY    订单已创建，还没有发起支付
--   1 PAYING         支付单已创建，等待渠道回调
--   2 PAID           支付成功，票已出
--   3 COMPLETED      放映/演出已结束
--   4 CANCELLED      超时，或用户主动取消
--   5 REFUNDING      已发起退款，等待渠道
--   6 REFUNDED       退款成功
--
-- 每一次流转都必须走同一条 CAS 语句：
--   UPDATE t_order_order SET status=?
--    WHERE order_no=? AND status IN (...)
-- 并且必须断言影响行数 = 1。
-- 代码库中任何地方都禁止写不带状态条件的裸 `UPDATE t_order_order SET status=...`。
-- ============================================================

CREATE DATABASE IF NOT EXISTS maipiao_order
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_order;

-- ------------------------------------------------------------
-- 订单
--
-- order_no 是渲染成字符串的 snowflake id。它是将来分库分表的分片基因；
-- show_date 是分片的时间维度。
-- idx_status_expire 为超时取消的扫描器提供支撑。
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
  -- 下单那一刻快照下来的品类。历史记录不能因为目录今天怎么改而变样，
  -- 而订单页要靠它决定说「到影院取票」还是「凭电子票入场」。
  category        VARCHAR(16)   NOT NULL DEFAULT 'MOVIE' COMMENT 'MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL',
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
-- 订单明细：每个座位一行
--
-- uk_order_seat 防止同一个座位被重复加进同一张订单。
-- ticket_no 是入场码，在出票时生成（G2）。
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
  -- 这个座位售出时所在的票档。退款要退的是它当时花掉的钱，而一笔把
  -- 1880 的 VIP 座和 580 的看台座放在一起的订单，没有单一数字可推导。
  tier_id      BIGINT        NULL                  COMMENT 'price band the seat was sold at',
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
-- 订单状态日志
-- 只追加的审计轨迹。在 CAS 成功之后异步写入；
-- 丢一条日志绝不能弄坏主流程。
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
