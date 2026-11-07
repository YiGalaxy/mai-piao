-- 校验和消费一次完成，旧令牌不能删掉后来发放的新令牌。
-- KEYS: 用户令牌、场次在途 ZSET；ARGV: 令牌值、用户 ID。
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[2])
return 1
