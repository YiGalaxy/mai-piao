# 麦票

麦票是一个基于 Java 微服务的票务系统学习项目，业务覆盖用户、影片/演出、场馆、座位、订单、支付和抢购排队。

项目重点是高并发售票链路中的一致性问题：座位锁定、库存扣减、订单状态迁移、支付回调幂等，以及有限库存下的流量控制。

## 项目结构

| 服务 | 端口 | 主要职责 |
|---|---:|---|
| gateway | 9000 | 统一入口、JWT 校验、角色鉴权、抢购请求拦截 |
| user | 9001 | 用户、登录、角色、优惠券 |
| movie | 9002 | 影片、场馆、场次、座位账本和后台管理 |
| seat | 9003 | 座位图、锁座、连座分配和 Redis 状态维护 |
| order | 9004 | 订单创建、状态机、取消、超时处理和退款协调 |
| pay | 9005 | 支付单、支付回调、出票和退款 |
| mock-pay | 9007 | 模拟支付渠道，支持触发重复、乱序和延迟回调 |
| queue | 9008 | 抢购排队、令牌发放和准入校验 |

## 核心购票链路

1. 用户通过网关查询场次和座位。
2. `seat-service` 使用 Redis Bitmap 和 Lua 脚本原子锁定座位，返回锁座令牌。
3. `order-service` 在 Seata AT 全局事务中校验锁座状态，扣减场次库存、锁定优惠券并写入订单。
4. `pay-service` 创建支付单并接收支付渠道回调。
5. 支付回调经过订单状态机校验，成功后完成出票；重复或过期回调不会重复推进订单。
6. 取消、支付超时和退款分别释放数据库账本与 Redis 座位状态。

## 关键实现

### 1. 座位并发控制

- Redis Bitmap 保存场次座位占用状态，位索引对应座位下标。
- Redis Hash 保存座位归属，区分锁定订单和已售订单。
- `seat_lock.lua` 在一次 Redis 脚本执行中完成检查和写入，避免两个请求同时看到空座位。
- `seat_release.lua` 根据订单归属释放座位，重复执行不会释放其他订单的座位。
- 座位状态缺失时，`seat-service` 从数据库座位账本增量播种 Bitmap，避免并发初始化覆盖新锁定。

### 2. 订单与库存一致性

- 订单创建使用 Seata AT 全局事务，参与方包括订单、场次库存、座位账本和优惠券。
- 库存更新通过条件更新和影响行数校验防止超卖；分支未更新到预期行数时主动抛出异常触发回滚。
- Redis 不属于 Seata 事务，锁座失败时由订单服务显式补偿释放，并由 TTL 和超时清扫兜底。
- 订单号复用锁座令牌，重复提交可以被识别并拒绝。

### 3. 支付回调处理

- 订单状态通过状态机迁移，只有允许的前置状态才能进入已支付、已取消或已退款状态。
- 支付通知记录通知日志，使用业务订单号和渠道流水号进行幂等判断。
- 重复回调不会重复扣库存、出票或推进订单状态。
- 支付成功后，数据库账本先完成锁定到已售迁移，再确认 Redis 座位标记。

### 4. 抢购流量控制

- 抢购场次先进入 `queue-service` 排队，不让所有请求直接访问锁座和订单接口。
- Redis ZSet 保存排队顺序，令牌携带场次和用户信息，并在座位服务中再次校验和消费。
- 网关负责快速拦截不符合抢购条件的请求，业务服务负责最终准入判断。
- 压测目前覆盖排队、锁座分配和售罄拒绝，未覆盖完整支付链路。

## 技术栈

| | |
|---|---|
| 语言 / 框架 | JDK 21、Spring Boot 3.2.12、Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.3.2 |
| 注册 / 配置 | Nacos 2.4.3 |
| 分布式事务 | Seata 2.2.0（AT 模式） |
| 存储 | MySQL 8.0（按服务拆 schema、库存与座位账本）、Redis 7（座位 Bitmap、抢购队列、售罄标记） |
| 消息预留 | RocketMQ 5.3.1 已配置，订单超时当前由定时扫描处理，未接入消息生产/消费 |
| 前端 | Vue 3 + Vite + Element Plus |

## 快速开始

以下命令从项目根目录运行。首次启动时，MySQL 会自动执行挂载的 `docs/sql/schema/`；已有数据卷不会重复执行建表脚本。

```bash
# 1. 启动中间件
docker compose -f docker/docker-compose.yml up -d
docker compose -f docker/docker-compose.yml ps

# 2. 导入演示数据（Bash / Git Bash）
for f in docs/sql/demo/*.sql; do
  docker exec -i maipiao-mysql mysql -uroot -pmaipiao123 < "$f"
done

# 3. 编译后端
mvn clean install -DskipTests

# 4. 分别启动用户端和管理端
(cd maipiao-web && npm ci && npm run dev)
(cd maipiao-admin-web && npm ci && npm run dev)
```

PowerShell 导入 SQL 请按照 [数据库脚本说明](docs/sql/README.md) 执行。启动后端服务时，需要运行 `gateway`、`user`、`movie`、`seat`、`order`、`pay`、`queue` 和 `mock-pay`。

演示数据接口默认关闭。仅本地演示时，在启动 `movie` 服务前设置：

```powershell
$env:MAIPIAO_DEMO_ENABLED="true"
```

| 演示账号 | 手机号 | 密码 |
|---|---|---|
| 管理员 | 13800000000 | 123456 |
| 普通用户 | 13800000001 | 123456 |

启用开关后，可通过 `movie-service` 直连接口生成演示数据：

```bash
# 第一个接口会清理并重建生成器数据
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-schedule?days=7&soldRatio=0.25&rush=true"
# 第二个接口只创建 Showcase 数据
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-showcase"
```

两个接口应按顺序执行。

## 文档

- [设计方案](docs/设计方案.md)：系统架构、核心链路和技术选型
- [设计取舍](docs/设计取舍.md)：关键方案及其适用边界
- [压测报告](docs/压测报告.md)：测试场景、数据和瓶颈分析
- [压测工具](docs/loadtest/)：排队和锁座压测脚本
- [数据库脚本说明](docs/sql/README.md)：结构脚本与演示数据导入方式
- [面试准备](docs/面试准备.md)：项目介绍、技术问题和回答要点
- [历史规划](docs/历史规划.md)：早期方案和未完全落地的规划内容

## 当前边界

- RocketMQ 已完成依赖和配置，订单超时当前由定时任务扫描处理，尚未接入业务消息生产和消费。
- 压测覆盖排队、锁座分配和售罄拒绝，尚未覆盖完整支付回调链路。
- 项目用于学习微服务、分布式事务和高并发售票设计，不代表生产级容量或完整支付渠道接入。
