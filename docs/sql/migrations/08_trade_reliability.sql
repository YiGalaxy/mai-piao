-- 升级前备份并停止订单/支付写流量。不要执行带 DROP 的初始化 schema 升级已有库。
-- 首先在 maipiao_pay 执行以下检查；有结果时先逐笔对账，禁止自动删除重复支付：
-- SELECT order_no,COUNT(*) FROM t_pay_payment GROUP BY order_no HAVING COUNT(*)>1;
USE maipiao_pay;
ALTER TABLE t_pay_payment
  ADD COLUMN cashier_url VARCHAR(1024) NULL AFTER channel_trade_no,
  ADD COLUMN seat_synced TINYINT NOT NULL DEFAULT 0,
  ADD UNIQUE KEY uk_order_no(order_no),
  ADD KEY idx_seat_sync(status,seat_synced,update_time),
  ADD KEY idx_status_update(status,update_time);

ALTER TABLE t_pay_refund
  ADD COLUMN seat_released TINYINT NOT NULL DEFAULT 0,
  ADD KEY idx_seat_release(status,release_seat,seat_released,update_time);

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
-- 补回旧版已进入 REFUNDING 但尚未送到支付服务的申请，重复投递由支付唯一键兜底。
INSERT INTO t_order_refund_request(order_no,amount)
SELECT order_no,pay_amount FROM t_order_order WHERE status=5
ON DUPLICATE KEY UPDATE order_no=t_order_refund_request.order_no;
