-- 将锁定转换为已售，在 G2 提交后调用。
--
-- KEYS[1] = seat:owner:{scheduleId}
-- KEYS[2] = seat:delay:{scheduleId}
-- KEYS[3] = seat:order:{orderNo}
--
-- ARGV[1] = orderNo
--
-- bitmap 保持占用；owner 改为 SOLD: 前缀，并移出超时集合。
-- 返回确认数量。

local ownerKey = KEYS[1]
local delayKey = KEYS[2]
local orderKey = KEYS[3]

local orderNo = ARGV[1]
local seats   = redis.call('SMEMBERS', orderKey)

for _, raw in ipairs(seats) do
    local seatIndex = tonumber(raw)
    -- SOLD: 用于区分已售和未支付锁定。
    redis.call('HSET', ownerKey, seatIndex, 'SOLD:' .. orderNo)
end

redis.call('ZREM', delayKey, orderNo)

-- 保留座位索引一周，供后续核对使用。
redis.call('EXPIRE', orderKey, 604800)

return #seats
