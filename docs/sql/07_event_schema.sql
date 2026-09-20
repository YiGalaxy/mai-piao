-- ============================================================
-- Event schema : films, concerts, talk shows and theatre under one model
--
-- The original schema assumed film: one price per screening, a seat for
-- every ticket, no admission control. A concert breaks all three - it has
-- tiered pricing (VIP / floor / stands), it may be standing-only, and it
-- opens at a fixed time with a per-person purchase limit.
--
-- Rather than bolt columns onto `t_movie_*` tables and leave names that
-- describe something else, the tables are named for what they actually hold.
-- A row in t_event_project is a thing that is ticketed; whether that is a
-- film or a stand-up set is a value in `category`, not a different table.
--
-- Naming map from the previous schema:
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
-- project : the thing being ticketed
--
-- Film-specific and performance-specific columns coexist and are null for
-- the other kind. The alternative - one table per category - would mean the
-- listing page queries N tables, and every new category is a migration
-- rather than a new enum value.
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

  -- film
  director      VARCHAR(64)   NOT NULL DEFAULT '',
  actors        VARCHAR(512)  NOT NULL DEFAULT '',

  -- performance
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
-- venue : where it happens
-- A cinema is a venue whose places are screens; an arena is a venue whose
-- places are seating blocks.
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
-- place : a room or seating area within a venue
--
-- seating_mode is what makes standing-only performances expressible:
--   SEATED     every ticket has a seat; the bitmap is the seat map
--   STANDING   no seats; the bitmap degrades to an admission counter, with
--              each bit standing for one unit of capacity
--   MIXED      part of the floor is seated, the rest is standing
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
-- session : one screening or one performance
--
-- price keeps the "from" price so a listing can show one number. What a
-- specific seat actually costs comes from its tier.
--
-- sale_start_time / purchase_limit / require_real_name are the admission
-- controls a concert needs and a film does not. Defaults leave film
-- behaviour unchanged: on sale immediately, no limit, no real-name check.
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

  -- admission controls
  sale_start_time   DATETIME(3)   NULL                 COMMENT 'when tickets open; NULL = already open',
  purchase_limit    INT           NOT NULL DEFAULT 0   COMMENT 'max tickets per order; 0 = unlimited',
  require_real_name TINYINT       NOT NULL DEFAULT 0   COMMENT '1 = every ticket must name an attendee',

  rush_mode         TINYINT       NOT NULL DEFAULT 0,
  rush_start_time   DATETIME(3)   NULL,

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
-- price tier : a price band within a session
--
-- A film session has exactly one tier covering every seat, so the same
-- lookup works for both kinds and pricing never branches on category.
--
-- Bands are expressed as row ranges, which is how venues sell them in
-- practice ("rows 1-5 are VIP"). A seat is assigned to a tier once, when the
-- session is generated, and that assignment is what the seat map colours by.
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
-- session seat : the durable ledger, one row per seat
--
-- tier_id is what makes tiered pricing work: the seat map colours by tier,
-- the price comes from the tier, and the order line records which tier was
-- bought so a refund returns the right amount.
--
-- For STANDING places, seat_index is still the bitmap offset but row_num and
-- col_num are synthetic - the bitmap is used as a counter rather than a map.
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
-- attendee : named person on a ticket
--
-- Only used when the session sets require_real_name. The id number is stored
-- hashed for the same reason passwords are: the check is "same person",
-- which does not require keeping the original.
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
