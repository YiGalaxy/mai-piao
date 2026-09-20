-- ============================================================
-- 从一组候选连座段中分配 N 个相邻座位。
--
-- 与 seat_lock.lua 互为对照，服务于买家不自己挑座的那类销售。大型演唱会不可能让
-- 十万人去浏览座位图，所以买家只选一个票档和一个数量，由系统把座位发下去；
-- "相邻"是买家应得的承诺，不是可有可无的加分项。
--
-- 有两个职责被刻意拆开：
--
--   Java 决定「去哪里找」。场馆几何只有它知道 —— 哪些座位在这个票档里、它们占了
--   哪几排、以及其中哪些在物理上彼此紧邻。这些在这里都表达不出来。
--
--   本脚本决定「这次取座能不能成」。它自己把每一个 bit 重新校验一遍，从不相信
--   候选列表，因为那份列表是基于一次 bitmap 读取算出来的，而脚本跑到这里时那次
--   读取早已过期。候选只用来缩小搜索范围，不构成任何授权。
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
-- 成功时返回 {1, seatIndex, seatIndex, ...}，即拿到的座位；填不满请求时返回
-- {0, longestFreeRun}。第二个值不是摆设：正是靠它调用方才能说"最多能坐 3 个人
-- 一起"，而不是干巴巴一句"不可用"。
--
-- run 指的是「索引连续」和「场馆内相邻」两个条件同时成立的序列。只要有一处断掉
-- Java 就切段，因为过道会在列号上留出空档，两个索引连续的座位可能正好分处过道
-- 两侧。
--
-- 返回座位索引而不是起始位置，原因恰恰是两种模式的区别所在：相邻模式下有起点加
-- 数量就能推出座位，拆分模式下推不出来；返回一个还要调用方自己再推导一遍的位置，
-- 等于邀请两端对"究竟拿了哪些座位"产生分歧。
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

-- ---- 将要查看的每一个 bit，只读一次 ----
--
-- 逐位读的话，每看一个座位就是一次 redis.call。Redis 把脚本作为一个整体执行，
-- 所以这些调用每一个都会阻塞整个实例 —— 几千次下来就是几毫秒的停顿，波及所有在飞
-- 的请求，包括其他场次的抢购。一次 GETRANGE，剩下的都只是 Lua 里的普通字符串运算。
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

-- bit 0 是第 0 个字节的最高位，不是最低位。搞反了就是把整个 bitmap 镜像着读，
-- 在稀疏数据上看着还挺像回事，实际完全错误。
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

-- ---- 扫描，按调用方给连座段排出的顺序 ----
--
-- 两种模式的分水岭就在于「什么时候重置 run 计数器」。相邻模式在每个段边界以及碰到
-- 已占座位时都会重置，于是一段 run 只可能由调用方声明过是邻居的座位拼成。拆分模式
-- 从不重置，这个计数器衡量的就变成"目前为止找到的空位数"，分段只是一个访问顺序。
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
    -- 什么都没写：在此之前整段扫描都是只读的，所以失败时 bitmap 与脚本进来时
    -- 一模一样。
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

-- 售罄是推导出来的，不是数出来的。BITCOUNT 读的就是本脚本刚写下的那些 bit，所以
-- 它不会像另设一个计数器那样与这些 bit 失步 —— 而且万一 bitmap 从账本重建，它
-- 会自愈。释放时通过清掉一个 bit 来清掉这个标记；见 seat_release.lua。
if redis.call('BITCOUNT', mapKey) >= totalSeat then
    redis.call('SET', soldKey, '1')
end

return result
