#!/bin/bash
# ============================================================
# 一次抢购压测，从一场干净的场次开始。
#
# 把展示用的抢购场次重置掉，放 N 个买家上去抢，然后打印汇总。
# 需要的东西它自己都会找，所以可以重复跑，不用记上一次跑的是什么。
#
#   ./run-rush.sh [buyers] [threads] [queue|direct]
#
#   queue   - 排队、等叫号、再下单（真实链路）
#   direct  - 跳过排队直接打选座接口，也就是排队机制本来要防的那种打法
#
# 结果除了打印出来，还会追加写进 results.txt，这样一整梯次的压测
# 可以直接拿来对比，不必重跑。
# ============================================================
set -u

BUYERS="${1:-1000}"
THREADS="${2:-200}"
MODE="${3:-queue}"

DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL="docker exec maipiao-mysql mysql -uroot -pmaipiao123 -N"
REDIS="docker exec maipiao-redis redis-cli -a maipiao123 --no-auth-warning"

# 展示用的抢购场次。
SESSION=$($MYSQL -e "SELECT id FROM maipiao_event.t_event_session WHERE project_id=1199 AND rush_mode=1 LIMIT 1;" 2>/dev/null | grep -v Warning)
# 用上全部票档，不只第一个：2000 个座位里单个票档只占 400 个，
# 只跑一个档测出来的是这个档满了，而不是整个场子满了。
TIER=$($MYSQL -e "SELECT GROUP_CONCAT(id ORDER BY row_start SEPARATOR ',') FROM maipiao_event.t_event_price_tier WHERE session_id=$SESSION;" 2>/dev/null | grep -v Warning)

if [ -z "$SESSION" ] || [ -z "$TIER" ]; then
  echo "找不到抢购场次，先跑一次："
  echo "  curl -X POST http://127.0.0.1:9002/movie/demo/generate-schedule"
  echo "  curl -X POST http://127.0.0.1:9002/movie/demo/generate-showcase"
  exit 1
fi

TOTAL=$($MYSQL -e "SELECT total_seat FROM maipiao_event.t_event_session WHERE id=$SESSION;" 2>/dev/null | grep -v Warning)
FREE=$($MYSQL -e "SELECT total_seat - locked_seat - sold_seat FROM maipiao_event.t_event_session WHERE id=$SESSION;" 2>/dev/null | grep -v Warning)

echo "重置场次 $SESSION（$TOTAL 座，可用 $FREE）"
$MYSQL -e "DELETE FROM maipiao_event.t_event_session_seat WHERE session_id=$SESSION AND status<>0;" >/dev/null 2>&1
$MYSQL -e "UPDATE maipiao_event.t_event_session SET locked_seat=0, sold_seat=0 WHERE id=$SESSION;" >/dev/null 2>&1
$REDIS DEL "seat:map:$SESSION" "seat:owner:$SESSION" "seat:delay:$SESSION" "sold_out:$SESSION" \
  "queue:wait:$SESSION" "queue:inflight:$SESSION" "queue:session:$SESSION" >/dev/null 2>&1
$REDIS DEL rush:schedules >/dev/null 2>&1

# 种子用户块里的第一个用户 id；种子用户见本目录。
FIRST_USER=2102900000000000000

echo "开始：买家=$BUYERS 并发=$THREADS 模式=$MODE"
java -Dfile.encoding=UTF-8 "$DIR/RushSaleTest.java" \
  "http://127.0.0.1:9000" "$SESSION" "$TIER" "$TOTAL" \
  "$BUYERS" "$THREADS" "$FIRST_USER" "$MODE" || exit 1

# 跑完之后账本怎么说——只有这本账才算数。
echo
echo "=== 账本核对 ==="
$MYSQL -e "
SELECT
  (SELECT COUNT(*) FROM maipiao_event.t_event_session_seat
    WHERE session_id=$SESSION AND status=2)                       AS 已售座位,
  (SELECT COUNT(*) FROM maipiao_event.t_event_session_seat
    WHERE session_id=$SESSION AND status=1)                       AS 锁定座位,
  (SELECT locked_seat FROM maipiao_event.t_event_session WHERE id=$SESSION) AS 计数器锁定,
  (SELECT sold_seat   FROM maipiao_event.t_event_session WHERE id=$SESSION) AS 计数器已售,
  (SELECT total_seat  FROM maipiao_event.t_event_session WHERE id=$SESSION) AS 总座位;" 2>/dev/null | grep -v Warning

echo
echo "=== 位图占位数（应 = 已售 + 锁定）==="
$REDIS BITCOUNT "seat:map:$SESSION" 2>/dev/null

echo "=== 售罄标记（1 = 已置位）==="
$REDIS EXISTS "sold_out:$SESSION" 2>/dev/null

echo
echo "=== 订单表交叉核对 ==="
$MYSQL -e "
SELECT COUNT(*) AS 订单数, IFNULL(SUM(seat_count),0) AS 票数
FROM maipiao_order.t_order_order
WHERE schedule_id=$SESSION AND status IN (0,1,2,3);" 2>/dev/null | grep -v Warning
