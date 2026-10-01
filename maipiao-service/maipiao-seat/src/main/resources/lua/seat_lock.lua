-- 原子锁定多座位，避免检查与写入之间的并发窗口。
--
-- KEYS[1] = seat:map:{scheduleId}      Bitmap，第 N 位 = seat_index N 已被占用
-- KEYS[2] = seat:owner:{scheduleId}    hash，field = seat_index，value = orderNo
-- KEYS[3] = seat:delay:{scheduleId}    ZSet，member = orderNo，score = 过期时刻（毫秒）
-- KEYS[4] = seat:order:{orderNo}       本订单持有的座位索引 set
-- KEYS[5] = sold_out:{scheduleId}      最后一个座位卖出时置上
--
-- ARGV[1] = orderNo
-- ARGV[2] = 锁过期时刻，epoch 毫秒
-- ARGV[3] = 该场次总座位数，用于售罄判断
-- ARGV[4..] = 要加锁的座位索引
--
-- 成功返回 {1, lockedCount}；冲突返回 {0, conflictingSeatIndex}。

local mapKey   = KEYS[1]
local ownerKey = KEYS[2]
local delayKey = KEYS[3]
local orderKey = KEYS[4]
local soldKey  = KEYS[5]

local orderNo   = ARGV[1]
local expireTs  = tonumber(ARGV[2])
local totalSeat = tonumber(ARGV[3])

local firstSeat = 4
local lastSeat  = #ARGV
local wanted    = lastSeat - firstSeat + 1

if wanted <= 0 then
    return {0, -1}
end

-- 先校验全部座位，保证全有或全无。
for i = firstSeat, lastSeat do
    local seatIndex = tonumber(ARGV[i])
    if redis.call('GETBIT', mapKey, seatIndex) == 1 then
        return {0, seatIndex}
    end
end

-- Redis 脚本原子执行，校验后立即写入。
for i = firstSeat, lastSeat do
    local seatIndex = tonumber(ARGV[i])
    redis.call('SETBIT', mapKey, seatIndex, 1)
    redis.call('HSET', ownerKey, seatIndex, orderNo)
    redis.call('SADD', orderKey, seatIndex)
end

-- 订单集合设置兜底 TTL；超时回收由 delay ZSet 负责。
redis.call('EXPIRE', orderKey, 7200)
redis.call('ZADD', delayKey, expireTs, orderNo)

-- 售罄标记由 bitmap 推导；释放座位时清除。
if totalSeat > 0 and redis.call('BITCOUNT', mapKey) >= totalSeat then
    redis.call('SET', soldKey, '1')
end

return {1, wanted}
