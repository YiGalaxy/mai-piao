# 麦票

一个仿猫眼售票系统的票务平台，Java 微服务实现。

复刻的是**核心技术难点**而不是界面：同一座位不能被两人同时选中、订单状态要扛住重复与
乱序的支付回调、大量买家争抢有限座位时系统不能垮。

<!-- CI 已配置在 .github/workflows/ci.yml。推到 GitHub 后，把下面两处 OWNER/REPO
     换成实际地址即可显示绿标；在此之前它是个坏图，所以注释掉了。

[![CI](https://github.com/OWNER/REPO/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/REPO/actions/workflows/ci.yml)

-->

## 技术栈

| | |
|---|---|
| 语言 / 框架 | JDK 21、Spring Boot 3.2.12、Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.3.2 |
| 注册 / 配置 | Nacos 2.4.3 |
| 分布式事务 | Seata 2.2.0（AT 模式） |
| 存储 | MySQL 8.0（按服务拆 schema、库存与座位账本）、Redis 7（座位 Bitmap、抢购队列、售罄标记） |
| 消息预留 | RocketMQ 5.3.1 已配置，订单超时当前由定时扫描处理，未接入消息生产/消费 |
| 前端 | Vue 3 + Vite + Element Plus |

> 本仓库固定并测试上述版本组合。升级 Spring、Cloud Alibaba 或 Seata 时，需要
> 一起核对依赖并做服务启动与集成回归；编译通过不代表运行时兼容。

## 快速开始

以下命令从**项目根目录**运行，SQL 循环使用 Bash（如 Git Bash）。首次启动时，MySQL
会自动执行挂载的 `docs/sql/schema/`；已有数据卷不会重复执行建表脚本。

```bash
# 1. 中间件（Nacos / MySQL / Redis / RocketMQ / Seata）
docker compose -f docker/docker-compose.yml up -d
# 等 MySQL 等依赖就绪；用 docker compose -f docker/docker-compose.yml ps 查看健康状态

# 2. 导入演示数据；表结构只在首次创建 MySQL 数据卷时自动初始化
for f in docs/sql/demo/*.sql; do
  docker exec -i maipiao-mysql mysql -uroot -pmaipiao123 < "$f"
done

# 3. 后端
mvn clean install -DskipTests
# 启动 gateway / user / movie / seat / order / pay / queue / mock-pay
# 仅本地演示时，在启动 movie 服务的终端设置 MAIPIAO_DEMO_ENABLED=true。

# 4. 前端（分别在两个终端运行）
(cd maipiao-web && npm ci && npm run dev)       # 用户端   :5273
(cd maipiao-admin-web && npm ci && npm run dev) # 管理后台 :5274
```

在 PowerShell 中，导入 SQL 请按 [数据库脚本说明](docs/sql/README.md) 的步骤操作。
演示数据接口默认关闭；在运行 movie 服务的终端设置
`$env:MAIPIAO_DEMO_ENABLED="true"` 后启动服务。此开关只能用于本地演示。

| 演示账号 | 手机号 | 密码 |
|---|---|---|
| 管理员 | 13800000000 | 123456 |
| 普通用户 | 13800000001 | 123456 |

演示数据（在启动 movie 服务前显式启用开关，以下为仅供本地使用的直连端口）：

```bash
# 顺序不能反 —— 第一个会清空所有生成数据，第二个只建周杰伦那两场
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-schedule?days=7&soldRatio=0.25&rush=true"
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-showcase"
```

## 服务

| 服务 | 端口 | 职责 |
|---|---|---|
| gateway | 9000 | 统一入口、JWT 与角色校验、抢购流量短路 |
| user | 9001 | 用户、优惠券、角色 |
| movie | 9002 | 影片、场馆、场次、座位账本、管理接口 |
| seat | 9003 | 座位图、锁座、连座分配（Redis Bitmap + Lua） |
| order | 9004 | 订单状态机、下单事务、超时取消、退款 |
| pay | 9005 | 支付、回调幂等、出票、退款 |
| queue | 9008 | 抢购排队与放行 |
| mock-pay | 9007 | 模拟支付渠道（可主动触发重复 / 乱序 / 延迟回调） |

## 文档

- [设计方案](docs/设计方案.md) —— 完整设计，含技术选型理由与并发边界清单
- [历史规划](docs/历史规划.md) —— 保留尚未全部落地的早期九服务方案
- [压测报告](docs/压测报告.md) —— 实测数据、复现方式、瓶颈在哪
- [设计取舍](docs/设计取舍.md) —— 每个关键决策否定了什么，为什么
- [数据库脚本](docs/sql/) —— 结构与演示数据分开
- [压测工具](docs/loadtest/) —— 可直接运行的压测脚本
- [面试准备](docs/面试准备.md) —— 当前实现、购票链路、技术学习路线与追问

压测脚本目前验证的是抢购排队、锁座分配和售罄拒绝路径，**没有执行订单支付**。
可核查的原始阶梯结果见 `docs/loadtest/rush-2000seats-2026-09-20.txt`。
