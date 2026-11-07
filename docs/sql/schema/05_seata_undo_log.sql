-- ============================================================
-- Seata AT 模式要求每一个参与全局事务的数据库里都有一张 undo_log 表。
--
-- 它不是服务端的表。每个服务自己的数据源都要有一张，因为回滚镜像是
-- 改动了数据的那个服务写下的，而且和这次改动在同一个本地事务里。没有它，
-- 服务会拒绝启动：
--
--     in AT mode, undo_log table not exist
--
-- 但报出来的却是数据源 bean 上的 unsatisfied-dependency 错误，看上去像
-- 装配问题，而不是少了一张表。
--
-- 本项目的全局事务（G1，创建订单）会碰三个库，所以这三个库都要有这张表。
-- 从不参与事务的服务则不需要 —— 比如 seat-service，它读 maipiao_event，
-- 但从不在全局事务里写。
--
-- maipiao_event 是最要紧、也最容易漏掉的一个。目录那几张表是从
-- maipiao_movie 迁过去的，undo log 却没跟着搬：数据源 URL 变了，
-- 分支事务写入的库变了，而启动时什么都没报错，因为老库里那张表还在。
-- 结果就是 Seata 无法补偿这个分支 —— G1 把订单回滚了，却把它预留的座位
-- 留在了锁定状态：没有订单认领它们，也没有任何超时会去释放它们。
--
-- 把一个库加进全局事务，就意味着要给它加上这张表。
-- 在事务真正回滚之前，这个故障是完全静默的。
--
-- 如果 Seata 服务端自己切换成 db 存储模式，`seata` 库还需要它自己的
-- 那几张表（来自服务端发行包）；本文件只关心客户端侧的 undo log。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- undo_log：每个参与库中结构完全一致
-- ------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS maipiao_order
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS maipiao_user
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS maipiao_event
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS maipiao_pay
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE maipiao_order;
CREATE TABLE IF NOT EXISTS undo_log
(
  branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
  xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
  rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info: before and after images',
  log_status    INT          NOT NULL COMMENT '0=normal, 1=defense',
  log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';

USE maipiao_user;
CREATE TABLE IF NOT EXISTS undo_log
(
  branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
  xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
  rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info: before and after images',
  log_status    INT          NOT NULL COMMENT '0=normal, 1=defense',
  log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';

USE maipiao_event;
CREATE TABLE IF NOT EXISTS undo_log
(
  branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
  xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
  rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info: before and after images',
  log_status    INT          NOT NULL COMMENT '0=normal, 1=defense',
  log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';

USE maipiao_pay;
CREATE TABLE IF NOT EXISTS undo_log
(
  branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
  xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
  rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info: before and after images',
  log_status    INT          NOT NULL COMMENT '0=normal, 1=defense',
  log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';
