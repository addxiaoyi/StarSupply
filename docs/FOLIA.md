# Folia 状态与剩余工作

当前可运行、已验收的组合为 Java 21、Paper 1.21.11、固定 StarCore API，以及按需安装的森罗组件。StarSupply 不声明 `folia-supported`，不能将 Paper 验收表述为完整 Folia 验收。原开发目标中的 Folia 组合兼容仍未完成。

实验组合已在真实 Folia 1.21.11-14 上完成首次启动和正常停服：StarCore 后勤 profile、StarSupply、CraftEngine 26.9.1 与 Cookery 1.3.0 均启用，启动与停服无 ERROR 或调度归属错误。测试 JAR 仅额外覆盖加载描述符，源码与运行哈希分别记录在 [首次启动证据](verification/folia-initial-boot-20261120.json)。这尚不证明双区域玩家库存、运输交付或完整重启闭环。

上游迁移的 [可移植源码补丁](upstream/starcore-folia-logistics.patch) 已能直接应用到固定原始提交；[构建记录](verification/folia-upstream-targeted-tests-20261120.json) 包含 967 项实际执行通过、4 项明确跳过的定向测试，以及后续可选命令修订的 34 项测试。显式 profile 保留全部 StarSupply 权威服务依赖，默认关闭。完整运行验收仍是发布 Folia 支持声明的前提。

已实现的自身线程边界：

- 玩家背包、PDC、GUI、位置与物品身份检查在玩家实体调度线程进行，并检查 `Bukkit.isOwnedByCurrentRegion`。
- SQL 与路线规划在受限队列的独立串行工作线程进行；异步回调操作玩家前重新进入实体调度器。
- 周期季节与围城状态从全局任务采样，使用 `Bukkit.isGlobalTickThread()` 检查上下文，再传递不可变数据。
- 可取消玩家操作事件在玩家线程发布，经济和周期消费通知明确标为异步事件。
- 玩家 `saveData()` 与库存收据保存在同一实体线程内完成；不能简单搬到 SQL 线程异步保存玩家对象。

本地 Paper 1.21.11-132 的 `CraftServer` 字节码表明，`isPrimaryThread()` 与 `isGlobalTickThread()` 在该 Paper 版本均委托同一 tick 线程判断。Folia 的全局任务上下文与区域 tick 不同，因此使用更精确的全局 API；Paper JAR 已验证启动采样与周期事件成功；当前 D2BFC503 包还通过首个全局采样、公开停用/重新启用及同 tick 连续三轮启停回归，详见 [验收记录](PAPER-ACCEPTANCE.md)。这只证明 Paper 下的线程路径，不能推出 Folia 服务安全。

剩余兼容工作必须尊重 StarCore 权威：

1. 固定原始 StarCore 没有 Folia 支持声明，仍使用传统调度器。实验补丁迁移了后勤 profile 的活跃调度并已通过真实启动，仍需完成实际玩法验收；不能把只修改描述符当作兼容方案。
2. 实验补丁为军队、围城、SupplyLine 和战争模型补齐了串行访问、独立快照与确认写入锁顺序；玩家通知、GUI 和固定世界位置分别进入实体或区域调度器。仍需用实际多区域玩家检验，而非复制国家或战争模型绕开 StarCore。
3. 真实 Folia 中不能假定 `onEnable()` 已处于全局 tick。当前已把首次战役采样移入首个全局任务，并在首个有效快照到达前暂停军需维护；代次标识阻止上次启用的排队回调污染新状态。该改动已通过 Paper 公共 API 生命周期回归，仍需真实 Folia 运行验证。
4. 在相互独立的玩家区域、断线调度退役、世界卸载和重启时，验证真实库存保存/读回、收据恢复、GUI、异步事件及经济调用的线程契约。
5. 对实际启用的 CraftEngine、Cookery、Tavern 和经济提供方分别验证对应版本兼容性；任一依赖不能运行时不能宣告整服通过。

围城财政与部署补丁只修复对应的 Paper 玩法缺陷，未解决上面的 Folia 工作，见 [上游补丁说明](upstream/README.md)。
