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
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_user;

ALTER TABLE t_user_user
  ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER'
    COMMENT 'USER or ADMIN; decides what the issued token may reach';

-- 13800000000 rather than a 139... number: the load-test buyers occupy that
-- block, and an administrator sharing an id with a buyer is a confusing thing
-- to debug.
INSERT INTO t_user_user (id, phone, password, nickname, avatar, status, role)
VALUES (2102999999999999999, '13800000000',
        '$2a$10$f2pbDwOnfS0ngK7hJvJ7Q8eY5jZ0X8m1nQ2wR3tY4uI5oP6aS7dF8G',
        '管理员', '', 1, 'ADMIN')
ON DUPLICATE KEY UPDATE role = 'ADMIN', nickname = '管理员';
