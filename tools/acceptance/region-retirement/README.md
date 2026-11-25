# Folia 实体退役与区块卸载恢复验收

该夹具只可放在 `127.0.0.1`、离线模式的独立测试服，且必须有 `folia-proof/allow-region-retirement-fixture` 标记。`prepare-stage.ps1` 复制已停止的测试世界和数据库到新目录，保留原测试服及生存玩家进度。不得安装到生产服。

使用现有 SupplyBuyer、Alpha 国家和 `alpha-depot`，背包至少有一个铁锭。真实玩家库存和经济参与验收，但测试传送、冻结与断线由管理员夹具控制，因此不属于生存物资获取证据。

准备阶段会备份复制来的玩家 NBT，仅从配方书移除非 `minecraft:` 配方，避免未加载 CraftEngine/Cookery 时旧配方产生启动后错误。脚本回读并确认完整 Inventory 和 BukkitValues（含库存回执）不变；源测试服不受影响。

两条路径均用公开 `SupplyApi.createPurchase` 创建一件 EQUIPMENT 的 5.00 采购，通过生产 `InventoryEscrow.plan` 和 `SupplyStore.prepareTransfer` 建立真实持久交接。夹具反射访问 StarSupply 的 SQL 提交、实体回调、库存操作守卫和只读状态；反射仅用于固定故障边界，不替换实际 Player、Folia 调度器、SQLite、经济或回执实现。

1. `untouched`：SQL 为 PREPARED，背包未扣物、没有回执。夹具踢出玩家，在退出事件返回前把真实库存应用回调排进尚未退役的玩家实体调度器。
2. `receipt`：先用生产库存实现扣除一个铁锭，保存并从玩家 NBT 核验回执，SQL 仍为 PREPARED。随后踢出玩家，在退出事件返回前把清理回执回调入队。

为固定排队时点，夹具在玩家区域线程使用最多 500 ms 的测试屏障，等待专用后台线程完成提交。生产代码没有增加阻塞、故障钩子或对夹具的依赖。生产 future 的异常栈必须来自实际 `retire` 调用，回调主体执行次数须为零；另一个真实 Folia 延迟任务独立观察退役。随后检查库存守卫释放、回调登记表排空，以及对已退役 Player 再次提交也会结束。

客户端必须观察远处目标区块的 `ChunkUnloadEvent`，再由全局观察任务确认 `isChunkLoaded == false`。额外通过只读反射调用该 Folia 版本的 `regioniser.getRegionAtSynchronised`，确认目标区块的区域对象已消失后才重连。重新加载须有 `ChunkLoadEvent` 和不同的新区域 ID，不能以等待固定秒数代替卸载证据。内部区域观测绑定验收记录的 Folia 版本，生产代码不依赖 NMS。生产重连恢复逻辑应分别得到：

| 路径 | 库存 | 采购 / 交接 / 结算 | 玩家余额 |
| --- | --- | --- | --- |
| untouched | 指纹完全不变、没有回执 | OPEN / 交接删除 / 无结算 | 不变 |
| receipt | 正好少一个铁锭、回执清除 | BOUGHT / STORED / PAID，结算事件一次 | 正好增加 5.00 |

每条路径随后再执行 `/supply recover` 并核对相同结果。最终恢复玩家原位置和运动属性、保存玩家，启动器通过 RCON 正常停服并检查日志与端口。证据在隔离目录的 `plugins/SupplyRetirementProof/evidence.jsonl` 与 `folia-proof/*result.json`。

构建和准备（所有路径均指独立测试副本）：

```powershell
& .\tools\acceptance\region-retirement\build.ps1 -JavaHome $javaDirectory `
  -StarCoreJar $coreJar -StarSupplyJar .\target\starsupply-0.1.0.jar
& .\tools\acceptance\region-retirement\prepare-stage.ps1 `
  -SourceStage $stoppedStage -Stage $newStage -StarCoreJar $coreJar -MineflayerModule $mineflayerModule
$env:STARSUPPLY_ACCEPTANCE_HEAP = '384M'
node .\tools\acceptance\survival-progression\run.cjs $newStage $mineflayerModule `
  $pathfinderModule (Join-Path $javaDirectory 'bin\java.exe') SupplyBuyer `
  .\tools\acceptance\region-retirement\client.cjs 420000
```

两个业务 JAR 只在隔离副本的描述符中增加 `folia-supported: true`；生产支持声明不变。该验收不覆盖完整可选插件组合、客户端资源包渲染或世界整体卸载 API。
