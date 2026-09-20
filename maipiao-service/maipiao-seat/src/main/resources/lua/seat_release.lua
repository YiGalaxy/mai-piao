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
-- KEYS[5] = sold_out:{scheduleId}
--
-- ARGV[1] = orderNo
-- ARGV[2] = '1' to force-release seats with no owner (reconciliation repair
--           only - never pass it from a normal flow)
-- ARGV[3] = '1' to also release seats this order has already sold, i.e. those
--           marked SOLD:{orderNo}. For refunds only - see below.
--
-- Returns the number of seats actually released.
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

    -- Free a seat this order still holds, and - only when the caller says so -
    -- one it has already sold.
    --
    -- The two are distinguished by the marker: a held seat carries the order
    -- number, a sold one carries SOLD: prefixed to it. Loosening this to
    -- match either would mean a late timeout message could free a seat that
    -- had been paid for, which is the failure the check exists to prevent.
    -- So a refund asks for the second kind explicitly rather than the default
    -- growing to cover it.
    --
    -- A sold seat really does go back on the market on a refund - and it has
    -- to, or the bit stays set and the seat is unsellable forever while the
    -- ledger says it is free.
    local mine  = owner == orderNo
    local wasMine = includeSold and owner == ('SOLD:' .. orderNo)

    if mine or wasMine or (force and not owner) then
        redis.call('SETBIT', mapKey, seatIndex, 0)
        redis.call('HDEL', ownerKey, seatIndex)
        released = released + 1
    end
end

-- Freeing a seat un-sells-out the screening.
--
-- The flag is set the moment the last bit goes, and without this it would
-- never come back: one cancelled order is enough to leave a screening that
-- still has seats reading as sold out for as long as the key lives. Clearing
-- is unconditional rather than re-derived from BITCOUNT, because at this point
-- the seats have demonstrably been freed.
if released > 0 then
    redis.call('DEL', soldKey)
end

redis.call('DEL', orderKey)
redis.call('ZREM', delayKey, orderNo)

return released
