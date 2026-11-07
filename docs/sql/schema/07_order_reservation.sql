-- 新装自动执行；已有数据库也可单独执行此文件，无需删除数据卷。
USE maipiao_order;
CREATE TABLE IF NOT EXISTS t_order_reservation (
    token VARCHAR(64) NOT NULL PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    seat_indexes VARCHAR(256) NULL,
    seat_labels VARCHAR(1024) NULL,
    expires_at BIGINT NOT NULL COMMENT '首次锁座的 epoch 毫秒截止时间',
    state VARCHAR(16) NOT NULL,
    request_hash VARCHAR(64) NULL,
    xid VARCHAR(128) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_reservation_state_expire(state, expires_at),
    INDEX idx_reservation_updated(update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
