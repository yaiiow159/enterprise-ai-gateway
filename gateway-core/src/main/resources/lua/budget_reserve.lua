-- 原子地預扣每日成本額度
--
-- 為什麼一定要在 Lua 裡做完：
--   「先讀目前支出、在 Java 端判斷、再寫回」不是原子的。多節點併發下必然超支——
--   一百個請求同時讀到「還有額度」，然後一起寫入。這和限流的超賣是同一個問題，
--   唯一的解法是把讀與寫收進同一次 Redis 執行。
--
-- 為什麼兩個 key 可以放在同一支腳本：
--   KEYS[1] 與 KEYS[2] 都帶同一個 hash tag（`{budget:<日期>}`），
--   在 Redis Cluster 下會落在同一個 slot。沒有 hash tag 的話跨 slot 會直接被拒絕。
--
-- KEYS[1] = 全域支出 key
-- KEYS[2] = 該租戶支出 key
-- ARGV[1] = 要預扣的金額（微美分，整數）
-- ARGV[2] = 全域上限；0 表示不限
-- ARGV[3] = 單租戶上限；0 表示不限
-- ARGV[4] = key 的存活秒數
--
-- 回傳 { 是否成功(1/0), 全域支出, 租戶支出, 觸發的上限(0=全域 1=租戶) }

local amount = tonumber(ARGV[1])
local global_limit = tonumber(ARGV[2])
local tenant_limit = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local global_after = redis.call('INCRBY', KEYS[1], amount)
local tenant_after = redis.call('INCRBY', KEYS[2], amount)

-- TTL 每次都重設：只在建立時設的話，長時間持續有流量的 key 反而可能提前過期，
-- 讓額度在一天中途莫名其妙重置。這和限流窗口的 TTL 是同一個坑。
redis.call('EXPIRE', KEYS[1], ttl)
redis.call('EXPIRE', KEYS[2], ttl)

if global_limit > 0 and global_after > global_limit then
    redis.call('DECRBY', KEYS[1], amount)
    redis.call('DECRBY', KEYS[2], amount)
    return { 0, global_after - amount, tenant_after - amount, 0 }
end

if tenant_limit > 0 and tenant_after > tenant_limit then
    redis.call('DECRBY', KEYS[1], amount)
    redis.call('DECRBY', KEYS[2], amount)
    return { 0, global_after - amount, tenant_after - amount, 1 }
end

return { 1, global_after, tenant_after, -1 }
