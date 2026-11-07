-- 管理员操作审计日志增量迁移。
-- 先备份并停止管理写入，再在四个有数据库的服务库执行；本文件不删除已有数据。

USE maipiao_user;
CREATE TABLE IF NOT EXISTS t_admin_audit_log (
  id             BIGINT        NOT NULL,
  operator_id    BIGINT        NULL,
  action_name    VARCHAR(64)   NOT NULL,
  resource_type  VARCHAR(64)   NOT NULL,
  http_method    VARCHAR(16)   NOT NULL DEFAULT '',
  request_uri    VARCHAR(255)  NOT NULL DEFAULT '',
  target_args    TEXT          NULL,
  success        TINYINT       NOT NULL DEFAULT 0,
  result_code    INT           NULL,
  result_message VARCHAR(255)  NOT NULL DEFAULT '',
  error_message  VARCHAR(1000) NOT NULL DEFAULT '',
  client_ip      VARCHAR(64)   NOT NULL DEFAULT '',
  user_agent     VARCHAR(255)  NOT NULL DEFAULT '',
  duration_ms    BIGINT        NOT NULL DEFAULT 0,
  create_time    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_operator_time (operator_id, create_time),
  KEY idx_audit_resource_time (resource_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='administrator operation audit log';

USE maipiao_order;
CREATE TABLE IF NOT EXISTS t_admin_audit_log (
  id             BIGINT        NOT NULL,
  operator_id    BIGINT        NULL,
  action_name    VARCHAR(64)   NOT NULL,
  resource_type  VARCHAR(64)   NOT NULL,
  http_method    VARCHAR(16)   NOT NULL DEFAULT '',
  request_uri    VARCHAR(255)  NOT NULL DEFAULT '',
  target_args    TEXT          NULL,
  success        TINYINT       NOT NULL DEFAULT 0,
  result_code    INT           NULL,
  result_message VARCHAR(255)  NOT NULL DEFAULT '',
  error_message  VARCHAR(1000) NOT NULL DEFAULT '',
  client_ip      VARCHAR(64)   NOT NULL DEFAULT '',
  user_agent     VARCHAR(255)  NOT NULL DEFAULT '',
  duration_ms    BIGINT        NOT NULL DEFAULT 0,
  create_time    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_operator_time (operator_id, create_time),
  KEY idx_audit_resource_time (resource_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='administrator operation audit log';

USE maipiao_event;
CREATE TABLE IF NOT EXISTS t_admin_audit_log (
  id             BIGINT        NOT NULL,
  operator_id    BIGINT        NULL,
  action_name    VARCHAR(64)   NOT NULL,
  resource_type  VARCHAR(64)   NOT NULL,
  http_method    VARCHAR(16)   NOT NULL DEFAULT '',
  request_uri    VARCHAR(255)  NOT NULL DEFAULT '',
  target_args    TEXT          NULL,
  success        TINYINT       NOT NULL DEFAULT 0,
  result_code    INT           NULL,
  result_message VARCHAR(255)  NOT NULL DEFAULT '',
  error_message  VARCHAR(1000) NOT NULL DEFAULT '',
  client_ip      VARCHAR(64)   NOT NULL DEFAULT '',
  user_agent     VARCHAR(255)  NOT NULL DEFAULT '',
  duration_ms    BIGINT        NOT NULL DEFAULT 0,
  create_time    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_operator_time (operator_id, create_time),
  KEY idx_audit_resource_time (resource_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='administrator operation audit log';

USE maipiao_pay;
CREATE TABLE IF NOT EXISTS t_admin_audit_log (
  id             BIGINT        NOT NULL,
  operator_id    BIGINT        NULL,
  action_name    VARCHAR(64)   NOT NULL,
  resource_type  VARCHAR(64)   NOT NULL,
  http_method    VARCHAR(16)   NOT NULL DEFAULT '',
  request_uri    VARCHAR(255)  NOT NULL DEFAULT '',
  target_args    TEXT          NULL,
  success        TINYINT       NOT NULL DEFAULT 0,
  result_code    INT           NULL,
  result_message VARCHAR(255)  NOT NULL DEFAULT '',
  error_message  VARCHAR(1000) NOT NULL DEFAULT '',
  client_ip      VARCHAR(64)   NOT NULL DEFAULT '',
  user_agent     VARCHAR(255)  NOT NULL DEFAULT '',
  duration_ms    BIGINT        NOT NULL DEFAULT 0,
  create_time    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_operator_time (operator_id, create_time),
  KEY idx_audit_resource_time (resource_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='administrator operation audit log';
