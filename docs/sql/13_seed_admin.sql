-- ============================================================
-- Roles, and an administrator account.
--
-- The gateway has injected X-User-Role since the beginning and no service ever
-- read it, which made "admin" mean nothing. Worse, it could not have meant
-- anything: JwtUtil has a generateAdminToken method that is never called
-- anywhere, and the user table has no column to say who is one. The role was
-- decided by which method the login path happened to invoke, and the login
-- path always invoked the user one.
--
-- So the role is stored on the account, where it belongs, and login issues
-- whatever the account says. That also makes revoking it a database change
-- rather than a deploy.
--
-- Re-runnable. See the note above the DDL for why that took work.
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_user;

-- ------------------------------------------------------------
-- Adding a column has no IF NOT EXISTS in MySQL, and a plain ALTER that fails
-- aborts the entire script - so on a second run, every statement after it is
-- skipped without a word. An earlier version of this file did exactly that:
-- the ALTER collided, the script stopped there, and the administrator account
-- it was supposed to create was never inserted. The account was missing and
-- the only symptom was a login that said the password was wrong.
--
-- The quoted ALTER is built as a string, so its own single quotes are doubled.
-- ------------------------------------------------------------

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = 'maipiao_user'
     AND table_name = 't_user_user'
     AND column_name = 'role');

SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE t_user_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT ''USER'' COMMENT ''USER or ADMIN''',
  'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- The password is "123456" - the same hash the demo users carry.
--
-- Copied from a known-good row rather than written by hand. bcrypt salts per
-- hash, so "123456" has no single correct value and one cannot be derived by
-- eye; the first version of this file contained a plausible-looking string
-- that matched nothing, and produced an administrator nobody could log into.
-- Regenerate it from a row that works, never from memory.
-- ------------------------------------------------------------

INSERT INTO t_user_user (id, phone, password, nickname, avatar, status, role)
VALUES (2102999999999999999, '13800000000',
        '$2a$10$f2pbDwOnfS0ngwpiDJsY6.JfWA/woH1mqdy94Rt7pS8G1X7kBsm0y',
        '管理员', '', 1, 'ADMIN')
ON DUPLICATE KEY UPDATE
  role = 'ADMIN',
  nickname = '管理员',
  password = VALUES(password);
