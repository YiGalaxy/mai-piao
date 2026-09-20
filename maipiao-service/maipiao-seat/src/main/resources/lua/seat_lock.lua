-- ============================================================
-- 原子的多座位加锁。
--
-- 这是整个系统唯一的仲裁点：只有在这里，"这个座位空着吗"和"把它标记为占用"才是
-- 一起发生的。Redis 把脚本当作一个整体执行，所以没有任何其他命令能插进检查和写入
-- 之间。用分开的 GETBIT 和 SETBIT 去做同样的事，会留下一段窗口期，两个用户都看到
-- 座位是空的，然后都把它拿走。
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
-- 成功返回 {1, lockedCount}；只要有一个请求的座位已被占，就返回
-- {0, conflictingSeatIndex}。给出冲突索引是为了让客户端能精确高亮是哪一个座位没了，
-- 而不是笼统地回一句"请重试"。
-- ============================================================

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

-- ---- pass 1: 校验每一个座位，不写任何东西 ----
--
-- 全有或全无：部分加锁会让用户攥着一个他并没有确认过的座位，而别人也订不了它，
-- 一直要等到锁过期。
for i = firstSeat, lastSeat do
    local seatIndex = tonumber(ARGV[i])
    if redis.call('GETBIT', mapKey, seatIndex) == 1 then
        return {0, seatIndex}
    end
end

-- ---- pass 2: 把它们全部占下 ----
--
-- pass 1 和 pass 2 之间没有任何命令能执行，所以 pass 1 里看到是空的座位，到不了
-- 这里就被人抢走。
for i = firstSeat, lastSeat do
    local seatIndex = tonumber(ARGV[i])
    redis.call('SETBIT', mapKey, seatIndex, 1)
    redis.call('HSET', ownerKey, seatIndex, orderNo)
    redis.call('SADD', orderKey, seatIndex)
end

-- 兜底：万一订单流程在确认或释放之前就挂了，这个 key 会自己消失，而不是让座位
-- 永远锁着。真正的超时回收路径是下面的 delay ZSet；这里只是给损失设个上界。
redis.call('EXPIRE', orderKey, 7200)
redis.call('ZADD', delayKey, expireTs, orderNo)

-- 售罄状态由本脚本刚写下的那些 bit 推导得出，所以不会像另设一个计数器那样与它们
-- 失步，而且 bitmap 从账本重建时它会自愈。座位回退时 seat_release.lua 会再把它
-- 清掉。
if totalSeat > 0 and redis.call('BITCOUNT', mapKey) >= totalSeat then
    redis.call('SET', soldKey, '1')
end

return {1, wanted}
