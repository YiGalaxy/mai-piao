-- ============================================================
-- Seata AT mode requires an undo_log table in EVERY database that
-- participates in a global transaction.
--
-- It is not a server-side table. Each service's own datasource needs one,
-- because the rollback image is written by the service that changed the data,
-- in the same local transaction as the change. Without it the service refuses
-- to start:
--
--     in AT mode, undo_log table not exist
--
-- which surfaces as an unsatisfied-dependency error on the datasource bean
-- and looks like a wiring problem rather than a missing table.
--
-- The global transaction in this project (G1, order creation) touches three
-- databases, so all three get the table. A service that never participates
-- transactionally does not need one - seat-service, for example, reads
-- maipiao_movie but never writes inside a global transaction.
--
-- If switch to db store mode for the Seata server itself, the `seata` schema
-- needs its own tables from the server distribution; this file is only about
-- the client-side undo log.
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- undo_log: identical structure in each participating database
-- ------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS maipiao_order
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS maipiao_movie
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS maipiao_user
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

USE maipiao_movie;
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
