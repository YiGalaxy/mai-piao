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

-- ADD COLUMN IF NOT EXISTS is not portable across MySQL versions, so this
-- file tolerates being run twice: the ALTER fails loudly the second time and
-- the backfill below still runs, which is the part that matters.
ALTER TABLE t_event_session
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'ADMIN'
    COMMENT 'DEMO = generated, safe to reset; ADMIN = entered by hand';

-- ------------------------------------------------------------
-- Backfill.
--
-- Every session that existed when this column was added was produced by the
-- generator - the admin screen did not exist yet. Without this they default to
-- ADMIN, which is the safe default for new rows and the wrong answer for these
-- ones: the generator would keep finding the table occupied and fail to write
-- its own sessions, because the venue-time unique key was still taken by the
-- previous run's rows.
--
-- Safe to run once, at the moment the column is added. Re-running it later
-- would mark genuine admin entries as generated and expose them to the reset,
-- so it is not something to keep in a reset script.
-- ------------------------------------------------------------

UPDATE t_event_session SET source = 'DEMO' WHERE source = 'ADMIN';
