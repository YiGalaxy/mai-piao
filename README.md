# 麦票

仿猫眼售票系统的票务平台，Java 微服务实现。

复刻的是**核心技术难点**而不是界面：同一座位不能被两人同时选中、订单状态要扛住重复与
乱序的支付回调、两千张票对上十万买家时系统不能垮。

<!-- CI 已配置在 .github/workflows/ci.yml。推到 GitHub 后，把下面的 OWNER/REPO
     换成实际地址、去掉注释即可。在那之前它是张裂图，所以先藏着。

[![CI](https://github.com/OWNER/REPO/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/REPO/actions/workflows/ci.yml)

-->

---

## 快速开始

需要 JDK 21、Maven 3.9+、Node 20+、Docker。

```bash
git clone <repo> && cd maipiao

# 1. 中间件：Nacos / MySQL / Redis / RocketMQ / Seata
cd docker && docker compose up -d && cd ..

# 等 MySQL 真正能接受连接再往下走。容器 up 不等于服务就绪，
# 通常还要二十来秒 —— 不等待的话下一步的建表会连不上。
until docker exec maipiao-mysql mysqladmin ping -uroot -pmaipiao123 --silent >/dev/null 2>&1; do
  sleep 2
done

# 2. 建结构，再灌演示数据（两样分开，理由见 docs/sql/README.md）
for f in docs/sql/schema/*.sql docs/sql/demo/*.sql; do
  docker exec -i maipiao-mysql mysql -uroot -pmaipiao123 < "$f"
done

# 3. 构建并启动后端（脚本会等服务真的能响应再退出）
mvn clean install -DskipTests
./scripts/start-all.sh

# 4. 生成排期。顺序不能反 —— 第一个清空所有生成数据，第二个只建周杰伦那两场。
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-schedule?days=7&soldRatio=0.25&rush=true"
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-showcase"

# 5. 前端
cd maipiao-web       && npm install && npm run dev   # 用户端   http://localhost:5273
cd maipiao-admin-web && npm install && npm run dev   # 管理后台 http://localhost:5274
```

| 账号 | 手机号 | 密码 |
|---|---|---|
| 管理员 | 13800000000 | 123456 |
| 普通用户 | 13800000001 | 123456 |

> `docker compose up -d --wait` 在这套编排上**不可用** —— 它把健康检查启动宽限期里的
> `starting` 当成失败，seata 会在十几秒时被误判，而它实际 24 秒后就健康了。
> 上面那段显式等待是可靠的。

后端常用操作：

```bash
./scripts/start-all.sh              # 启动（已在跑的会跳过）
./scripts/start-all.sh --stop       # 全部停掉
./scripts/start-all.sh maipiao-pay  # 只启某一个
tail -f /tmp/maipiao-order.log      # 看某个服务的日志
```

---

## 服务

| 服务 | 端口 | 职责 |
|---|---|---|
| gateway | 9000 | 统一入口、JWT 与角色校验、抢购流量短路 |
| user | 9001 | 用户、优惠券、角色 |
| movie | 9002 | 影片、场馆、场次、座位账本、管理接口 |
| seat | 9003 | 座位图、锁座、连座分配、超时回收（Redis Bitmap + Lua） |
| order | 9004 | 订单状态机、下单事务、超时取消、退款（RocketMQ 延时消息） |
| pay | 9005 | 支付、回调幂等、出票、退款 |
| queue | 9008 | 抢购排队与放行 |
| mock-pay | 9007 | 模拟支付渠道（可主动触发重复 / 乱序 / 延迟回调） |

## 技术栈

| | |
|---|---|
| 语言 / 框架 | JDK 21、Spring Boot 3.2.12、Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.3.2 |
| 注册 / 配置 | Nacos 2.4.3 |
| 分布式事务 | Seata 2.2.0（AT 模式） |
| 消息 | RocketMQ 5.3.1（订单超时的任意时刻延时消息） |
| 存储 | MySQL 8.0（按服务分库）、Redis 7（座位 Bitmap、库存、抢购队列） |
| 定时任务 | `@Scheduled` + ShedLock 5.13（Redis 实现，多实例互斥） |
| 前端 | Vue 3 + Vite + Element Plus |

**版本是一条锁死的链**：Spring Boot 3.2 只能配 Spring Cloud 2023.0.x，后者只能配
SCA 2023.0.3.2，而 Seata 客户端必须与服务端同版本。任何一处单独升级，编译能过、启动会炸。

## 文档

| | |
|---|---|
| [设计方案](docs/设计方案.md) | 完整设计：架构、并发边界清单、定时任务、验证方式 |
| [设计取舍](docs/设计取舍.md) | 每个关键决策否定了什么、为什么 |
| [压测报告](docs/压测报告.md) | 实测数据、复现方式、瓶颈在哪一层 |
| [数据库脚本](docs/sql/README.md) | 结构与演示数据为什么分开 |
| [压测工具](docs/loadtest/README.md) | 可直接运行的压测脚本 |

## 目录

```
maipiao-common/     公共模块：统一返回体、异常、JWT、Redis、Lua 加载器
maipiao-gateway/    网关：JWT 校验、角色校验、抢购短路
maipiao-service/    七个业务服务
maipiao-mock-pay/   模拟支付渠道
maipiao-web/        用户端 Vue 3
maipiao-admin-web/  管理后台 Vue 3
docs/               设计、取舍、压测、SQL、压测工具
docker/             中间件编排
scripts/            启动脚本
```
