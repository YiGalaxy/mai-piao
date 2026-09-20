-- ============================================================
-- Convert a lock into a sale.
--
-- Runs after G2 commits (the order is paid, the ledger says "sold").
--
-- KEYS[1] = seat:owner:{scheduleId}
-- KEYS[2] = seat:delay:{scheduleId}
-- KEYS[3] = seat:order:{orderNo}
--
-- ARGV[1] = orderNo
--
-- The bitmap is deliberately left at 1. Bit 1 means "not available", which is
-- equally true of a locked seat and a sold one; clearing it here would make
-- the seat selectable again the moment payment succeeded. What changes is the
-- owner marker, so a later release can tell the two apart, and the removal
-- from the delay zset, so the timeout sweep stops trying to free it.
--
-- Returns the number of seats confirmed.
-- ============================================================

local ownerKey = KEYS[1]
local delayKey = KEYS[2]
local orderKey = KEYS[3]

local orderNo = ARGV[1]
local seats   = redis.call('SMEMBERS', orderKey)

for _, raw in ipairs(seats) do
    local seatIndex = tonumber(raw)
    -- The SOLD: prefix is what makes a paid seat distinguishable from a
    -- merely locked one when a release message arrives late.
    redis.call('HSET', ownerKey, seatIndex, 'SOLD:' .. orderNo)
end

redis.call('ZREM', delayKey, orderNo)

-- Keep the seat index around for a week so the reconciliation job has
-- something to compare the ledger against; after that it expires on its own.
redis.call('EXPIRE', orderKey, 604800)

return #seats
