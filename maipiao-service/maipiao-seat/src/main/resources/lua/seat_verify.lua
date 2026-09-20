-- ============================================================
-- Check that a hold is still this order's.
--
-- Called at the start of order creation, before the G1 transaction opens.
-- Without it the lock token is taken at face value, and a token whose hold
-- lapsed - the browser tab left open past the 15-minute hold, then submitted -
-- still produces an order. The ledger compare-and-set stops that from
-- overselling, but it does so by rejecting whoever holds the seat now, so the
-- user who actually won the seat loses it to one holding a dead token.
--
-- KEYS[1] = seat:owner:{scheduleId}
--
-- ARGV[1] = orderNo
-- ARGV[2..] = seat indexes the order claims
--
-- Returns 1 when every seat is owned by this order, 0 otherwise.
--
-- One script rather than a read per seat: the seats are a single claim, and
-- checking them separately would let a release land between two of the reads
-- and produce a verdict that was never true at any instant.
-- ============================================================

local ownerKey = KEYS[1]
local orderNo  = ARGV[1]

for i = 2, #ARGV do
    local owner = redis.call('HGET', ownerKey, ARGV[i])
    if owner ~= orderNo then
        return 0
    end
end

return 1
