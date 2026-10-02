-- ============================================================
-- 把一次加锁变成一次成交。
--
-- 在 G2 提交之后运行（订单已付款，账本上写着"已售"）。
--
-- KEYS[1] = seat:owner:{scheduleId}
-- KEYS[2] = seat:delay:{scheduleId}
-- KEYS[3] = seat:order:{orderNo}
--
-- ARGV[1] = orderNo
--
-- bitmap 上那个 1 是刻意留着的。1 的含义是"不可选"，这对已锁的座位和已售的座位
-- 同样成立；在这里清掉它，支付一成功座位就会重新变成可选的。真正改变的是 owner
-- 标记，好让后续的释放能区分这两者；同时把这个订单从 delay ZSet 里摘掉，让超时
-- 扫描不再试图释放它。
--
-- 返回确认的座位数量。
-- ============================================================

local ownerKey = KEYS[1]
local delayKey = KEYS[2]
local orderKey = KEYS[3]

local orderNo = ARGV[1]
local seats   = redis.call('SMEMBERS', orderKey)

for _, raw in ipairs(seats) do
    local seatIndex = tonumber(raw)
    -- 加 SOLD: 前缀，是为了在一条释放消息迟到时，能把付过钱的座位和只是锁着的
    -- 座位区分开。
    redis.call('HSET', ownerKey, seatIndex, 'SOLD:' .. orderNo)
end

redis.call('ZREM', delayKey, orderNo)

-- 座位索引多留一周，好让对账任务有东西可以拿去和账本比对；之后它会自己过期。
redis.call('EXPIRE', orderKey, 604800)

return #seats
