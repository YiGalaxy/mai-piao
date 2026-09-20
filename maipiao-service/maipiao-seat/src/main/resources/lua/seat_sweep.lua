-- ============================================================
-- 找出某个场次里已经到期的持有，顺便决定这个场次还要不要继续被扫描。
--
-- 为什么需要它：`seat_lock.lua` 一直忠实地把每个持有的过期时刻写进 `seat:delay`
-- 这个 ZSet，`seat_confirm.lua` 和 `seat_release.lua` 也都会把它摘掉 —— 数据一直是
-- 对的，缺的只是读它的人。没有读的人，一个用户锁了座、关掉页面、不下单，
-- 那个座位就永久占死了：bitmap 和 owner hash 都不带 TTL，没有任何东西会去清它。
--
-- 只负责"找出来"，不负责"释放"。释放走 seat_release.lua，那条路径上带着
-- owner 校验，并且会被取消、退款、G1 回滚共用 —— 一套释放逻辑只能有一个实现。
--
-- KEYS[1] = seat:delay:{scheduleId}
-- KEYS[2] = seat:active
--
-- ARGV[1] = scheduleId
-- ARGV[2] = 当前时刻，epoch 毫秒
-- ARGV[3] = 单轮上限
--
-- 返回已到期的 orderNo 列表。
-- ============================================================

local delayKey  = KEYS[1]
local activeKey = KEYS[2]

local scheduleId = ARGV[1]
local now        = tonumber(ARGV[2])
local limit      = tonumber(ARGV[3])

local expired = redis.call('ZRANGEBYSCORE', delayKey, 0, now, 'LIMIT', 0, limit)

-- 这个场次没有到期的持有，而且一条持有都不剩了 —— 可以让它退场，以后的扫描
-- 不必再为它跑一趟。
--
-- 判断必须和上面的读取在同一段脚本里。拆成「查 ZSet 空了没」和「从名单里删掉」
-- 两步的话，一次并发的加锁可以恰好插在中间：那个新持有于是属于一个已经退场的
-- 场次，从此没人来收它。放进脚本之后，Redis 的单线程执行保证这两者之间插不进
-- 任何东西 —— 要么加锁整个发生在判断之前（ZCARD 不为 0，不退场），
-- 要么整个发生在之后（SADD 把场次重新加回来）。
--
-- 注意 active 集合只增不减是不行的：那等于把内存泄漏换成了一次越扫越慢的遍历。
if #expired == 0 and redis.call('ZCARD', delayKey) == 0 then
    redis.call('SREM', activeKey, scheduleId)
end

return expired
