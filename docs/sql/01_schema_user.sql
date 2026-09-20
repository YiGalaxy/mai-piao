-- ============================================================
-- maipiao_user : user-service's private schema
-- No cross-schema JOIN is allowed. Other services reach this data
-- through Feign only.
-- ============================================================

CREATE DATABASE IF NOT EXISTS maipiao_user
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_user;

-- ------------------------------------------------------------
-- user account
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_user_user;
CREATE TABLE t_user_user (
  id          BIGINT        NOT NULL                COMMENT 'primary key, snowflake id',
  phone       VARCHAR(20)   NOT NULL                COMMENT 'phone number, used as login account',
  password    VARCHAR(100)  NOT NULL                COMMENT 'BCrypt hash, never store plaintext',
  nickname    VARCHAR(50)   NOT NULL DEFAULT ''     COMMENT 'display name',
  avatar      VARCHAR(255)  NOT NULL DEFAULT ''     COMMENT 'avatar url',
  status      TINYINT       NOT NULL DEFAULT 1      COMMENT '0=disabled 1=active',
  create_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user account';

-- ------------------------------------------------------------
-- coupon template (managed by admin, issued to users)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_user_coupon_template;
CREATE TABLE t_user_coupon_template (
  id              BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  name            VARCHAR(64)   NOT NULL             COMMENT 'display name, e.g. "50 off 200"',
  amount          DECIMAL(10,2) NOT NULL             COMMENT 'discount amount',
  threshold       DECIMAL(10,2) NOT NULL             COMMENT 'minimum order amount required',
  valid_days      INT           NOT NULL DEFAULT 30  COMMENT 'validity in days after being claimed',
  total_quantity  INT           NOT NULL DEFAULT 0   COMMENT '0 = unlimited',
  claimed_quantity INT          NOT NULL DEFAULT 0   COMMENT 'how many have been issued',
  status          TINYINT       NOT NULL DEFAULT 1   COMMENT '0=offline 1=online',
  create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='coupon template';

-- ------------------------------------------------------------
-- user coupon
--
-- status transitions (the concurrency boundary is the WHERE clause):
--   0 unused --lock-->  1 locked --use-->  2 used
--                       1 locked --rollback--> 0 unused
--   0 unused --expire--> 3 expired
-- Locking must always be:
--   UPDATE ... SET status=1 WHERE id=? AND user_id=? AND status=0
-- and must verify affected rows = 1.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_user_coupon;
CREATE TABLE t_user_coupon (
  id                 BIGINT        NOT NULL           COMMENT 'primary key, snowflake id',
  user_id            BIGINT        NOT NULL           COMMENT 'owner',
  coupon_template_id BIGINT        NOT NULL           COMMENT 'source template',
  amount             DECIMAL(10,2) NOT NULL           COMMENT 'discount amount, copied from template',
  threshold          DECIMAL(10,2) NOT NULL           COMMENT 'minimum order amount, copied from template',
  status             TINYINT       NOT NULL DEFAULT 0 COMMENT '0=unused 1=locked 2=used 3=expired',
  order_no           VARCHAR(32)   NULL               COMMENT 'order_no that locked or consumed this coupon',
  lock_time          DATETIME(3)   NULL               COMMENT 'when it was locked by an order',
  expire_time        DATETIME(3)   NOT NULL           COMMENT 'expiry timestamp',
  create_time        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_status (user_id, status),
  KEY idx_status_expire (status, expire_time),
  KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user coupon';
