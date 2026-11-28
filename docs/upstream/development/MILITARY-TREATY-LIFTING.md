# 军事和约权限化解除 T20

日期：2026-11-29，Asia/Shanghai。

管理员现在可以使用 `/war treatyrecovery military <完整和约UUID>` 查询原军事效果及解除回执，使用 `/war treatyrecovery liftmilitary <完整和约UUID>` 解除已完整执行并释放的军事和约限制。入口检查 `starcore.war.admin`、完整UUID、当前服务实例，并在执行前重新确认权限。补全从已保存的军事执行中返回最多10个匹配项，在服务解析和清单读取后复验权限与实例；军限动作仅出现在和约恢复补全中。普通玩家不能通过该入口自行解除。

来源服务要求原和约已接受、执行已完成、批次已 `RELEASED`，原军事计划和终态与Army权威一致，War保存的完整 `APPLIED` 释放向量与来源原向量一致。新增只读 `settlementRelease` 查询不会创建或恢复操作。Army接收器读取并复读原War结束、无取消、完整释放与外交终态，同时检查当前来源实例；缺失、变化或不完整的证明均拒绝。解除使用从原操作与manifest派生的稳定编号，重复调用沿原回执与原时间恢复。

解除只在Army权威保存既有Lift字段，不改变原和约效果证明、裁军墓碑或其他军限。解除时间不早于该国家之后的军队变化、退役、付款、技能、战斗和其他军限历史。查询旧军事记录返回独立副本，调用方直接修改返回对象不能修改内部记录。旧记录缺少完整v2来源证明，不能经此入口伪造迁移或解除，仍需独立迁移协议。

最终六批 **1969项 / 133个suite** 全部通过：lifting 24，contracts 54，army 1401，protocol 441，composition 18，persistence 31；0失败、错误、跳过。7个生产源空目录编译为26个class，3个测试源编译为5个class；10个Java格式通过，API基线未更新。96 MiB heap、SerialGC、单处理器，编译与测试严格串行。见[机器证据](../../verification/workspace-military-treaty-lifting-development-20261129.json)；最终日志和XML逐批核对，不重复累计。

专项使用真实文件/SQLite War、外交、Army权威，覆盖重启后原解除回执、裁军不复活、未完整ACK或未释放时拒绝、取消/未知和约拒绝、提交失败和丢回复恢复、来源证明消失或实例替换、其他军限继续限制扩军、后续军队历史时间，以及命令权限/UUID/服务生命周期。回归包含Army目录中现有可执行测试及和约协议；旧 `DoctrineServiceBudgetSpendingTest` 仍是被禁用的空类，不构成学说预算覆盖。组合组只重跑各组合的完整接受方法，不宣称重跑全部既有组合故障矩阵。Bukkit与调度器为替身，未进行实际服务器崩溃、SQL多进程或MySQL验收。

累计补丁 `military-treaty-lifting-20261129.patch`：**5,199,199字节 / 444个文件diff**，SHA256：`3a18dac340907a8a8c9b9f4367bda743c49e668278c1b9741608e6851f9bdb8d`。固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`；替代旧累计补丁，不能叠加。两个独立索引正反应用后，3,909个src及API/pom规范字节一致。

**全部阶段尚未完成。** 旧军限迁移、攻城来源/接收器、租赁/赔款/贡纳/贷款资金、海军运输/港口、伤兵回补/学说、预算取消/结转、其他模块权限/线程/生命周期及归档继续开发。Foundation安装见证仍无write-ahead pending candidate，不能检测权威与全部见证同时恢复。全仓clean build、新JAR/review ZIP、最终Core＋Supply、SQL多进程/MySQL与用户推迟的实服/客户端验收仍开放。未启动服务器或修改世界、玩家存档。
