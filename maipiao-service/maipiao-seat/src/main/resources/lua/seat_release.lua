-- ============================================================
-- Release seats held by an order. Idempotent.
--
-- Called on user cancellation, on payment timeout, and from the G1 rollback
-- path - all of which may run more than once for the same order, and any of
-- which may run after the seats have already been sold to somebody else.
--
-- KEYS[1] = seat:map:{scheduleId}
-- KEYS[2] = seat:owner:{scheduleId}
-- KEYS[3] = seat:delay:{scheduleId}
-- KEYS[4] = seat:order:{orderNo}
--
-- ARGV[1] = orderNo
-- ARGV[2] = '1' to force-release seats with no owner (reconciliation repair
--           only - never pass it from a normal flow)
--
-- Returns the number of seats actually released.
-- ============================================================

local mapKey   = KEYS[1]
local ownerKey = KEYS[2]
local delayKey = KEYS[3]
local orderKey = KEYS[4]

local orderNo = ARGV[1]
local force   = ARGV[2] == '1'

local seats    = redis.call('SMEMBERS', orderKey)
local released = 0

for _, raw in ipairs(seats) do
    local seatIndex = tonumber(raw)
    local owner = redis.call('HGET', ownerKey, seatIndex)

    -- Only free a seat this order still owns.
    --
    -- Without this check, a late timeout message would clear a seat that has
    -- since been sold to someone else - the map would say "available" while
    -- the ledger says "sold", and the next user to pick it would be sold a
    -- seat that already has a ticket.
    if owner == orderNo or (force and not owner) then
        redis.call('SETBIT', mapKey, seatIndex, 0)
        redis.call('HDEL', ownerKey, seatIndex)
        released = released + 1
    end
end

redis.call('DEL', orderKey)
redis.call('ZREM', delayKey, orderNo)

return released
