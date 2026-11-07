# 数据库脚本

## 新库初始化

`schema/` 描述当前结构，包含会 `DROP TABLE` 的文件，仅用于新库或明确可重建的独立开发库；不要用整个目录升级已有库。

| 文件 | 内容 |
| --- | --- |
| `01_user.sql` | 用户、优惠券、优惠券模板 |
| `02_order.sql` | 订单、明细、状态日志 |
| `03_pay.sql` | 支付、退款、通知审计、对账差异，含支付唯一身份及座位补偿字段 |
| `04_event.sql` | 影片、场馆、场地、场次、座位账本、票档、观演人 |
| `05_seata_undo_log.sql` | order、user、event、pay 四库的 Seata AT `undo_log` |
| `07_order_reservation.sql` | 持久化预约协调记录 |
| `08_order_refund_request.sql` | 订单退款请求 outbox |
| `10_order_domain_snapshots.sql` | 将旧订单表的影片/影院快照列重命名为项目/场馆快照列 |

`01_user.sql`、`02_order.sql`、`03_pay.sql`、`04_event.sql` 还包含各服务本地的
`t_admin_audit_log` 管理员操作审计表。movie 和 seat 服务共用 `maipiao_event`；queue
服务没有业务数据库，因此不创建该表。queue 的暂停/恢复是 Redis 瞬态运维开关；如需
持久追溯，应接入统一日志或操作平台，本次不为了审计给 queue-service 增加业务数据库。

新库按文件名顺序执行 `schema/*.sql`。`docker/docker-compose.yml` 已挂载该目录，MySQL 只在空数据卷首次初始化时自动执行。已有数据卷不会因为脚本变化而自动升级。

新库使用当前 schema 后，不需要再执行 `migrations/08_trade_reliability.sql`。

## 已有库升级

本次未对用户数据库执行任何 SQL。更新应用前，先停止交易写入并备份，确认旧库实际列和索引与脚本前提相符。

1. 如缺少预约表，仅执行 `schema/07_order_reservation.sql` 补表。此文件使用 `CREATE TABLE IF NOT EXISTS`，不删除旧表；已有表还需确认结构一致。
2. 在 `maipiao_pay` 检查同一订单多笔支付：

   ```sql
   SELECT order_no, COUNT(*) FROM t_pay_payment
   GROUP BY order_no HAVING COUNT(*) > 1;
   ```

   有结果时先逐笔核对渠道资金、订单与退款状态，确定保留/归档方案；不能自动删除重复支付记录后强加唯一索引。
3. 执行 `migrations/08_trade_reliability.sql`。它新增支付收银台地址、订单唯一索引、座位补偿字段和索引、退款 outbox，并为旧版 `status=5` 的退款中订单补齐待投递请求。
4. 执行 `migrations/09_admin_audit_log.sql`，在 `maipiao_user`、`maipiao_order`、`maipiao_event`、`maipiao_pay` 四个库中补齐审计表。该迁移只使用 `CREATE TABLE IF NOT EXISTS`，可重复执行，但仍应先核对实际表结构。
5. 检查新表、列、索引及待补偿数量，完成后再启动匹配版本的应用，观察支付查单、退款投递、座位补偿和管理员审计写入日志。

订单库仍使用旧列名时，先核实 `t_order_order` 中存在 `film_id`、`film_name`、`cinema_id`、`cinema_name`、`hall_name`，再执行 `migrations/10_order_domain_snapshots.sql`。迁移只重命名列并保留原值；已使用新列名的库不要重复执行。

`08_trade_reliability.sql` 的 ALTER **只能执行一次**，没有自动版本管理；如中途失败，先检查已经生效的 DDL，再执行剩余语句。MySQL DDL 不能当成整个文件可回滚的事务。已部分应用时禁止直接整份重跑。后续可引入 Flyway 统一迁移版本管理。

管理员审计日志按服务写入各自的本地数据库，当前不跨库 JOIN。需要统一检索时，应建设日志汇总或查询服务，并明确权限、保留期和告警策略；不能在业务请求中临时跨库查询。

## 演示数据与生成排期

`demo/` 仅用于独立开发库，依赖 schema：

| 文件 | 内容 |
| --- | --- |
| `01_films.sql` | 影片、影院、影厅、优惠券模板 |
| `02_performances.sql` | 演出场馆、场地、项目 |
| `03_showcase.sql` | 2000 座演唱会演示基础资料 |
| `04_admin.sql` | 本地演示管理员账号 |

先执行 demo 脚本，再生成排期。movie 服务必须同时启用 `dev` profile 和 `maipiao.demo.enabled=true`，否则控制器不注册。通过网关使用管理员 Bearer token，按下列顺序调用（将 ADMIN_TOKEN 替换为本地管理员登录取得的 token）：

```bash
curl -X POST 'http://127.0.0.1:9000/api/movie/admin/demo/generate-schedule?days=7&soldRatio=0.25&rush=true' -H 'Authorization: Bearer ADMIN_TOKEN'
curl -X POST 'http://127.0.0.1:9000/api/movie/admin/demo/generate-showcase' -H 'Authorization: Bearer ADMIN_TOKEN'
```

已有生成场次时，生成/清理接口会拒绝在线重置；已有 showcase 时也拒绝覆盖。生成中断可能留下部分数据，应在独立开发库核查或重建。服务直连端口属于内部网络，不能用网关鉴权代替网络隔离。
