-- ============================================================
-- 返回某场次全部已被占用的座位索引。
--
-- 放在客户端做、每个座位一次 GETBIT，就是每个座位一个来回；几百个座位时这部分开销
-- 会盖过请求本身的其余部分。搬进 Redis 里，同一个循环就只是几百次内存中的 bit 读取，
-- 比它替换掉的那次网络往返便宜得多。
--
-- KEYS[1] = seat:map:{scheduleId}
-- ARGV[1] = 该场次的总座位数
--
-- 返回已占用索引的数组。索引从 0 开始且连续，因为场次生成时它们就是这么分配的。
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
