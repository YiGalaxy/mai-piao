#!/bin/bash
# ============================================================
# One rush-sale load run, from a clean screening.
#
# Resets the showcase rush sale, fires N buyers at it, and prints the
# summary. Everything it needs it finds itself, so it can be re-run
# without remembering anything about the last run.
#
#   ./run-rush.sh [buyers] [threads] [queue|direct]
#
#   queue   - join the line, wait to be admitted, then buy (the real path)
#   direct  - skip the line and call the seat endpoint, which is what the
#             line exists to protect against
#
# Results are appended to results.txt as well as printed, so a ladder of
# runs can be compared without re-running them.
# ============================================================
set -u

BUYERS="${1:-1000}"
THREADS="${2:-200}"
MODE="${3:-queue}"

DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL="docker exec maipiao-mysql mysql -uroot -pmaipiao123 -N"
REDIS="docker exec maipiao-redis redis-cli -a maipiao123 --no-auth-warning"

# The showcase rush sale.
SESSION=$($MYSQL -e "SELECT id FROM maipiao_event.t_event_session WHERE project_id=1199 AND rush_mode=1 LIMIT 1;" 2>/dev/null | grep -v Warning)
# Every band, not just the first: one band holds 400 of the 2000 seats,
# so a single-band run measures the band filling up rather than the venue.
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

# First user id of the seeded block; see seed users in this directory.
FIRST_USER=2102900000000000000

echo "开始：买家=$BUYERS 并发=$THREADS 模式=$MODE"
java -Dfile.encoding=UTF-8 "$DIR/RushSaleTest.java" \
  "http://127.0.0.1:9000" "$SESSION" "$TIER" "$TOTAL" \
  "$BUYERS" "$THREADS" "$FIRST_USER" "$MODE" || exit 1

# What the ledger says afterwards, which is the only account that matters.
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
