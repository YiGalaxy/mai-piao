-- ============================================================
-- Who created a session: the demo generator, or an administrator.
--
-- The generator's reset clears every session before it writes new ones, which
-- was fine while it was the only thing creating them. Now that an
-- administrator can put a show on sale, that reset silently deletes it - and
-- the person who entered it has no way to tell whether they did something
-- wrong or whether something else ate their work.
--
-- So a session says where it came from, and the reset only clears its own.
-- ADMIN is the default because a row created by anything other than the
-- generator is somebody's work, and the safer default is to keep it.
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_event;

-- Same idempotent-column idiom as 13_seed_admin.sql; see the note there. A
-- failed ALTER aborts the script, which would silently skip the backfill
-- below - the part that actually matters on a database that already has rows.
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = 'maipiao_event'
     AND table_name = 't_event_session'
     AND column_name = 'source');

SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE t_event_session ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''ADMIN'' COMMENT ''DEMO = generated, ADMIN = entered by hand''',
  'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- Backfill.
--
-- Every session that existed when this column was added was produced by the
-- generator - the admin screen did not exist yet. Without this they default
-- to ADMIN, which is the right default for new rows and the wrong answer for
-- these ones: the generator would keep finding the venue-time unique key
-- still taken by its own previous run, and fail to write anything.
--
-- Deliberately NOT idempotent, and deliberately not part of a reset script.
-- Running it a second time would mark genuine administrator entries as
-- generated and expose them to the next reset.
-- ------------------------------------------------------------

UPDATE t_event_session SET source = 'DEMO' WHERE source = 'ADMIN';
