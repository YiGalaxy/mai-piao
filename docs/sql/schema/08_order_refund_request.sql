USE maipiao_order;
CREATE TABLE IF NOT EXISTS t_order_refund_request (
  order_no VARCHAR(32) NOT NULL PRIMARY KEY,
  amount DECIMAL(10,2) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending delivery,1=accepted by pay service',
  refund_no VARCHAR(32) NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_error VARCHAR(512) NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_delivery(status,next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
