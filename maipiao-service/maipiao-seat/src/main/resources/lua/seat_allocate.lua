-- ============================================================
-- Assign N adjacent seats from a set of candidate runs.
--
-- The counterpart to seat_lock.lua, for the sales where the buyer does not
-- pick. A large concert cannot let a hundred thousand people browse a seat
-- map, so the buyer chooses a price band and a quantity and the system hands
-- out seats; "adjacent" is a promise the buyer is entitled to, not a nicety.
--
-- Two responsibilities are split deliberately:
--
--   Java decides WHERE to look. It knows the hall geometry - which seats are
--   in the band, which rows they occupy, and which of them are physically
--   next to each other. None of that is expressible here.
--
--   This script decides WHETHER the take succeeds. It re-checks every bit
--   itself and never trusts the candidate list, because the list was computed
--   from a bitmap read that is already stale by the time the script runs. The
--   candidates narrow the search; they do not authorise anything.
--
-- KEYS[1] = seat:map:{scheduleId}     bitmap, bit N = seat_index N taken
-- KEYS[2] = seat:owner:{scheduleId}   hash, field = seat_index, value = orderNo
-- KEYS[3] = seat:delay:{scheduleId}   zset, member = orderNo, score = expiry millis
-- KEYS[4] = seat:order:{orderNo}      set of seat indexes held by this order
-- KEYS[5] = sold_out:{scheduleId}     set when the last seat goes
--
-- ARGV[1] = orderNo
-- ARGV[2] = lock expiry, epoch millis
-- ARGV[3] = how many seats to assign
-- ARGV[4] = total seats in the session, for the sold-out decision
-- ARGV[5] = '1' to require the seats be adjacent, '0' to take any free seats
-- ARGV[6..] = candidate runs, flat pairs of (startIndex, length), in the order
--             they should be tried
--
-- Returns {1, seatIndex, seatIndex, ...} with the seats taken, or
-- {0, longestFreeRun} when it could not fill the request. The second value is
-- not decoration: it is what lets the caller say "at most 3 seats together"
-- instead of "unavailable".
--
-- A run is a sequence that is adjacent BOTH in index and in the hall. Java
-- breaks a run wherever either jumps, because an aisle puts a gap in the
-- column numbering and two seats with consecutive indexes can be on opposite
-- sides of it.
--
-- The returned indexes rather than a start position because the two modes
-- differ in exactly that: adjacent seats are implied by a start and a count,
-- split ones are not, and returning a position that the caller has to
-- re-derive invites the two sides to disagree about what was taken.
-- ============================================================

local mapKey   = KEYS[1]
local ownerKey = KEYS[2]
local delayKey = KEYS[3]
local orderKey = KEYS[4]
local soldKey  = KEYS[5]

local orderNo   = ARGV[1]
local expireTs  = tonumber(ARGV[2])
local want      = tonumber(ARGV[3])
local totalSeat = tonumber(ARGV[4])
local adjacent  = ARGV[5] == '1'

local firstArg = 6
local runCount = math.floor((#ARGV - 5) / 2)
if want < 1 or runCount < 1 then
    return {0, 0}
end

-- ---- one read for every bit we are about to look at ----
--
-- Reading bit by bit would be a redis.call per seat. Redis runs a script as a
-- single unit, so every one of those calls blocks the whole instance - and at
-- a few thousand of them that is milliseconds of stall for every other
-- request in flight, including other screenings' rush sales. One GETRANGE and
-- the rest is ordinary string work in Lua.
local minStart = nil
local maxEnd = -1
for s = 1, runCount do
    local start = tonumber(ARGV[firstArg + (s - 1) * 2])
    local len   = tonumber(ARGV[firstArg + (s - 1) * 2 + 1])
    if minStart == nil or start < minStart then
        minStart = start
    end
    if start + len - 1 > maxEnd then
        maxEnd = start + len - 1
    end
end
if minStart == nil then
    return {0, 0}
end

local firstByte = math.floor(minStart / 8)
local blob = redis.call('GETRANGE', mapKey, firstByte, math.floor(maxEnd / 8))

-- Bit 0 is the most significant bit of byte 0, not the least. Getting this
-- backwards reads the bitmap mirrored, which looks plausible on sparse data
-- and is completely wrong.
local MASKS = {128, 64, 32, 16, 8, 4, 2, 1}

local function occupied(index)
    local byte = string.byte(blob, math.floor(index / 8) - firstByte + 1)
    if not byte then
        -- Past the end of the string, which is past the end of the bitmap.
        -- An unset bit beyond the allocation is free, and so is one on a
        -- bitmap that has never been written.
        return false
    end
    return math.floor(byte / MASKS[(index % 8) + 1]) % 2 == 1
end

-- ---- scan, in the order the caller ranked the runs ----
--
-- The seam between the two modes is what resets the run counter. Adjacent
-- mode resets it at every segment boundary as well as on an occupied seat, so
-- a run can only ever be built from seats the caller declared neighbours.
-- Split mode never resets it, so the counter measures "free seats found so
-- far" and the segments are just a visiting order.
local taken = {}
local longestFree = 0
local run = 0

for s = 1, runCount do
    local start = tonumber(ARGV[firstArg + (s - 1) * 2])
    local len   = tonumber(ARGV[firstArg + (s - 1) * 2 + 1])
    if adjacent then
        run = 0
    end

    for i = start, start + len - 1 do
        if occupied(i) then
            run = 0
        else
            run = run + 1
            if run > longestFree then
                longestFree = run
            end

            if adjacent then
                if run >= want then
                    -- take the whole window, not just the seat that closed it
                    taken = {}
                    for j = i - want + 1, i do
                        taken[#taken + 1] = j
                    end
                    break
                end
            else
                taken[#taken + 1] = i
                if #taken == want then
                    break
                end
            end
        end
    end

    if #taken == want then
        break
    end
end

if #taken < want then
    -- Nothing was written: the scan is read-only until this point, so a
    -- failure leaves the bitmap exactly as it found it.
    return {0, longestFree}
end

local result = {1}
for k = 1, #taken do
    local seatIndex = taken[k]
    redis.call('SETBIT', mapKey, seatIndex, 1)
    redis.call('HSET', ownerKey, seatIndex, orderNo)
    redis.call('SADD', orderKey, seatIndex)
    result[#result + 1] = seatIndex
end
redis.call('EXPIRE', orderKey, 7200)
redis.call('ZADD', delayKey, expireTs, orderNo)

-- Sold out is derived, not counted. BITCOUNT reads the same bits this script
-- just wrote, so it cannot drift out of step with them the way a second
-- counter would - and it self-heals if the bitmap is ever rebuilt from the
-- ledger. A release clears the flag by clearing a bit; see seat_release.lua.
if redis.call('BITCOUNT', mapKey) >= totalSeat then
    redis.call('SET', soldKey, '1')
end

return result
