-- ============================================================
-- Admit a batch from the line and hand each one a token.
--
-- Written as a script rather than ZPOPMIN followed by a loop, for two
-- reasons and only one of them is the usual one.
--
-- The usual one is atomicity: popping and stamping must not be separable, or
-- two dispatcher instances waking together could each pop a different member
-- and both proceed, which is fine, or read the same one and both proceed,
-- which is not.
--
-- The other reason is that ZPOPMIN does not work through this project's Redis
-- client. Redisson cannot decode the reply, so the pop happens on the server
-- and the client never learns the result - the members are gone from the line
-- and nobody was admitted. Silently consuming people's places is worse than
-- any failure the queue is meant to prevent, and it looked exactly like an
-- empty queue.
--
-- KEYS[1] = queue:wait:{scheduleId}
-- KEYS[2] = queue:inflight:{scheduleId}
-- KEYS[3] = queue:token:{scheduleId}:   (prefix; the user id is appended)
--
-- ARGV[1] = quota, how many to admit
-- ARGV[2] = inflight score, the epoch millis at which these admissions lapse
-- ARGV[3] = token seed, a value the caller generated that cannot be guessed
-- ARGV[4] = token time-to-live, seconds
-- ARGV[5] = inflight key time-to-live, seconds
--
-- Returns a flat list of {userId, token, userId, token, ...}, empty when
-- nobody was waiting.
--
-- Tokens are seeded rather than random because Lua has no random source worth
-- trusting here. The seed is a UUID the caller generated, so the token is
-- unguessable; what matters more is that the seat service compares it against
-- the stored value, so a client cannot invent one whether or not it could
-- guess the format.
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

-- The front of the line, in arrival order. A sorted set scores by join time,
-- so this is the earliest arrivals rather than an arbitrary slice.
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

-- In-flight entries score by expiry, so the reaper can drop the ones whose
-- token has lapsed with a range delete. The key itself expires well after the
-- last possible admission, so a forgotten screening does not linger.
redis.call('EXPIRE', inflightKey, inflightTtl)

return admitted
