# 压测工具

这些脚本用于独立本地测试环境，会修改场次数据和 Redis 售罄标记；执行前检查脚本内数据库地址、凭据、用户 ID 和网关地址。不要对承接真实订单的数据库运行。历史数据与统计限制统一见 [压测报告](../压测报告.md)。

需要 Bash、JDK 21、Docker，以及正在运行的本地服务；买家账号需预先准备。工具使用代码内的本地开发 JWT 密钥签发 token，跳过注册与登录。

在本目录运行：

```bash
./rush-ladder.sh 200 2000 5000 10000 20000
./flood.sh 800000 2000
```

## JMeter 秒杀场景

`maipiao-seckill.jmx` 使用 JMeter 5.6.3 的非 GUI 模式，模拟每个线程对应一个独立用户：签发本地测试 JWT、加入队列、轮询放行、持令牌请求一张票。正常售罄算预期结果；HTTP 错误、鉴权失败、排队超时和未分类业务拒绝会记为失败。

该计划会真实锁定演示场次座位，**只对隔离的本地开发数据运行**。先启动完整后端，准备一场正在开售且开启抢购的空演示场次，查出该场次及可售票档 ID。JMeter 使用项目本地默认 JWT 密钥；如服务改过密钥，显式传入同一密钥。

在 PowerShell 中运行：

```powershell
$jmeter = 'D:\JMter\apache-jmeter-5.6.3\bin\jmeter.bat'
$results = 'D:\JMter\results\maipiao-seckill.jtl'
New-Item -ItemType Directory -Force (Split-Path $results) | Out-Null
& $jmeter -n -t 'docs\loadtest\maipiao-seckill.jmx' `
  -JbaseUrl=http://127.0.0.1:9000 -JscheduleId=场次ID -JtierId=票档ID `
  -Jthreads=200 -JrampSeconds=10 -JtimeoutMs=30000 `
  -JuserIdBase=3000000000000000000 `
  -Jresults=$results
```

场次必须是 `seat_mode=1` 的系统分配场次，且未售罄。每轮使用不同的 `userIdBase`，避免重复买家身份。先以 1–5 个线程跑通，再按 20、100、200、500 递增；每轮使用新的空演示场次，或在隔离开发库确认数据库账本为空后清理该场次的 Redis 占位和队列状态。逐轮记录线程数、场次余量、JMeter Summary Report 的吞吐/P95/P99/错误率，并在 MySQL 核对座位账本、场次计数和订单数。该 JMX 的每个样本覆盖完整排队等待，因此其延迟包含等待时间，不能当作单接口延迟。JMeter 结果也不等于真实生产容量结论。

- `rush-ladder.sh` 调用 `run-rush.sh` 准备测试场次，然后入队、轮询、获取令牌、调用 `/api/seat/assign`。只验证分配锁座，没有订单、支付和出票。
- `flood.sh` 设置售罄标记，测量分配请求的拒绝吞吐；输出不能用作成交 TPS。

## 完整购票链路

`maipiao-full-chain.jmx` 每个线程创建独立买家并登录，按唯一座位串行执行：

```text
注册/登录 → 座位图 → 锁座 → 创建订单 → MOCK 支付预下单 → 确认支付
→ 轮询异步支付回调 → 轮询订单并确认出票
```

主样本只有在订单状态为已支付、票数正确且票号非空时才算成功；接口调用作为子样本写入 JTL。遇到 `RETRYABLE` 下单结果时会按 `orderRetries` 有限重试。异步回调由 MOCK 服务为每次确认启动线程，压力结果只代表当前本地学习环境。

该脚本会真实创建用户、订单、支付单并消耗场次座位，只能对隔离本地场次运行。每轮指定未使用的连续座位段和用户索引段；座位索引必须在座位图中存在且初始可用。建议从低并发开始，完整链路超过 50 线程时检查订单服务、Seata、MySQL、支付回调线程和 JMeter 客户端资源。

PowerShell 示例：

```powershell
$jmeter = 'D:\JMter\apache-jmeter-5.6.3\bin\jmeter.bat'
$result = 'D:\JMter\results\full-chain-smoke.jtl'
& $jmeter -n -t 'docs\loadtest\maipiao-full-chain.jmx' `
  -JbaseUrl=http://127.0.0.1:9000 -JmockPayUrl=http://127.0.0.1:9007 `
  -JscheduleId=隔离场次ID -JseatIndexBase=0 -Jthreads=2 -JrampSeconds=1 `
  -JtimeoutMs=45000 -JpollMs=250 -JmaxPolls=180 -JorderRetries=3 `
  -JuserIndexBase=97000000 -Jresults=$result
```

JTL 主样本标签 `full-chain-success` 表示完整成交；`full-chain-failure-阶段` 用于定位失败阶段。子样本耗时反映单次 HTTP 调用，主样本耗时反映整笔购买（包含重试和支付回调等待）。阶梯测试每轮应更换用户段和未占用座位段，禁止并行运行在同一场次。测试后检查 `t_order_order`、`t_order_item`、`t_pay_payment`、`t_event_session`、`t_event_session_seat` 和预约状态，并核实未完成锁的超时回收。

`allocated` 是分配成功次数，`assign latency` 是接口平均耗时，`DUPLICATE SEATS` 是本轮成功响应中重复的座位下标数量。它们都不能单独证明完整购票链路不超卖。脚本总任务数不等于同时在线人数。

本轮已用 JMeter 覆盖排队、准入和 Redis 锁座，并新增完整交易链路脚本。退款、超时取消以及进程重启/重复回调竞争仍需单独做故障注入验收。
