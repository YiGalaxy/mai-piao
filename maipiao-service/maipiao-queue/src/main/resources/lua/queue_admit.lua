-- ============================================================
-- 从队列里放一批人进来，并给每人发一个令牌。
--
-- 写成脚本而不是 ZPOPMIN 之后再循环，有两个原因，而且只有一个是通常那个。
--
-- 通常那个是原子性：弹出和盖戳不能是可分的，否则两个调度实例同时醒来，各自弹出一个
-- 不同的成员然后都继续 —— 这没问题；或者读到同一个成员然后都继续 —— 这就有问题了。
--
-- 另一个原因是 ZPOPMIN 走不通本项目的 Redis 客户端。Redisson 解不了那个应答，于是
-- 弹出在服务端发生了，客户端却永远拿不到结果 —— 队列里的成员没了，却没有一个人被放进
-- 来。悄无声息地吃掉别人的排队位置，比这个队列本来要防的任何故障都更糟，而它的表象
-- 和"队列是空的"一模一样。
--
-- KEYS[1] = queue:wait:{scheduleId}
-- KEYS[2] = queue:inflight:{scheduleId}
-- KEYS[3] = queue:token:{scheduleId}:   （前缀；后面拼上用户 id）
--
-- ARGV[1] = quota，放多少人进来
-- ARGV[2] = inflight 的 score，即这批准入失效的 epoch 毫秒
-- ARGV[3] = 令牌种子，由调用方生成、无法被猜到的值
-- ARGV[4] = 令牌的 TTL，秒
-- ARGV[5] = inflight key 的 TTL，秒
--
-- 返回一个扁平列表 {userId, token, userId, token, ...}，没有人在等时返回空表。
--
-- 令牌是"种子派生"而不是靠随机，因为 Lua 在这里没有可信的随机源。种子是调用方生成的
-- UUID，所以令牌猜不出来；更重要的是座位服务会拿它和存起来的值比对，因此不管客户端
-- 能不能猜到格式，它都造不出一个有效令牌。
-- ============================================================

local waitKey     = KEYS[1]
local inflightKey = KEYS[2]
local tokenPrefix = KEYS[3]

local quota       = tonumber(ARGV[1])
local expiresAt   = tonumber(ARGV[2])
local seed        = ARGV[3]
local tokenTtl    = tonumber(ARGV[4])
local inflightTtl = tonumber(ARGV[5])

if quota < 1 then
    return {}
end

-- 队首，按到达顺序。ZSet 用加入时间做 score，所以这里取到的是最早到达的那批人，
-- 而不是随便切一段。
local members = redis.call('ZRANGE', waitKey, 0, quota - 1)
if #members == 0 then
    return {}
end

redis.call('ZREM', waitKey, unpack(members))

local admitted = {}
for _, uid in ipairs(members) do
    local token = seed .. '-' .. uid
    redis.call('SET', tokenPrefix .. uid, token, 'EX', tokenTtl)
    redis.call('ZADD', inflightKey, expiresAt, uid)
    admitted[#admitted + 1] = uid
    admitted[#admitted + 1] = token
end

-- inflight 条目用失效时刻做 score，这样回收任务一次范围删除就能清掉令牌已过期的那些。
-- key 本身的过期时间远晚于最后一次可能的准入，所以被遗忘的场次不会一直留着。
redis.call('EXPIRE', inflightKey, inflightTtl)

return admitted
