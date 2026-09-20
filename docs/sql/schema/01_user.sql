-- 强制本连接使用 utf8mb4。
--
-- 不加这一句，mysql 客户端会协商成 latin1，服务端随即把中文文本的 UTF-8
-- 字节按 latin1 字符重新解释，再以双重编码的形式存进去（本该是 E6 B7 B1，
-- 实际存成了 C3A6 C2B7 C2B1）。损坏发生在写入的那一刻；事后再用正确的
-- 字符集读取，也已经挽不回来了。
SET NAMES utf8mb4;

-- ============================================================
-- maipiao_user : user-service 的私有库
-- 不允许跨库 JOIN。其他服务只能通过 Feign 拿到这些数据。
-- ============================================================

CREATE DATABASE IF NOT EXISTS maipiao_user
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_user;

-- ------------------------------------------------------------
-- 用户账号
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_user_user;
CREATE TABLE t_user_user (
  id          BIGINT        NOT NULL                COMMENT 'primary key, snowflake id',
  phone       VARCHAR(20)   NOT NULL                COMMENT 'phone number, used as login account',
  password    VARCHAR(100)  NOT NULL                COMMENT 'BCrypt hash, never store plaintext',
  nickname    VARCHAR(50)   NOT NULL DEFAULT ''     COMMENT 'display name',
  avatar      VARCHAR(255)  NOT NULL DEFAULT ''     COMMENT 'avatar url',
  status      TINYINT       NOT NULL DEFAULT 1      COMMENT '0=disabled 1=active',
  -- 角色存在账号上，而不是由登录路径碰巧调用了哪个签发方法决定。
  -- 网关在 /api/*/admin/** 上校验它。
  role        VARCHAR(16)   NOT NULL DEFAULT 'USER' COMMENT 'USER or ADMIN',
  create_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user account';

-- ------------------------------------------------------------
-- 优惠券模板（由管理员维护，发放给用户）
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
-- 用户优惠券
--
-- 状态流转（并发边界就在 WHERE 子句里）：
--   0 未使用 --锁定-->  1 已锁定 --使用-->  2 已使用
--                       1 已锁定 --回滚--> 0 未使用
--   0 未使用 --过期--> 3 已过期
-- 锁定时必须始终写成：
--   UPDATE ... SET status=1 WHERE id=? AND user_id=? AND status=0
-- 并且必须校验影响行数 = 1。
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
