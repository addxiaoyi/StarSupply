# Folia 状态与剩余工作

当前可运行、已验收的组合为 Java 21、Paper 1.21.11、固定 StarCore API，以及按需安装的森罗组件。StarSupply 不声明 `folia-supported`，不能将 Paper 验收表述为完整 Folia 验收。原开发目标中的 Folia 组合兼容仍未完成。

已实现的自身线程边界：

- 玩家背包、PDC、GUI、位置与物品身份检查在玩家实体调度线程进行，并检查 `Bukkit.isOwnedByCurrentRegion`。
- SQL 与路线规划在受限队列的独立串行工作线程进行；异步回调操作玩家前重新进入实体调度器。
- 周期季节与围城状态从全局任务采样，使用 `Bukkit.isGlobalTickThread()` 检查上下文，再传递不可变数据。
- 可取消玩家操作事件在玩家线程发布，经济和周期消费通知明确标为异步事件。
- 玩家 `saveData()` 与库存收据保存在同一实体线程内完成；不能简单搬到 SQL 线程异步保存玩家对象。

本地 Paper 1.21.11-132 的 `CraftServer` 字节码表明，`isPrimaryThread()` 与 `isGlobalTickThread()` 在该 Paper 版本均委托同一 tick 线程判断。Folia 的全局任务上下文与区域 tick 不同，因此使用更精确的全局 API；最终 Paper JAR 已验证启动采样与周期事件成功。这只证明 Paper 下的线程路径，不能推出 Folia 服务安全。

剩余兼容工作必须尊重 StarCore 权威：

1. 固定 StarCore 没有 Folia 支持声明，军队、季节、围城仍使用传统 `BukkitScheduler`。应先在上游完成调度器迁移，不能只修改声明强行加载。
2. 季节服务的世界状态容器和军队/围城可变对象需要明确所有权线程、线程安全公开快照或事件桥。StarSupply 不应在任意区域线程读写这些对象，也不能复制一套国家或战争模型绕开权威服务。
3. 真实 Folia 中不能假定 `onEnable()` 已处于全局 tick。首次战役采样需进入首个全局任务，并在首个有效快照到达前保守处理需求，保持围城连续性语义。
4. 在相互独立的玩家区域、断线调度退役、世界卸载和重启时，验证真实库存保存/读回、收据恢复、GUI、异步事件及经济调用的线程契约。
5. 对实际启用的 CraftEngine、Cookery、Tavern 和经济提供方分别验证对应版本兼容性；任一依赖不能运行时不能宣告整服通过。

围城财政与部署补丁只修复对应的 Paper 玩法缺陷，未解决上面的 Folia 工作，见 [上游补丁说明](upstream/README.md)。
