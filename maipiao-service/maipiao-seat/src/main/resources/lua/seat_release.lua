-- 幂等释放订单座位。
--
-- KEYS[1] = seat:map:{scheduleId}
-- KEYS[2] = seat:owner:{scheduleId}
-- KEYS[3] = seat:delay:{scheduleId}
-- KEYS[4] = seat:order:{orderNo}
-- KEYS[5] = sold_out:{scheduleId}
--
-- ARGV[1] = orderNo
-- ARGV[2] = '1' 表示强行释放没有 owner 标记的座位（只给对账修复用 —— 正常流程
--           绝不能传这个值）
-- ARGV[3] = '1' 表示连本订单已经卖掉的座位一起释放，也就是标记为
--           SOLD:{orderNo} 的那些。只给退款用 —— 见下文。
--
-- 返回实际释放数量。

local mapKey   = KEYS[1]
local ownerKey = KEYS[2]
local delayKey = KEYS[3]
local orderKey = KEYS[4]
local soldKey  = KEYS[5]

local orderNo    = ARGV[1]
local force      = ARGV[2] == '1'
local includeSold = ARGV[3] == '1'

local seats    = redis.call('SMEMBERS', orderKey)
local released = 0

for _, raw in ipairs(seats) do
    local seatIndex = tonumber(raw)
    local owner = redis.call('HGET', ownerKey, seatIndex)

    -- 普通释放匹配订单号；退款可额外匹配 SOLD: 前缀。
    local mine  = owner == orderNo
    local wasMine = includeSold and owner == ('SOLD:' .. orderNo)

    if mine or wasMine or (force and not owner) then
        redis.call('SETBIT', mapKey, seatIndex, 0)
        redis.call('HDEL', ownerKey, seatIndex)
        released = released + 1
    end
end

-- 释放任一座位后清除售罄标记。
if released > 0 then
    redis.call('DEL', soldKey)
end

redis.call('DEL', orderKey)
redis.call('ZREM', delayKey, orderNo)

return released
