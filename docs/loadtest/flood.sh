#!/bin/bash
# ============================================================
# Raw rejection throughput: how much traffic a sold-out sale absorbs.
#
# Different question from the queue ladder. That one measures the waiting
# experience - join, wait, buy - at a scale a person could be part of. This
# one skips every step that involves a human and asks how fast the edge can
# say no, which is what "a million requests" actually means.
#
#   ./flood.sh [requests] [threads]
# ============================================================
set -u

REQUESTS="${1:-100000}"
THREADS="${2:-400}"

DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL="docker exec maipiao-mysql mysql -uroot -pmaipiao123 -N"
REDIS="docker exec maipiao-redis redis-cli -a maipiao123 --no-auth-warning"

SESSION=$($MYSQL -e "SELECT id FROM maipiao_event.t_event_session WHERE project_id=1199 AND rush_mode=1 LIMIT 1;" 2>/dev/null | grep -v Warning)
TIER=$($MYSQL -e "SELECT GROUP_CONCAT(id ORDER BY row_start SEPARATOR ',') FROM maipiao_event.t_event_price_tier WHERE session_id=$SESSION;" 2>/dev/null | grep -v Warning)

# Sold out on purpose: the flag is what the gateway reads to turn a request
# away without a backend call, and the point is to measure that path.
$REDIS SET "sold_out:$SESSION" 1 >/dev/null 2>&1

echo "场次 $SESSION 已置为售罄，打 $REQUESTS 个请求，并发 $THREADS"
PER_THREAD=$(( (REQUESTS + THREADS - 1) / THREADS ))

java -Dfile.encoding=UTF-8 "$DIR/RushSaleTest.java" \
  "http://127.0.0.1:9000" "$SESSION" "$TIER" 2000 \
  "$((PER_THREAD * THREADS))" "$THREADS" 2102900000000000000 flood

echo
echo "=== 服务端有没有被打到 ==="
echo "seat-service 最近日志行数: $(wc -l < /tmp/maipiao-seat.log)"
