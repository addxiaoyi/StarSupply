# Foundation Chunk 条约来源证明 T16

日期：2026-11-28，Asia/Shanghai。

Foundation 原生区块预留现在可以保存完整原始条约 envelope、不可变共同 binding 与本地 BOUND/终态证明。新增独立 `ChunkCessionTerm`，明确使用世界名称与原始 x/z 坐标、最低剩余领地数量；旧 `TerritorialCessionTerm` 继续使用 Cuboid UUID。世界名称按 UTF-8 编码为无 padding 的 Base64URL，坐标使用规范整数，拒绝重复、非法 UTF-8 和别名编码。单批最多256个原坐标。

包内接收器支持原号 reserve、bind、abandonUnbound 与 resume。提交所有权前核实并复读真实 War 的结束/取消决定与外交终态，检查完整 scope、原条款索引、计划 hash、身份和时间；来源不可用、替换、关闭、重入或读后发生变化均拒绝提交。来源回调期间领土快照变化也会阻止提交。绑定后的操作必须服从来源决定，原号提前取消保存完整原请求；原生接口不能绕过条约归属直接完成操作。

领土存储在含条约时使用 schema5，继续读取 schema1–4。绑定是独立持久后继，不改所有权；提交终态必须精确匹配原批次效果，拒绝跳过绑定、历史改写和元数据降级。证明容量作为原预留空间的替换槽，保存前检查未来全部终态证明编码不超过1 MiB，并计算阶段对应的未来 revision；覆盖9→10等位数增长以及文件、SQL计数耗尽边界。

War 新增独立 foundation proof reader，准入、结束和 release 都要求读取完整指定证明并检查服务身份与生命周期。**目前只接受 diplomacy＋foundation，可带政治条款；与军事或 Cuboid 的混合组合仍拒绝。接收器保持包内可见，PeaceTreatyService、批次持久协议、玩家命令和生产模块注册尚未接通。**

最终七批 **1994项 / 146个suite** 全部通过：foundation 301，protocol 464，territory 692，nation 357，persistence 31，mixed 80，production 69；0失败、错误、跳过。新增44项来源/后继测试、9项容量/生命周期测试和27项真实三权威测试，已包含在最终 Foundation 批次，早期75项与27项专项结果不重复累计。8个生产源空目录编译为20个class，19个测试源编译为52个class；11个Java格式通过，API基线未更新。见[机器证据](../../verification/workspace-foundation-chunk-treaty-development-20261128.json)。本任务编译与测试严格串行，96 MiB heap、SerialGC、单处理器。

真实文件/SQLite联合测试使用实际 War、Diplomacy、Foundation 服务，覆盖10处提交后丢回复与重建、否决、取消、替代、纯割让仍须外交以及证明源缺失/替换/关闭。来源专项另覆盖20组提交前后丢回复。Bukkit与调度器为测试替身，未启动或终止实际游戏服务器。跨权威协议能够按原号恢复，但不是跨数据库瞬时事务；运行中拒绝身份替换不代表能发现跨重启的整库外部回滚，持久安装绑定仍待实现。

累计补丁 `foundation-chunk-treaty-20261128.patch`：**4,982,910字节 / 429个文件diff**，SHA256：`f1fb40a66fddfb0e1c25accb8f545aaca5c8ad2cdd32d7b0a3ae3e1429b6a8f3`。固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`；替代全部旧累计补丁，不能叠加。两个独立索引正反实际应用后，3,898个src及API/pom规范字节一致。

**全部阶段尚未完成。** 下一步接通 Foundation 生产和约与混合条款，再继续攻城 receiver、旧军限迁移/权限化解除、租赁/赔款/贡纳/贷款资金、海军运输/港口、伤兵回补/学说、预算取消/结转、其他模块权限/线程/生命周期及归档。全仓 clean build、新JAR/review ZIP、最终Core＋Supply回归、SQL多进程/MySQL和用户推迟的实服/客户端验收仍开放。未修改世界或玩家存档。
