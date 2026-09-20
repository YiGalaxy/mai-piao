#!/bin/bash
# ============================================================
# A ladder of rush-sale runs at increasing scale, appended to results.txt.
#
# Each rung is a fresh screening, so the numbers are comparable: same 2000
# seats, same four bands, more buyers each time.
#
#   ./rush-ladder.sh [threads] [buyer1 buyer2 ...]
#
# The point of a ladder rather than one big run is that it shows where the
# system stops keeping up. One number tells you whether it worked; a series
# tells you what it costs.
# ============================================================
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"
THREADS="${1:-200}"
shift || true
BUYERS="${*:-500 1000 3000 6000}"

OUT="$DIR/results.txt"
{
  echo "============================================================"
  echo "压测 $(date '+%Y-%m-%d %H:%M:%S')  并发=$THREADS  买家序列=$BUYERS"
  echo "============================================================"
} >> "$OUT"

for N in $BUYERS; do
  echo
  echo "########## 买家 $N ##########"
  bash "$DIR/run-rush.sh" "$N" "$THREADS" queue 2>&1 \
    | iconv -f utf-8 -t utf-8 -c \
    | tee -a "$OUT"
done

echo
echo "完整结果已写入 $OUT"
