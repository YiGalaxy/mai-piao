-- ============================================================
-- 演出模型：把影片、演唱会、脱口秀、话剧统一到一套模型里
--
-- 最初的表结构是按影片假设的：一场放映一个价格、每张票一个座位、没有入场控制。
-- 演唱会把这三点全打破了 —— 它分级定价（VIP / 内场 / 看台），可能只有站席，
-- 而且固定时间开票、限制每人购买数量。
--
-- 与其往 `t_movie_*` 表上硬加列、留下名不副实的表名，不如让表名如实描述
-- 它实际存的东西。t_event_project 里的一行就是「一个要卖票的东西」；
-- 它到底是电影还是单口喜剧专场，是 `category` 里的一个取值，而不是另一张表。
--
-- 与上一版表结构的命名对照：
--   t_movie_film          -> t_event_project
--   t_movie_cinema        -> t_event_venue
--   t_movie_hall          -> t_event_place
--   t_movie_schedule      -> t_event_session
--   t_movie_schedule_seat -> t_event_session_seat
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS maipiao_event
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_event;

-- ------------------------------------------------------------
-- project：要卖票的那个东西
--
-- 影片专有的列和演出专有的列并存，对另一类来说它们为空。另一种做法 ——
-- 一个分类一张表 —— 意味着列表页要查 N 张表，而且每加一个分类都是一次
-- 迁移，而不是多一个枚举值。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_project;
CREATE TABLE t_event_project (
  id            BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  category      VARCHAR(16)   NOT NULL DEFAULT 'MOVIE'
                COMMENT 'MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL',
  title         VARCHAR(128)  NOT NULL             COMMENT 'film title or show name',
  en_title      VARCHAR(128)  NOT NULL DEFAULT '',
  poster_url    VARCHAR(255)  NOT NULL DEFAULT '',
  duration      INT           NOT NULL DEFAULT 0   COMMENT 'runtime in minutes',
  tags          VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT 'genre or style, comma separated',
  show_date     DATE          NOT NULL             COMMENT 'release date, or the date the run opens',
  score         DECIMAL(3,1)  NOT NULL DEFAULT 0.0 COMMENT '0.0 means not rated yet',
  status        TINYINT       NOT NULL DEFAULT 0   COMMENT '0=upcoming 1=on sale 2=closed',

  -- 影片专有
  director      VARCHAR(64)   NOT NULL DEFAULT '',
  actors        VARCHAR(512)  NOT NULL DEFAULT '',

  -- 演出专有
  artist        VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT 'headline act or lead performer',
  organizer     VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT 'presenting company',
  description   VARCHAR(1000) NOT NULL DEFAULT '',

  create_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_category_status (category, status),
  KEY idx_show_date (show_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ticketed project: film, concert, talk show, theatre';

-- ------------------------------------------------------------
-- venue：演出发生的场所
-- 影院是「place 为影厅」的场所；体育馆是「place 为坐席分区」的场所。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_venue;
CREATE TABLE t_event_venue (
  id          BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  name        VARCHAR(128)  NOT NULL,
  venue_type  VARCHAR(16)   NOT NULL DEFAULT 'CINEMA'
              COMMENT 'CINEMA / STADIUM / GYMNASIUM / THEATER / LIVEHOUSE',
  address     VARCHAR(255)  NOT NULL DEFAULT '',
  district    VARCHAR(64)   NOT NULL DEFAULT '',
  phone       VARCHAR(32)   NOT NULL DEFAULT '',
  longitude   DECIMAL(10,6) NOT NULL DEFAULT 0,
  latitude    DECIMAL(10,6) NOT NULL DEFAULT 0,
  status      TINYINT       NOT NULL DEFAULT 1   COMMENT '0=closed 1=open',
  create_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_district (district, status),
  KEY idx_venue_type (venue_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='venue';

-- ------------------------------------------------------------
-- place：场所内的一个厅或一片坐席区
--
-- seating_mode 让「只有站席」的演出变得可表达：
--   SEATED     每张票都有座位；bitmap 就是座位图
--   STANDING   没有座位；bitmap 退化成入场计数器，
--              每一位代表一个容量单位
--   MIXED      场地内一部分有座，其余是站席
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_place;
CREATE TABLE t_event_place (
  id            BIGINT       NOT NULL            COMMENT 'primary key, snowflake id',
  venue_id      BIGINT       NOT NULL,
  name          VARCHAR(64)  NOT NULL            COMMENT 'e.g. "Hall 3 IMAX" or "Main Arena"',
  place_type    VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
  seating_mode  VARCHAR(16)  NOT NULL DEFAULT 'SEATED'
                COMMENT 'SEATED / STANDING / MIXED',
  row_count     INT          NOT NULL DEFAULT 0,
  col_count     INT          NOT NULL DEFAULT 0,
  seat_template JSON         NULL                COMMENT 'layout, see the note in the previous schema',
  seat_count    INT          NOT NULL DEFAULT 0  COMMENT 'sellable capacity after aisles and broken seats',
  status        TINYINT      NOT NULL DEFAULT 1,
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_venue (venue_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='place: room or seating area within a venue';

-- ------------------------------------------------------------
-- session：一场放映或一场演出
--
-- price 存的是「起价」，好让列表页只展示一个数字。某个具体座位实际多少钱，
-- 由它所属的票档决定。
--
-- sale_start_time / purchase_limit / require_real_name 是演唱会有、电影没有的
-- 入场控制。默认值让影片的行为保持不变：立即开售、不限购、不校验实名。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_session;
CREATE TABLE t_event_session (
  id                BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  project_id        BIGINT        NOT NULL,
  venue_id          BIGINT        NOT NULL,
  place_id          BIGINT        NOT NULL,
  show_date         DATE          NOT NULL,
  start_time        DATETIME(3)   NOT NULL,
  end_time          DATETIME(3)   NOT NULL,
  price             DECIMAL(10,2) NOT NULL             COMMENT 'from-price for listing; real price comes from the tier',
  total_seat        INT           NOT NULL DEFAULT 0,
  locked_seat       INT           NOT NULL DEFAULT 0,
  sold_seat         INT           NOT NULL DEFAULT 0,
  status            TINYINT       NOT NULL DEFAULT 0   COMMENT '0=pending 1=on sale 2=running 3=finished 4=cancelled',

  -- 入场控制
  sale_start_time   DATETIME(3)   NULL                 COMMENT 'when tickets open; NULL = already open',
  purchase_limit    INT           NOT NULL DEFAULT 0   COMMENT 'max tickets per order; 0 = unlimited',
  require_real_name TINYINT       NOT NULL DEFAULT 0   COMMENT '1 = every ticket must name an attendee',

  rush_mode         TINYINT       NOT NULL DEFAULT 0,
  rush_start_time   DATETIME(3)   NULL,

  -- 谁来挑座位：0 = 买家，1 = 系统。和场馆的 seating_mode 是两个不同的轴 ——
  -- 后者说的是这个地方有没有固定座位，前者说的是这次售卖让不让买家挑。
  seat_mode         TINYINT       NOT NULL DEFAULT 0   COMMENT '0=buyer picks seats, 1=system assigns adjacent seats',

  -- 这个场次是谁创建的。生成器的重置只清它自己建的，否则管理员录入的演出
  -- 会被无声删掉。
  source            VARCHAR(16)   NOT NULL DEFAULT 'ADMIN' COMMENT 'DEMO = generated, ADMIN = entered by hand',

  create_time       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_place_time (place_id, start_time),
  KEY idx_project_date (project_id, show_date, status),
  KEY idx_venue_date (venue_id, show_date, status),
  KEY idx_status_time (status, start_time),
  KEY idx_rush (rush_mode, rush_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='session: one screening or performance';

-- ------------------------------------------------------------
-- price tier：一场演出内部的票档
--
-- 影片场次只有一个票档、覆盖全部座位，于是两类演出的取价逻辑完全相同，
-- 定价永远不需要按 category 分支。
--
-- 票档用排号区间来表达，这也是场馆实际卖票的方式（「1-5 排是 VIP」）。
-- 座位在生成场次时被一次性划入某个票档，座位图就是按这个归属来上色的。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_price_tier;
CREATE TABLE t_event_price_tier (
  id          BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  session_id  BIGINT        NOT NULL,
  name        VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT 'VIP / floor / stands / standard',
  price       DECIMAL(10,2) NOT NULL,
  row_start   INT           NOT NULL DEFAULT 1,
  row_end     INT           NOT NULL DEFAULT 0   COMMENT '0 = to the last row',
  color       VARCHAR(16)   NOT NULL DEFAULT ''  COMMENT 'hex colour hint for the seat map',
  create_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='price tier within a session';

-- ------------------------------------------------------------
-- session seat：持久台账，每个座位一行
--
-- 分级定价能成立靠的就是 tier_id：座位图按票档上色，价格取自票档，
-- 订单行记录买的是哪个票档，退款时才能退对金额。
--
-- 对 STANDING 的场地，seat_index 依然是 bitmap 偏移量，但 row_num 和
-- col_num 是合成的 —— 此时 bitmap 被当成计数器用，而不是座位图。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_session_seat;
CREATE TABLE t_event_session_seat (
  id               BIGINT      NOT NULL             COMMENT 'primary key, snowflake id',
  session_id       BIGINT      NOT NULL,
  seat_id          VARCHAR(16) NOT NULL             COMMENT 'e.g. "5_7"; synthetic for standing',
  seat_index       INT         NOT NULL             COMMENT 'contiguous bitmap offset, assigned once',
  row_num          INT         NOT NULL,
  col_num          INT         NOT NULL,
  seat_type        TINYINT     NOT NULL DEFAULT 0   COMMENT '0=normal 1=couple 2=accessible',
  tier_id          BIGINT      NULL                 COMMENT 'price band this seat belongs to',
  status           TINYINT     NOT NULL DEFAULT 0   COMMENT '0=available 1=locked 2=sold',
  lock_order_no    VARCHAR(32) NULL,
  lock_user_id     BIGINT      NULL,
  lock_expire_time DATETIME(3) NULL,
  sold_order_no    VARCHAR(32) NULL,
  sold_time        DATETIME(3) NULL,
  version          INT         NOT NULL DEFAULT 0,
  create_time      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_seat (session_id, seat_id),
  UNIQUE KEY uk_session_index (session_id, seat_index),
  KEY idx_status_expire (status, lock_expire_time),
  KEY idx_sold_order (sold_order_no),
  KEY idx_lock_order (lock_order_no),
  KEY idx_tier (tier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='session seat ledger';

-- ------------------------------------------------------------
-- attendee：票上署名的实际观演人
--
-- 只有场次设置了 require_real_name 时才用。身份证号以哈希形式存储，
-- 理由和密码一样：要校验的是「是不是同一个人」，并不需要保留原文。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_event_attendee;
CREATE TABLE t_event_attendee (
  id          BIGINT       NOT NULL             COMMENT 'primary key, snowflake id',
  user_id     BIGINT       NOT NULL             COMMENT 'owner of this contact record',
  real_name   VARCHAR(64)  NOT NULL,
  id_card_hash VARCHAR(128) NOT NULL            COMMENT 'hashed; never store the original',
  id_card_mask VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'e.g. 4403**********1234, for display',
  phone       VARCHAR(20)  NOT NULL DEFAULT '',
  create_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='named attendee for real-name ticketing';
