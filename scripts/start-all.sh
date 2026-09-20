#!/bin/bash
# ============================================================
# 启动全部后端服务。
#
# 存在的原因很简单：八个服务、八条 java -jar，而顺序里有两处会让人卡住 ——
# gateway 要先起来（它做服务发现的下游），而每个服务都要等 Nacos 就绪。
# 把这些写进一个脚本，比在 README 里用「依次启动」带过要好。
#
#   ./scripts/start-all.sh [服务名 ...]     只启指定的，不给就全启
#   ./scripts/start-all.sh --stop           停掉全部
#
# 日志写到 /tmp/maipiao-<服务名>.log（Windows 上 Git Bash 会映射到临时目录）。
# 要在前台看某个服务的输出，直接 tail 那个文件。
# ============================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 名字:模块目录。顺序有意义 —— gateway 最先，其余随意。
SERVICES=(
  "maipiao-gateway:maipiao-gateway"
  "maipiao-user:maipiao-service/maipiao-user"
  "maipiao-movie:maipiao-service/maipiao-movie"
  "maipiao-seat:maipiao-service/maipiao-seat"
  "maipiao-order:maipiao-service/maipiao-order"
  "maipiao-pay:maipiao-service/maipiao-pay"
  "maipiao-queue:maipiao-service/maipiao-queue"
  "maipiao-mock-pay:maipiao-mock-pay"
)

# 端口，用来判断「已经在跑」和「起来了没有」。
port_of() {
  case "$1" in
    maipiao-gateway)  echo 9000 ;;
    maipiao-user)     echo 9001 ;;
    maipiao-movie)    echo 9002 ;;
    maipiao-seat)     echo 9003 ;;
    maipiao-order)    echo 9004 ;;
    maipiao-pay)      echo 9005 ;;
    maipiao-mock-pay) echo 9007 ;;
    maipiao-queue)    echo 9008 ;;
  esac
}

# ------------------------------------------------------------

if [ "${1:-}" = "--stop" ]; then
  for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    port=$(port_of "$name")
    pid=$(netstat -ano 2>/dev/null | grep ":$port " | grep -i listening | head -1 | awk '{print $NF}')
    if [ -n "$pid" ]; then
      taskkill //F //PID "$pid" >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null
      echo "stopped $name ($port)"
    fi
  done
  exit 0
fi

# 不给参数就全启；给了就只启那几个。
wanted=("$@")
if [ ${#wanted[@]} -eq 0 ]; then
  wanted=()
  for entry in "${SERVICES[@]}"; do
    wanted+=("${entry%%:*}")
  done
fi

started=0
for entry in "${SERVICES[@]}"; do
  name="${entry%%:*}"
  dir="${entry##*:}"

  keep=0
  for w in "${wanted[@]}"; do
    [ "$w" = "$name" ] && keep=1
  done
  [ "$keep" = 0 ] && continue

  jar="$ROOT/$dir/target/${name}-1.0.0.jar"
  if [ ! -f "$jar" ]; then
    echo "跳过 $name：$jar 不存在，先跑 mvn clean install -DskipTests"
    continue
  fi

  port=$(port_of "$name")
  if netstat -ano 2>/dev/null | grep ":$port " | grep -qi listening; then
    echo "跳过 $name：$port 已被占用（可能已经在跑）"
    continue
  fi

  # 在子 shell 里 cd，好让脚本自己的目录不受影响。
  #
  # 日志名就是 "$name.log" —— $name 本身已经是 maipiao-order 这种带前缀的全名了，
  # 这里再拼一次 maipiao- 会写出 maipiao-maipiao-order.log，而下面提示的、README 里
  # 写的都是单前缀那个路径，于是「服务起不来，去哪里看日志」这件事直接失效。
  ( cd "$ROOT/$dir" && nohup java -jar "$jar" > "/tmp/$name.log" 2>&1 & )
  echo "启动 $name -> :$port"
  started=$((started + 1))

  # 起来一个再起下一个。并行启动会让八个 JVM 同时抢 CPU 和 Nacos 连接，
  # 在开发机上反而更慢，而日志也更难看懂。
  sleep 2
done

[ "$started" = 0 ] && { echo "没有需要启动的服务"; exit 0; }

# ------------------------------------------------------------
# 等待就绪。
#
# 起进程和能服务是两件事：Spring 上下文要连 Nacos、连库、连 Redis，
# 全做完才第一次响应 /actuator/health。脚本在这里等一下，是为了让
# 「脚本跑完」等于「可以用了」，而不是「等一会儿再试试」。
# ------------------------------------------------------------

echo
echo "等待就绪（最多 120 秒）..."
deadline=$((SECONDS + 120))
for entry in "${SERVICES[@]}"; do
  name="${entry%%:*}"
  port=$(port_of "$name")

  keep=0
  for w in "${wanted[@]}"; do
    [ "$w" = "$name" ] && keep=1
  done
  [ "$keep" = 0 ] && continue

  while [ $SECONDS -lt $deadline ]; do
    if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$port/actuator/health"; then
      echo "  ✓ $name"
      break
    fi
    sleep 2
  done
  if [ $SECONDS -ge $deadline ]; then
    echo "  ✗ $name 超时，看 /tmp/maipiao-$name.log"
  fi
done
