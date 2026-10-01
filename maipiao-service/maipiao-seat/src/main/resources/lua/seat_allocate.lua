-- 按候选连座段原子分配座位。Java 负责生成候选，脚本负责校验并写入 bitmap。
--
-- KEYS[1] = seat:map:{scheduleId}     Bitmap，第 N 位 = seat_index N 已被占用
-- KEYS[2] = seat:owner:{scheduleId}   hash，field = seat_index，value = orderNo
-- KEYS[3] = seat:delay:{scheduleId}   ZSet，member = orderNo，score = 过期时刻（毫秒）
-- KEYS[4] = seat:order:{orderNo}      本订单持有的座位索引 set
-- KEYS[5] = sold_out:{scheduleId}     最后一个座位卖出时置上
--
-- ARGV[1] = orderNo
-- ARGV[2] = 锁过期时刻，epoch 毫秒
-- ARGV[3] = 要分配几个座位
-- ARGV[4] = 该场次总座位数，用于售罄判断
-- ARGV[5] = '1' 表示要求座位相邻，'0' 表示随便拿空位
-- ARGV[6..] = 候选连座段，扁平的 (startIndex, length) 数对，按尝试顺序排列
--
-- 成功返回 {1, seatIndex...}；失败返回 {0, longestFreeRun}。

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

-- 一次 GETRANGE 读取 bitmap，避免逐位调用 Redis。
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

-- Redis bitmap 的 bit 0 位于字节最高位。
local MASKS = {128, 64, 32, 16, 8, 4, 2, 1}

local function occupied(index)
    local byte = string.byte(blob, math.floor(index / 8) - firstByte + 1)
    if not byte then
        -- 读到了字符串末尾之外，也就是 bitmap 末尾之外。分配范围之外的 bit 视为
        -- 未置位，也就是空位；一张从未被写过的 bitmap 上的 bit 同理。
        return false
    end
    return math.floor(byte / MASKS[(index % 8) + 1]) % 2 == 1
end

-- 按候选顺序扫描；adjacent 决定是否在段边界重置连续计数。
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
                    -- 取整个窗口，而不是只取把它凑满的那一个座位
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
-- 扫描阶段只读，失败时不修改 bitmap。
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

-- 售罄标记由 bitmap 推导，释放座位时清除。
if redis.call('BITCOUNT', mapKey) >= totalSeat then
    redis.call('SET', soldKey, '1')
end

return result
