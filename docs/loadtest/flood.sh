#!/bin/bash
# ============================================================
# 纯粹的拒绝吞吐量：一场已售罄的场次能扛住多少流量。
#
# 这和排队阶梯测的不是一回事。那个测的是等待体验——排队、等叫号、下单——
# 量级控制在真人也参与得进来的程度。这个则把所有涉及人的环节全跳过，
# 只问最外层能多快地说"不"，所谓"一百万请求"说的其实就是这件事。
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

# 故意置成售罄：网关就是读这个标记，不进后端就把请求挡回去，
# 要测的正是这条路径。
$REDIS SET "sold_out:$SESSION" 1 >/dev/null 2>&1

echo "场次 $SESSION 已置为售罄，打 $REQUESTS 个请求，并发 $THREADS"
PER_THREAD=$(( (REQUESTS + THREADS - 1) / THREADS ))

java -Dfile.encoding=UTF-8 "$DIR/RushSaleTest.java" \
  "http://127.0.0.1:9000" "$SESSION" "$TIER" 2000 \
  "$((PER_THREAD * THREADS))" "$THREADS" 2102900000000000000 flood

echo
echo "=== 服务端有没有被打到 ==="
echo "seat-service 最近日志行数: $(wc -l < /tmp/maipiao-seat.log)"
