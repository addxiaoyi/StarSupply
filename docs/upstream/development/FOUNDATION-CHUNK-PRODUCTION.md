# Foundation Chunk 生产和约 T17

日期：2026-11-28，Asia/Shanghai。

Foundation 区块割让已接通实际 PeaceTreatyService、持久批次、模块注册与玩家命令，支持纯区块割让和区块＋政治条款。独立不可变 `PeaceTreaty.ChunkCessionTerm` 保存原始世界名称、x/z、最低剩余领地数及描述，最多256个不同坐标；旧 Cuboid 条款仍使用 UUID。军事或 Cuboid 与 Foundation 的组合继续拒绝，等待下一阶段共同协调器。

新的公开 `ChunkTreatyOperationService` 仅公开原计划引用和终态证明，工厂复用 `DatabaseAwareTerritoryService` 已固定的真实 owner，调用前后检查身份、关闭和后端状态。内部原生预留接口仍不可作为无来源完成入口。host 注册/关闭 facade，WarModule 将同一个 facade 接入 War 证明读取与和约协调器，实际运行时不另建一套领土权威。

来源批次保存原外交计划、Foundation 权威/准备revision/计划hash及共同 binding。共同决定先持久保存，再调用任一接收器；取消发生在部分预留或部分绑定之后，也按原编号补齐同一决定。外交与领土全部终态一致后，来源才确认接受或保存取消结果并释放 War scope。缺失或不匹配的原计划、终态或 proof 会阻止完成，重启继续原操作。已完成条约重放不恢复后来转出的领地。

和约 schema8 保存 Foundation 计划及完整终态，继续读取1–7；旧版本中夹带新的区块条款、计划或回执时拒绝降级，包括尚未创建执行批次的提案。原生坐标和 Cuboid UUID 不能混写到同一条款。来源容量保留新的计划/证明与执行步骤数据，绑定前继续检查接收器未来证明预算；真实文件/SQLite 验证256个坐标、长世界名与转义描述仍可完成，并保留败方承诺的最少领地。

玩家命令：`/war treaty propose <victor> <defeated> chunk:<world>:<x>:<z>:<keep>`。每个 chunk 参数指定一个原坐标，可重复参数并加入 `political:<requirement>`；沿用条约总条款限制，默认最多10条。x/z与keep要求规范整数，keep不得为负；世界名称可以包含冒号，解析从右侧分隔数值。保留原提案/接受权限和国家身份检查，帮助与补全已更新。API 单条可以携带多个坐标，整个批次上限256。

最终六批 **1036项 / 65个suite** 全部通过：foundation 301，protocol 478，persistence 31，mixed 80，cuboid 69，production 77；0失败、错误、跳过。11个生产源空目录编译为42个class，40个测试源编译为99个class，17个Java格式通过，API基线未更新。编译与测试严格串行，96 MiB heap、SerialGC、单处理器。见[机器证据](../../verification/workspace-foundation-chunk-production-development-20261128.json)。早期112项成功专项被最终组覆盖；首次专项含帮助文本断言失败且遭遇C盘耗尽、报告不完整，已排除并完整重跑，不计入通过总数。

生产测试使用真实 War、Diplomacy 和固定 Foundation 文件/SQLite owner，覆盖16处提交后丢回复重启、9处取消边界、提前封存、外交否决、缺失终态、其他协调器并发取消、来源关闭、后端变化、权威引用替换与终态后的再转移。Bukkit/调度器为测试替身，未启动或终止实际游戏服务器。联合恢复协议不是跨数据库瞬时事务；持久安装绑定及跨重启整库外部回滚检测仍开放。

累计补丁 `foundation-chunk-production-20261128.patch`：**5,062,872字节 / 434个文件diff**，SHA256：`58c23846fd857c4f9f23886a2d7fdc02ee774d57099284299125286f5cec44a0`。固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`；替代全部旧累计补丁，不能叠加。两个独立索引正反实际应用后，3,901个src及API/pom规范字节一致。

**全部阶段尚未完成。** 下一步军事/Cuboid/Foundation混合和约，再继续攻城 receiver、旧军限迁移/权限化解除、租赁/赔款/贡纳/贷款资金、海军运输/港口、伤兵回补/学说、预算取消/结转、其他模块权限/线程/生命周期及归档。全仓 clean build、新JAR/review ZIP、最终Core＋Supply回归、SQL多进程/MySQL与用户推迟的实服/客户端验收仍开放。未修改世界或玩家存档。
