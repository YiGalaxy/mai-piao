# 麦票

一个仿猫眼售票系统的票务平台，Java 微服务实现。

复刻的是**核心技术难点**而不是界面：同一座位不能被两人同时选中、订单状态要扛住重复与
乱序的支付回调、两千张票对上十万买家时系统不能垮。

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
| 存储 | MySQL 8.0（按服务分库）、Redis 7（座位 Bitmap、库存、抢购队列） |
| 消息 | RocketMQ 5.3.1（延迟消息） |
| 前端 | Vue 3 + Vite + Element Plus |

> 版本是**锁死的链条**：Spring Boot 3.2 只能配 Spring Cloud 2023.0.x，后者只能配
> SCA 2023.0.3.2，Seata 客户端又必须与服务端同版本。任何一处单独升级、编译能过但
> 启动会炸。详见 [docs/设计方案.md](docs/设计方案.md)。

## 快速开始

```bash
# 1. 中间件（Nacos / MySQL / Redis / RocketMQ / Seata）
cd docker && docker compose up -d

# 2. 建表与种子数据
for f in docs/sql/schema/*.sql docs/sql/demo/*.sql; do
  docker exec -i maipiao-mysql mysql -uroot -pmaipiao123 < "$f"
done

# 3. 后端
mvn clean install -DskipTests
# 依次启动 gateway / user / movie / seat / order / pay / queue / mock-pay

# 4. 前端
cd maipiao-web       && npm install && npm run dev   # 用户端   :5273
cd maipiao-admin-web && npm install && npm run dev   # 管理后台 :5274
```

| 演示账号 | 手机号 | 密码 |
|---|---|---|
| 管理员 | 13800000000 | 123456 |
| 普通用户 | 13800000001 | 123456 |

演示数据：

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
- [压测报告](docs/压测报告.md) —— 实测数据、复现方式、瓶颈在哪
- [设计取舍](docs/设计取舍.md) —— 每个关键决策否定了什么，为什么
- [数据库脚本](docs/sql/) —— 结构与演示数据分开
- [压测工具](docs/loadtest/) —— 可直接运行的压测脚本
