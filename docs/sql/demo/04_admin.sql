-- ============================================================
-- 一个管理员账号。
--
-- 演示数据的一部分，不是系统的一部分 —— 真实部署不会想要这个账号。
--
-- 密码是 "123456"，和演示用户同一个 bcrypt 哈希。bcrypt 每次加盐，所以
-- "123456" 没有唯一正确的值，也没法靠眼睛推出来；这个文件的第一版里放了一个
-- 看起来很合理的字符串，结果谁都匹配不上，造出了一个没人登得进去的管理员。
-- 要重新生成就从一条能用的记录里复制，绝不能凭记忆写。
--
-- 网关在 /api/*/admin/** 上校验角色，role 列由 schema/01_user.sql 建出。
-- ============================================================

SET NAMES utf8mb4;

USE maipiao_user;

INSERT INTO t_user_user (id, phone, password, nickname, avatar, status, role)
VALUES (2102999999999999999, '13800000000',
        '$2a$10$f2pbDwOnfS0ngwpiDJsY6.JfWA/woH1mqdy94Rt7pS8G1X7kBsm0y',
        '管理员', '', 1, 'ADMIN')
ON DUPLICATE KEY UPDATE
  role = 'ADMIN',
  nickname = '管理员',
  password = VALUES(password);
