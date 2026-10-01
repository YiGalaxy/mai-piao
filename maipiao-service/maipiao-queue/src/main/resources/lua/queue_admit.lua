-- 原子弹出队首用户并生成准入令牌。
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
-- 令牌由调用方生成的种子派生，座位服务会校验其完整值。

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

-- 按到达时间取队首用户。
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

-- inflight 以失效时间为 score，便于范围回收；key 设置兜底 TTL。
redis.call('EXPIRE', inflightKey, inflightTtl)

return admitted
