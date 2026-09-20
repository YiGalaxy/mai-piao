-- ============================================================
-- Atomic multi-seat lock.
--
-- This is the single arbitration point for the whole system: it is the only
-- place where "is this seat free" and "mark it taken" happen together. Redis
-- runs a script as one unit, so no other command can slip between the check
-- and the write. Doing the same with separate GETBIT and SETBIT calls leaves
-- a window in which two users both see the seat as free and both take it.
--
-- KEYS[1] = seat:map:{scheduleId}      bitmap, bit N = seat_index N taken
-- KEYS[2] = seat:owner:{scheduleId}    hash, field = seat_index, value = orderNo
-- KEYS[3] = seat:delay:{scheduleId}    zset, member = orderNo, score = expiry millis
-- KEYS[4] = seat:order:{orderNo}       set of seat indexes held by this order
--
-- ARGV[1] = orderNo
-- ARGV[2] = lock expiry, epoch millis
-- ARGV[3..] = seat indexes to lock
--
-- Returns {1, lockedCount} on success, or {0, conflictingSeatIndex} when any
-- requested seat is already taken. The conflicting index lets the client
-- highlight exactly which seat went, instead of failing with "try again".
-- ============================================================

local mapKey   = KEYS[1]
local ownerKey = KEYS[2]
local delayKey = KEYS[3]
local orderKey = KEYS[4]

local orderNo  = ARGV[1]
local expireTs = tonumber(ARGV[2])

local firstSeat = 3
local lastSeat  = #ARGV
local wanted    = lastSeat - firstSeat + 1

if wanted <= 0 then
    return {0, -1}
end

-- ---- pass 1: verify every seat, without writing anything ----
--
-- All-or-nothing: a partial lock would leave the user holding a seat they
-- never confirmed, and no one else able to book it until the lock expires.
for i = firstSeat, lastSeat do
    local seatIndex = tonumber(ARGV[i])
    if redis.call('GETBIT', mapKey, seatIndex) == 1 then
        return {0, seatIndex}
    end
end

-- ---- pass 2: occupy them all ----
--
-- No command runs between pass 1 and pass 2, so nothing observed as free in
-- pass 1 can have been taken by the time we get here.
for i = firstSeat, lastSeat do
    local seatIndex = tonumber(ARGV[i])
    redis.call('SETBIT', mapKey, seatIndex, 1)
    redis.call('HSET', ownerKey, seatIndex, orderNo)
    redis.call('SADD', orderKey, seatIndex)
end

-- Safety net: if the order flow dies before confirming or releasing, the key
-- disappears on its own rather than leaving seats locked forever. The real
-- timeout path is the delay zset below; this only bounds the damage.
redis.call('EXPIRE', orderKey, 7200)
redis.call('ZADD', delayKey, expireTs, orderNo)

return {1, wanted}
