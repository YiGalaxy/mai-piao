-- ============================================================
-- maoyan_movie : movie-service's private schema
-- Holds film / cinema / hall / schedule data AND the seat ledger
-- (t_movie_schedule_seat). seat-service has no schema of its own:
-- Redis is the real-time source of truth for seat availability,
-- this table is the durable ledger it reconciles against.
-- ============================================================

CREATE DATABASE IF NOT EXISTS maoyan_movie
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maoyan_movie;

-- ------------------------------------------------------------
-- film
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_movie_film;
CREATE TABLE t_movie_film (
  id           BIGINT        NOT NULL              COMMENT 'primary key, snowflake id',
  name         VARCHAR(128)  NOT NULL              COMMENT 'film title',
  en_name      VARCHAR(128)  NOT NULL DEFAULT ''   COMMENT 'english title',
  poster_url   VARCHAR(255)  NOT NULL DEFAULT ''   COMMENT 'poster image url',
  director     VARCHAR(64)   NOT NULL DEFAULT ''   COMMENT 'director',
  actors       VARCHAR(512)  NOT NULL DEFAULT ''   COMMENT 'comma separated cast, denormalized for list display',
  duration     INT           NOT NULL DEFAULT 0    COMMENT 'runtime in minutes',
  film_type    VARCHAR(64)   NOT NULL DEFAULT ''   COMMENT 'genre tags, comma separated',
  release_date DATE          NOT NULL              COMMENT 'release date',
  score        DECIMAL(3,1)  NOT NULL DEFAULT 0.0  COMMENT 'rating 0.0 - 9.9',
  status       TINYINT       NOT NULL DEFAULT 0    COMMENT '0=upcoming 1=now showing 2=offline',
  create_time  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_status_release (status, release_date),
  KEY idx_release_date (release_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='film';

-- ------------------------------------------------------------
-- cinema
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_movie_cinema;
CREATE TABLE t_movie_cinema (
  id          BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  name        VARCHAR(128)  NOT NULL             COMMENT 'cinema name',
  address     VARCHAR(255)  NOT NULL DEFAULT ''  COMMENT 'full address',
  district    VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT 'district, used for filtering',
  phone       VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT 'contact phone',
  longitude   DECIMAL(10,6) NOT NULL DEFAULT 0   COMMENT 'gps longitude',
  latitude    DECIMAL(10,6) NOT NULL DEFAULT 0   COMMENT 'gps latitude',
  status      TINYINT       NOT NULL DEFAULT 1   COMMENT '0=closed 1=open',
  create_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_district (district, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='cinema';

-- ------------------------------------------------------------
-- hall
--
-- seat_template (JSON) describes the physical layout. Example:
-- {
--   "rows": 10,
--   "cols": 12,
--   "aisleCols": [4, 9],
--   "brokenSeats": ["1-1", "1-12", "10-1", "10-12"],
--   "coupleSeats": [["5-6","5-7"], ["6-6","6-7"]]
-- }
--
-- seat_index (see t_movie_schedule_seat) is computed ONCE from this
-- template when a schedule is generated. It must never be derived at
-- runtime from (row-1)*cols+(col-1): aisle columns make the index
-- non-contiguous and the formula would silently drift.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_movie_hall;
CREATE TABLE t_movie_hall (
  id            BIGINT       NOT NULL            COMMENT 'primary key, snowflake id',
  cinema_id     BIGINT       NOT NULL            COMMENT 'owning cinema',
  name          VARCHAR(64)  NOT NULL            COMMENT 'hall name, e.g. "Hall 3 IMAX"',
  hall_type     VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / IMAX / 3D / VIP',
  row_count     INT          NOT NULL DEFAULT 0  COMMENT 'number of rows',
  col_count     INT          NOT NULL DEFAULT 0  COMMENT 'number of columns (including aisles)',
  seat_template JSON         NULL                COMMENT 'seat layout definition, see table comment',
  seat_count    INT          NOT NULL DEFAULT 0  COMMENT 'number of sellable seats after aisles/broken removed',
  status        TINYINT      NOT NULL DEFAULT 1  COMMENT '0=disabled 1=active',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_cinema (cinema_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='hall';

-- ------------------------------------------------------------
-- schedule (a screening of a film, in a hall, at a time)
--
-- Inventory model: total_seat = locked_seat + sold_seat + remaining.
-- The anti-oversell boundary is a single UPDATE:
--   UPDATE t_movie_schedule
--      SET locked_seat = locked_seat + N
--    WHERE id = ? AND status = 1
--      AND locked_seat + sold_seat + N <= total_seat
-- and the caller MUST assert affected rows = 1.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_movie_schedule;
CREATE TABLE t_movie_schedule (
  id              BIGINT        NOT NULL             COMMENT 'primary key, snowflake id',
  film_id         BIGINT        NOT NULL             COMMENT 'film being screened',
  cinema_id       BIGINT        NOT NULL             COMMENT 'cinema, denormalized to avoid a cross-service call',
  hall_id         BIGINT        NOT NULL             COMMENT 'hall',
  show_date       DATE          NOT NULL             COMMENT 'screening date, future sharding key for orders',
  start_time      DATETIME(3)   NOT NULL             COMMENT 'screening start',
  end_time        DATETIME(3)   NOT NULL             COMMENT 'screening end',
  price           DECIMAL(10,2) NOT NULL             COMMENT 'base ticket price',
  total_seat      INT           NOT NULL DEFAULT 0   COMMENT 'total sellable seats for this screening',
  locked_seat     INT           NOT NULL DEFAULT 0   COMMENT 'seats currently locked by unpaid orders',
  sold_seat       INT           NOT NULL DEFAULT 0   COMMENT 'seats successfully sold',
  status          TINYINT       NOT NULL DEFAULT 0   COMMENT '0=pending 1=on sale 2=screening 3=finished 4=cancelled',
  rush_mode       TINYINT       NOT NULL DEFAULT 0   COMMENT '0=normal 1=rush sale, requires queue admission',
  rush_start_time DATETIME(3)   NULL                 COMMENT 'when the rush sale opens',
  create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_hall_time (hall_id, start_time),
  KEY idx_film_date (film_id, show_date, status),
  KEY idx_cinema_date (cinema_id, show_date, status),
  KEY idx_status_time (status, start_time),
  KEY idx_rush (rush_mode, rush_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='schedule (screening)';

-- ------------------------------------------------------------
-- schedule seat : the durable ledger of every seat of every screening
--
-- seat_index is the Redis Bitmap offset. It is assigned once, at
-- schedule-generation time, in row-major order skipping aisles and
-- broken seats, starting from 0 and staying contiguous.
--
-- status transitions and their concurrency boundaries:
--   0 available --occupy--> 1 locked   WHERE status=0
--   1 locked    --sold----> 2 sold     WHERE status=1 AND lock_order_no=?
--   2 sold      --refund--> 0 available WHERE status=2 AND sold_order_no=?
-- Every one of these must assert the affected row count.
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_movie_schedule_seat;
CREATE TABLE t_movie_schedule_seat (
  id               BIGINT      NOT NULL             COMMENT 'primary key, snowflake id',
  schedule_id      BIGINT      NOT NULL             COMMENT 'owning screening',
  seat_id          VARCHAR(16) NOT NULL             COMMENT 'human readable seat id, e.g. "5_7" (row_col)',
  seat_index       INT         NOT NULL             COMMENT 'contiguous offset into the Redis bitmap - assigned once, never recomputed',
  row_num          INT         NOT NULL             COMMENT 'row number, 1-based',
  col_num          INT         NOT NULL             COMMENT 'column number, 1-based',
  seat_type        TINYINT     NOT NULL DEFAULT 0   COMMENT '0=normal 1=couple 2=accessible',
  status           TINYINT     NOT NULL DEFAULT 0   COMMENT '0=available 1=locked 2=sold',
  lock_order_no    VARCHAR(32) NULL                 COMMENT 'order holding the lock',
  lock_user_id     BIGINT      NULL                 COMMENT 'user holding the lock',
  lock_expire_time DATETIME(3) NULL                 COMMENT 'when the lock expires',
  sold_order_no    VARCHAR(32) NULL                 COMMENT 'order that bought this seat',
  sold_time        DATETIME(3) NULL                 COMMENT 'when it was sold',
  version          INT         NOT NULL DEFAULT 0   COMMENT 'optimistic lock counter, bumped on every state change',
  create_time      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_seat (schedule_id, seat_id),
  UNIQUE KEY uk_schedule_index (schedule_id, seat_index),
  KEY idx_status_expire (status, lock_expire_time),
  KEY idx_sold_order (sold_order_no),
  KEY idx_lock_order (lock_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='schedule seat ledger';
