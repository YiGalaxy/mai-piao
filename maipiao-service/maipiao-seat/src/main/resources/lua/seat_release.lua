-- ============================================================
-- 释放某个订单持有的座位。幂等。
--
-- 在用户取消、支付超时、以及 G1 回滚路径上都会被调用 —— 这些路径对同一个订单都可能
-- 跑不止一次，而且其中任何一次都可能发生在座位已经卖给别人的时候。
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
-- 返回真正被释放的座位数量。
-- ============================================================

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

    -- 释放本订单还持有的座位，以及 —— 仅在调用方明确要求时 —— 它已经卖掉的座位。
    --
    -- 两者靠 owner 标记区分：持有的座位存的就是订单号，已售的座位前面多了个
    -- SOLD: 前缀。把判断放宽成两者都匹配，就意味着一条迟到的超时消息可以释放掉一个
    -- 已经付过钱的座位，而这个检查存在的意义正是防止这件事。所以退款要显式点名要
    -- 第二类，而不是让默认行为慢慢扩张到把两类都覆盖进去。
    --
    -- 已售座位在退款时确实要重新回到市场上 —— 而且必须回来，否则那个 bit 一直置着，
    -- 座位永远卖不出去，而账本上却显示它是空着的。
    local mine  = owner == orderNo
    local wasMine = includeSold and owner == ('SOLD:' .. orderNo)

    if mine or wasMine or (force and not owner) then
        redis.call('SETBIT', mapKey, seatIndex, 0)
        redis.call('HDEL', ownerKey, seatIndex)
        released = released + 1
    end
end

-- 释放座位等于把这场次从"售罄"里撤回来。
--
-- 这个标记在最后一个 bit 被占走的瞬间就置上了，没有这一段它就再也回不来：只要有一
-- 个订单被取消，一个明明还有空座的场次就会在 key 存活期内一直显示售罄。这里是无条件
-- 清除，而不是再拿 BITCOUNT 推导一遍，因为走到这一步时座位已经确实被释放了。
if released > 0 then
    redis.call('DEL', soldKey)
end

redis.call('DEL', orderKey)
redis.call('ZREM', delayKey, orderNo)

return released
