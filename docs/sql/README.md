# 数据库脚本

分成两类，理由不同，跑法也不同。

```
schema/   建出当前的库表结构。全新 MySQL 数据卷由 Docker 自动执行一次。
demo/     演示数据。可选 —— 真实部署不需要，本地演示离不开。
```

## schema/ —— 结构

| 文件 | 内容 |
|---|---|
| `01_user.sql` | 用户、优惠券、优惠券模板 |
| `02_order.sql` | 订单、订单明细、状态日志 |
| `03_pay.sql` | 支付单、退款单、回调日志、对账差异 |
| `04_event.sql` | 影片 / 场馆 / 场地 / 场次 / 座位账本 / 票档 / 观演人 |
| `05_seata_undo_log.sql` | 四个参与全局事务的库，各一张 `undo_log` |

从项目根目录执行 `docker compose -f docker/docker-compose.yml up -d` 时，Compose 将
`schema/` 挂到 `/docker-entrypoint-initdb.d`，MySQL **只在数据卷为空时自动执行**。
这些脚本含 `DROP TABLE IF EXISTS`；**已有数据卷不要手动重跑，否则会删表中数据**。

**这些文件描述的是结构的当前状态，不是变更历史。** 早先加的列、改的类型都直接写在建表语句里 ——
一个从零开始的克隆跑一遍 `schema/` 就应该得到与开发机一致的结构，而不是「跑完基础建表、
再记得补三个 ALTER」。

> `undo_log` 那一张尤其容易漏。Seata AT 模式下，**每一个参与全局事务的库**都需要它，
> 少一个是启动期报 `in AT mode, undo_log table not exist`，而报出来的样子像数据源装配问题。
> 目前是四个库：order、user、event、pay。

## demo/ —— 演示数据

| 文件 | 内容 | 依赖 |
|---|---|---|
| `01_films.sql` | 24 部影片、8 家影院、40 个影厅、优惠券模板 | schema/ |
| `02_performances.sql` | 演出类场馆、场地、项目 | schema/ |
| `03_showcase.sql` | 周杰伦演唱会那座 2000 座体育场 | schema/ |
| `04_admin.sql` | 一个管理员账号（13800000000 / 123456） | schema/01_user.sql |

以下命令从项目根目录执行：

```bash
for f in docs/sql/demo/*.sql; do
  docker exec -i maipiao-mysql mysql -uroot -pmaipiao123 < "$f"
done
```

Windows PowerShell 7 可按文件名顺序导入（先确认 MySQL 健康检查已通过）：

```powershell
Get-ChildItem docs/sql/demo/*.sql | Sort-Object Name | ForEach-Object {
    Get-Content $_.FullName -Raw -Encoding utf8 |
        docker exec -i maipiao-mysql mysql -uroot -pmaipiao123
}
```

**场次和座位不在这里。** 大约 1300 个场次、约 19 万行座位由程序生成 ——
手写 19 万行算术就是 19 万次把座位偏移量写错的机会，而那些偏移量就是 Redis bitmap 的位置。
生成方式见 [当前实现与架构](../设计方案.md)；旧模型的生成公式留在
[历史规划](../历史规划.md)。

## 顺序

```
schema/  →  demo/  →  生成排期
```

最后一步是两次 HTTP 调用，顺序不能反（第一个会清空所有生成的场次）。
接口默认关闭，必须先在启动 movie 服务的终端设置 `MAIPIAO_DEMO_ENABLED=true`；
下面的 `9002` 是本地直连端口，绕过网关权限校验，不应对外开放。

```bash
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-schedule?days=7&soldRatio=0.25&rush=true"
curl -X POST "http://127.0.0.1:9002/movie/demo/generate-showcase"
```
