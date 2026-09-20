-- ============================================================
-- Return every occupied seat index for a screening.
--
-- Doing this with one GETBIT per seat from the client would be one round trip
-- per seat; at a few hundred seats that dominates the request. Inside Redis
-- the same loop is a few hundred in-memory bit reads, which is far cheaper
-- than the network hop it replaces.
--
-- KEYS[1] = seat:map:{scheduleId}
-- ARGV[1] = total number of seats on this screening
--
-- Returns an array of occupied indexes. Indexes are 0-based and contiguous,
-- because that is how they were assigned when the schedule was generated.
-- ============================================================

local mapKey = KEYS[1]
local total  = tonumber(ARGV[1])

local occupied = {}
if total and total > 0 then
    for i = 0, total - 1 do
        if redis.call('GETBIT', mapKey, i) == 1 then
            occupied[#occupied + 1] = i
        end
    end
end

return occupied
