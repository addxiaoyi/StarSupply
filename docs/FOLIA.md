# Folia 状态与剩余工作

当前可运行、已验收的组合为 Java 21、Paper 1.21.11、固定 StarCore API，以及按需安装的森罗组件。StarSupply 不声明 `folia-supported`，不能将 Paper 验收表述为完整 Folia 验收。原开发目标中的 Folia 组合兼容仍未完成。

## 2026-11-25 续接开发与待验状态

本次重新构建通过 124 项 Java 测试并复现当前生产 JAR 哈希；生存验收器已修复熔炉部分批次、燃料耗尽续烧和放置确认，47 项 Node 测试与 7 项 PowerShell 恢复检查通过。玩家存档只读核对与交接记录一致。本机可用内存不足，用户选择先完成可运行的开发测试，实服验收保留待办，因此这些新脚本尚未在 Folia 现场运行。六类生产、后续持续补给、当前完整组合和图形资源包仍未通过整体验收。详见 [本次记录](verification/workspace-survival-resume-20261125.json)。

随后补齐仅使用已有玩家物资的 `logistics` 阶段，连接普通采购/运输命令、三个连续完整消费周期、只读 SQL 核对和正常停服后的背包扣减。11 项新增 SQLite 夹具测试通过，Node 总数达到 58；旧仓库中的历史库存会被拒绝，防止把其他库存消费归入本次生存批次。该实现没有启动 Folia，生产来源、Core 回执重启重放和完整组合仍需分别验收。见 [物流阶段自动化证据](verification/workspace-survival-logistics-20261125.json)。

## 2026-11-25 六类生存流程续接

六阶段客户端已实现矿石/烧炼、地表木材与工具、种植面包、采蜜、脚手架和烟花合成入口。阶段通过仍必须在正常停服后核对生存模式玩家 NBT。新增服务器挖掘包确认、有限搜索和逐格拾取、中断后的原插件哈希恢复，以及运行脚本哈希记录；30 项 Node 回归与 7 项 PowerShell 恢复检查通过，恢复脚本也在 Windows PowerShell 5.1 验证。

本轮实际完成煤与粗铁采集：正常停服存档保留 6 份煤、6 份粗铁、146 个圆石和 1 把铁镐，玩家生命与饥饿值均为 20。采矿运行因从地下寻找沙子失败，未通过整个 mining 阶段；已改为先烧炼再回地表采沙。后续重试在客户端连接前因主机原生内存不足退出，全部临时插件恢复，7 个 SQLite 库完整性检查均为 ok。一次会话中断的运行没有正常停服证据，明确不计入通过记录。

六阶段整体、六类自产物资后续交付消费、当前完整插件组合与图形资源包渲染仍待验收。新增服务器挖掘包确认的最终安装时点已修正为玩家登录后，目前有协议测试，不能把早先采矿结果套用于该新代码。详细日志、存档和产物哈希见 [六类生存续接记录](verification/folia-survival-six-resource-progress-20261125.json)，入口与阶段条件见 [运行说明](../tools/acceptance/survival-progression/README.md)。

## 2026-11-25 实体退役、区域卸载与重建

当前构建 `87487098E790404108C9010EAE2186B1FFECAD1CF0A99119363A93D37BD18980` 通过完整 `mvnw.cmd -B -ntp -DforkCount=0 verify`：124 项测试，0 失败、0 错误、0 跳过；20 项验收器测试通过。修复 API 调用方取消或提前完成返回 future 后库存锁无法清理的问题，锁仍由实际库存流程持有，结束后再释放。见 [构建记录](verification/workspace-inventory-cancellation-20261125.json)。

独立 Folia 1.21.11-14 副本使用 384 MiB 堆、当前 StarSupply、兼容 StarCore 后勤 profile 和验收插件。两次分别在 SQL 已 PREPARED、玩家尚未扣物，以及玩家已扣物并保存回执但 SQL 尚未确认时退出。生产回调的异常栈均包含实际 `EntityScheduler.retire`，回调主体执行零次、库存锁释放、回调表排空，对已退役 Player 的后续提交也正常结束。目标区块触发卸载后 `isChunkLoaded` 为 false，Folia 区域查询为 null；重连观察到新的区域 ID。

未扣物路径的完整背包指纹保持不变，3 个铁锭保留，交接删除、采购回到 OPEN，无结算、余额不变。已有回执路径只扣 1 个铁锭，得到唯一 STORED/PAID、唯一 5.00 结算事件，余额从 5567.77 增至 5572.77。重复 `/supply recover` 后结果不变；正常停服 NBT 保留 2 个铁锭且无回执，6 个 SQLite 数据库完整性均为 ok。

夹具使用测试专用反射和有界屏障固定中断时点，所有库存、SQLite、经济及调度器均为真实实现；这是恢复边界验收，未通过整条正常售货入口制造时序。区域内部观测绑定本次 Folia 版本。源生存测试服未改动；两个业务 JAR 仅在测试副本添加加载描述符，生产 Folia 声明仍关闭。完整可选组合、六类持续生存生产、世界整体卸载 API 与图形资源包渲染不在本轮范围。见 [完整运行记录](verification/folia-region-retirement-20261125.json) 和 [复现说明](../tools/acceptance/region-retirement/README.md)。

## 2026-11-25 早先构建与铁镐生存续验

此前保险加固构建通过完整 `mvnw.cmd -B -ntp verify`：119 项测试，0 失败、0 错误、0 跳过。此前生命周期加固覆盖执行中回调和库存完整流程的重启屏障、SQL 提交代次/线程池固定、致命异常结果收尾及关闭清理；本轮再修复保险保费操作结果写回失败后在同一进程重复调用的缺口。新增 SQLite 故障注入和恢复测试使用外部经济替身，没有新增 Paper/Folia 现场运行；该构建时真实区域卸载和调度器退役恢复尚待验收；现已由本页上方记录补验。产物与日志哈希见 [当前构建记录](verification/workspace-insurance-interruption-20261125.json)，此前 [112 项生命周期构建](verification/workspace-lifecycle-hardening-20261125.json) 与 [105 项构建](verification/workspace-verify-20261125.json) 分别保留原记录。此前通过的 15 项验收器测试未在本轮重跑。

早先被归因于 `ExtensionPoint` 缺失的加载失败，实际先发生 StarCore 堆内存耗尽和停用；该类存在于其 JAR 中。随后 768 MiB 的六插件重试又耗尽主机分配空间，未启动客户端。验收器现在默认使用 1024 MiB 堆，在启动前为客户端和 JVM 原生分配预留 512 MiB，并同时检查 Windows 物理内存和提交余量；OOM、插件错误或启动里程碑缺失均阻止通过。本机后续完整组合预检仅有 976 MiB 可用，低于所需 1536 MiB，因此仍不能认证完整组合。

在仅保留 StarCore 后勤 profile 和 StarSupply、384 MiB 堆的隔离副本中，两插件均成功启用。脚本补齐 Minecraft 1.21.11 的 `player_loaded` 确认，挖掘后等待服务器方块包，避免把 Mineflayer 本地预测当作地形改变。05:01 UTC 的运行确认挖掉两块石头、在凹位放置背包中已有的熔炉，并用两块白桦木板烧炼两份已有粗铁。该轮客户端预测的铁镐合成未保存，原始 `pass=true` 不作为铁镐完成证据；停服存档保留三块铁锭和五根木棍，成为下一轮续验起点。

05:07 UTC 的续验逐格放入三块铁锭和两根木棍，收到服务器的铁镐配方产物、背包槽位更新及原版 `Isn't It Iron Pick` 成就。正常停服后，玩家 NBT 确认生存模式与 1 把铁镐；四个数据库完整性均为 `ok`，游戏与 RCON 端口关闭，临时移出的内容插件及原 StarSupply JAR 均按哈希恢复。实际测试 StarSupply JAR 与当时的 `6C942A8E…` 生产 JAR 逐项比较，仅 `plugin.yml` 不同；该比较不适用于本次生命周期加固后的新构建。运行 StarCore 与该次上游构建另有军队、Dungeon、SQLite 存储类差异，完整名单保存在证据中，不能把本轮套用于另一 Core 构建。

铁镐工具链现已通过；区域卸载/退役恢复已在本页上方独立验收；六类资源完整生存生产、生产 Folia 支持及图形客户端资源包渲染仍未完成。原始失败见 [重试记录](verification/folia-survival-progression-retry-20261125.json)，新的服务端与存档证据见 [铁镐续验记录](verification/folia-survival-iron-pickaxe-20261125.json)，复现方式见 [验收器说明](../tools/acceptance/survival-progression/README.md)。

实验组合已在真实 Folia 1.21.11-14 上完成首次启动和正常停服：StarCore 后勤 profile、StarSupply、CraftEngine 26.9.1 与 Cookery 1.3.0 均启用，首次启动与停服无 ERROR 或调度归属错误。测试 JAR 仅额外覆盖加载描述符，源码与运行哈希分别记录在 [首次启动证据](verification/folia-initial-boot-20261120.json)。后续真实玩法验收已完成双区域六类物资闭环，同时暴露一次上游 SQLite 并发写入失败；不能把首次启动结果扩展为整轮没有错误。

2026-11-20 的 [六资源 Folia 运行证据](verification/folia-six-resource-runtime-20261120.json) 对应 StarCore 运行包 `0C0D340C…`、StarSupply 运行包 `16A1ED43…`。两个玩家分别位于 `(-21.5,86,68.5)` 与 `(-4120.5,70,68.5)`，实体回调在两个不同的 Folia worker 上实际重叠，均通过实体所有权检查。测试使用真实服务器时钟，将隔离服运输速度设为每秒 100 格、补给周期设为 30 秒；物资由管理员通过真实 Cookery/CraftEngine 和原版物品提供方准备，不能计作本轮已证明厨师生产配方。

六类物资各交接 8 件：馒头折算 16 FOOD 单位，其余各 8 单位。玩家切回生存模式后，六个 PDC 库存回执均在实体线程被观察到，批次通过真实库存托管、国家采购与远端交付，6 笔采购各 40、6 笔运费各 15 全部 `PAID`，每个结算事件只出现一次。馒头在背包与 SQL 批次中的序列化字节完全相同，CraftEngine 身份、Cookery `BASE` 品质及食物组件完整保留；装备从含原有 16 件的 24 件堆中托管 8 件，除数量外 NBT 相同，原有物品保持不变。库存收据在完成后清除；本轮没有在操作中途独立捕获磁盘上的非空回执，不能据此替代中断恢复验收。

随后在同一 Folia 进程中加载 Tavern，使用真实 `kaleidoscope_tavern:brandy` 熟成等级 2 的酒品登记 `folia-proof-inn`，手持物品数量和序列化哈希前后一致；亲临 `morale-road` 侦察、发布报告并打开 9x6 情报板，报告和本国合同均可读回。完整记录见 [Tavern Folia 证据](verification/folia-tavern-runtime-20261120.json)。该次配置 `require-authentic-beverage=false`，所以没有在 Folia 重启服上验证开启强制认证时的拒绝分支；Paper 的严格拒绝证据另行记录。

2026-11-20 的旧 Cookery 探针由玩家人工推动磨盘，记录了小麦到面粉和生面团，但当时因蒸笼放置失败而没有销售或运输。该次失败记录仍保留在 [Cookery 部分证据](verification/folia-cookery-production-20261120.json)；其“未完成军粮链”的结论已由下方 2026-11-21 独立重试更新。

新 StarCore 临时士气接口在 Folia 实服的短缺战斗中也已验证：玩家状态显示有效士气 90、短缺贡献 -10；真实攻击伤害为 22.95，单独比较士气影响为 0.90，基础士气仍为 100 且战斗后军队状态按公开快照接口还原。尝试通过正常 FOOD 订单恢复时，卖家没有映射军粮，订单仍为 `OPEN`，没有运输或结算，最终有效士气保持 90。生产探针记录过一个约 8.7 秒的 Folia watchdog 区域线程告警；进程继续运行并恢复周期事件。之后仅作了两次受控蒸笼放置重试，仍未改变世界或库存，随后正常断开厨房测试客户端。详见 [士气与恢复尝试证据](verification/folia-morale-recovery-20261120.json)。

承运者在 6 单均为 `IN_TRANSIT` 时断线，使用同一 UUID 重连后在远端完成全部交付。重复交付返回已有 `DELIVERED`，另一玩家再次领取被拒绝；前后 Alpha 国库 `814.40`、卖家 `6058.45`、承运者 `5084.07` 均不变。该分支验证完成后的重复操作，不等于已验证两个玩家同时竞争首次领取。前线连续五个周期真实消费六类物品，覆盖由 0 增至 100%；现有国策下作战倍率从约 0.93 恢复至 1.13。真实 `ArmyService.attack` 中攻击伤害由基准 30 变为 33.9，敌军生命由 1000 降至 966.1，随后双方通过公开快照恢复接口还原并持久化。

本轮实际记录了 Gamma 建军扣款时的 `SQLITE_BUSY`。StarCore 报告 `rollback=true`，公共查询确认国库保持 `66500.00`；稍后人工发起一次新建军请求成功后余额为 `65500.00`。相关并发写入修复仍需新构建和真实运行证明，不能把本轮成功运输当作该问题已经消失。首次误在创造模式出售的尝试被正常拒绝，库存与付款保持不变；随后生存模式下沿用原采购单完成交接。该轮已正常停服并保存所有玩家数据，完整的闭环后进程重启、同一结算 ID 再次核验仍待后续验证。

上游迁移的 [可移植源码补丁](upstream/starcore-folia-logistics.patch) 已能直接应用到固定原始提交；[构建记录](verification/folia-upstream-targeted-tests-20261120.json) 包含 967 项实际执行通过、4 项明确跳过的定向测试，以及后续可选命令修订的 34 项测试。2026-11-21 又修正了分钟供给向上取整及饥饿士气恢复，并在 StarCore 定向测试中通过 19 项；[补充记录](verification/folia-army-supply-maintenance-20261121.json) 现关联实际 Folia 运行观察。隔离服部署包与当前构建的 StarCore 7,289 个、StarSupply 248 个 class 文件逐一匹配，测试版只覆盖加载描述符。供给从 15 恢复后，在 native supply 为 95 的 152 秒观察窗内，基础士气从 72 恢复到 75。该结果验证了军队维护路径，不代表完整 StarCore 模块或可选插件组合；生产插件仍不声明 Folia 支持。

已实现的自身线程边界：

- 玩家背包、PDC、GUI、位置与物品身份检查在玩家实体调度线程进行，并检查 `Bukkit.isOwnedByCurrentRegion`。
- SQL 与路线规划在受限队列的独立串行工作线程进行；异步回调操作玩家前重新进入实体调度器。
- 周期季节与围城状态从全局任务采样，使用 `Bukkit.isGlobalTickThread()` 检查上下文，再传递不可变数据。
- 可取消玩家操作事件在玩家线程发布，经济和周期消费通知明确标为异步事件。
- 玩家 `saveData()` 与库存收据保存在同一实体线程内完成；不能简单搬到 SQL 线程异步保存玩家对象。

本地 Paper 1.21.11-132 的 `CraftServer` 字节码表明，`isPrimaryThread()` 与 `isGlobalTickThread()` 在该 Paper 版本均委托同一 tick 线程判断。Folia 的全局任务上下文与区域 tick 不同，因此使用更精确的全局 API；Paper JAR 已验证启动采样与周期事件成功；当前 D2BFC503 包还通过首个全局采样、公开停用/重新启用及同 tick 连续三轮启停回归，详见 [验收记录](PAPER-ACCEPTANCE.md)。这只证明 Paper 下的线程路径，不能推出 Folia 服务安全。

剩余兼容工作必须尊重 StarCore 权威：

1. 固定原始 StarCore 没有 Folia 支持声明，仍使用传统调度器。实验补丁迁移了后勤 profile 的活跃调度，已通过真实启动和上述玩法路径。2026-11-22 既有运行包跨区域执行建军和圈地并正常重启，公开状态和存档核对通过；当次没有观察到锁错误，原包也没有成功重试日志标记。其后在独立 Folia 副本中使用克隆 SQLite 数据库和短 WAL 写锁，通过公开 `ArmyService.createArmy` 观察到 `SQLITE_BUSY_RETRY ... retryAllowed=true`，同一建军请求成功后正常停服并在重启关闭数据库中核对余额及完整性。原始 StarCore 数据库、JAR、配置和军队快照已恢复。完整哈希与证据见 [SQLite 忙锁 Folia 运行记录](verification/folia-sqlite-contention-live-retry-20261122.json)、[既有争用复核](verification/folia-sqlite-contention-retest-20261122.json) 与 [上游全量复核](verification/folia-upstream-full-retest-20261122.json)。
2. 实验补丁为军队、围城、SupplyLine 和战争模型补齐了串行访问、独立快照与确认写入锁顺序；玩家通知、GUI 和固定世界位置分别进入实体或区域调度器。双区域库存、异步支付和真实战斗已有上述证据，尚不能据此认定所有模块调用路径均已覆盖。
3. 真实 Folia 中不能假定 `onEnable()` 已处于全局 tick。当前已把首次战役采样移入首个全局任务，并在首个有效快照到达前暂停军需维护；代次标识阻止上次启用的排队回调污染新状态。Paper 生命周期回归和 Folia 真实周期采样均已运行；Folia 不以同实例热重载替代正常进程重启验收。
4. 2026-11-21 的采购/交付交易已经过一次正常 Folia 服重启；采购和运输状态、两种唯一 PAID 结算、StarCore 余额、army snapshot 与前线绑定均在重启后复核。2026-11-22 旧运行包断线时曾留下 `PREPARED` 交接，重连因其他物品组件映射键顺序变化进入 `REVIEW`，之后凭存档证据执行 `untouched` 核对。当前指纹修复构建已在隔离 Folia 玩家流程中复测两条恢复路径：无回执的 `PREPARED` 交接自动撤销、采购恢复 `OPEN` 且无付款；已有持久回执的 `TAKEN/PENDING` 交接重连后只完成一次 `STORED/PAID`。隔离 JAR 仅添加测试用 `folia-supported: true` 描述符，生产描述符未更改。区域卸载与调度器退役恢复已由 2026-11-25 独立记录补验；此前断线记录详见[旧版断线记录](verification/folia-inventory-disconnect-recovery-20261122.json)、[当前构建 Folia 恢复记录](verification/folia-inventory-recovery-20261122.json)及 [Paper v11 库存指纹恢复记录](verification/paper-v11-legacy-fingerprint-recovery-20261122.json)。
5. 2026-11-21 已在隔离 Folia 测试包上完成玩家真实磨麦、和面、蒸馒头、国家采购、运输、前线消耗和一周期战力/士气变化；见 [真实军粮闭环记录](verification/folia-cookery-trade-war-loop-20261121.json)。这修复了 2026-11-20 失败探针留下的生产链缺口，但只验证一件 FOOD，并不覆盖所有六类持续供给。2026-11-22 已补验 Tavern 严格酒品认证的拒绝与通过路径及九插件启动/Music 玩家流程；其他经济提供方、未纳入后勤 profile 的 StarCore 模块和客户端资源包渲染仍需分别验收。

2026-11-22 又在同一隔离 Folia 副本启用 CraftEngine、Cookery、Tavern、Deco、Doll、StarCore 与 StarSupply。8 个插件初始化并启用，Buyer 能打开 StarSupply 主菜单和持久化 Tavern 情报板。CraftEngine 对 Deco 没有未知行为警告，但仍报告 725 条 Doll 行为类型未知；Music 插件未安装，完整插件组合未认证。测试后正常停服并恢复原六插件运行组合。详见 [Deco 与 Doll 部分运行记录](verification/folia-optional-deco-doll-20261122.json)。

随后以 Java 21 对 Music 执行 Gradle `:music:build --rerun-tasks`，14 项 MIDI 测试全部通过，JAR SHA-256 为 `983FB4B88B39BB5211E12EAD23D3202222624E114E2EE4137F48D49D434B1078`。之后对固定森罗提交执行 `series build --rerun-tasks`，五个模块共 330 项测试全部新鲜通过。新构建发现隔离副本中的 Doll JAR 与当前产物不同，现已在未运行的验收副本中替换为新构建哈希 `3CA0E01E19F571F61AEC20F6DCA4211C375FE21FB6690D4B82A62BC1C04551FA`。

随后在独立 Folia 端口 `25629/25639`、768 MiB 最大堆配置下启动九插件验收副本，CraftEngine 与八个 Bukkit 插件全部启用，未见 Doll 未知行为警告或启动错误。Mineflayer 玩家打开 `generic_9x3` 键盘并实际听到键盘音符及三音 MIDI；`/kmusic stop` 阻止了延迟音符，另一客户端确认播放者断线后未再听到延迟音符。隔离服已正常停服并释放监听端口，原 Folia 服务 PID 3416 仍在 `25729/25739` 运行。测试目录已有的 CraftEngine 资源包包含 17 个 musicplus 命名空间条目，但本轮没有重新生成资源包，也没有用图形客户端验证乐器模型渲染。完整运行哈希与客户端证据见 [Music Folia 验收记录](verification/folia-music-stage-20261122.json)；玩家脚本见[验收器说明](../tools/acceptance/music/README.md)。这项结果只覆盖隔离测试副本，不改变生产插件的 Folia 支持声明。

2026-11-22 的 [Tavern 严格认证 Folia 记录](verification/folia-tavern-strict-auth-20261122.json) 使用 `require-authentic-beverage: true`。普通铁锭登记被拒绝，熟成等级 2 的真实白兰地登记成功并标为已验证；同一客户端会话前后的物品数量和序列化哈希未变，测试集合点已移除。此项验证覆盖严格认证分支，不改变生产插件的 Folia 支持声明。

## 2026-11-22 Tavern 严格酒品认证

隔离 Folia 运行使用 `tavern.require-authentic-beverage: true` 与最低熟成等级 1。SupplyBuyer 在生存模式、Alpha 领土和所属区域线程上，手持 4 个铁锭登记被拒绝；改持真实 `kaleidoscope_tavern:brandy`（熟成等级 2）后登记成功，服务器回复已验证。随后移除了临时集合点。操作前后同一客户端会话的三个物品数量与 Bukkit 序列化哈希一致，酒品未被消耗或改写。原配置在正常停服后恢复；运行 JAR 只在描述符加入 Folia 加载标记，非描述符条目与当前生产 JAR 一致。完整版本、哈希、时间和原始证据见 [严格酒品认证记录](verification/folia-tavern-strict-auth-20261122.json)。这仍不构成完整 Folia 插件组合或生产 Folia 支持认证。

## 2026-11-21 真实军粮、采购与前线补给

独立 Folia 1.21.11 会话完成真实玩家链：玩家在生存模式种植并收获小麦，亲自驱动 Cookery 磨盘得到 1 份真实面粉，使用面粉和水制作 1 份生面团，再在岩浆热源上加盖蒸制并取出 `kaleidoscopecookery:mantou`。Cookery 物品保留 `BASE` 品质、`quality_ratio=1.0` 与 `food_v1` 元数据；订单采购配置最低品质 0.3。

Alpha 的国家采购单 `21a05473-678e-4970-bfa2-d37741a37b6c` 以 5.00 真实收购 1 件 mantou；玩家物品从背包扣除，`PURCHASE` 结算 `ac8a34ef-697d-47c1-ad39-523afc7966a5` 为 `PAID`，批次在 SQL 中记为 `FOOD`、2 单位、`BASE`、比例 1.0。军需官创建运单 `cdb9d60c-348d-45c8-8b28-9b5802e23bf0`，承运者领取并交付到 `morale-front`，风险 0%；唯一 `TRANSPORT` 结算为 15.00 `PAID`、状态 `DELIVERED`。重复交付返回原单同一结算 UUID；数据库在正常重启后仍各只有一笔 PURCHASE 和 TRANSPORT，玩家余额与国家国库也由 StarCore 权威服务读取。

为连接本次补给与当时的 StarCore 军队快照，隔离服通过测试夹具调用 `ArmyService.createArmy` 建立 10 人军队 `6f318ddf-5e79-4194-b05f-43f1cc99a6bf`，军队文件在重启后恢复。新增 `/supply rebind-front <前线站ID> <军队ID>` 只更新已有受控前线的 army 绑定；SQL lot、库存、道路、运输状态和位置均保留。重绑定后军队状态从短缺的 0% / ×0.850 / 有效士气 90.0 提升到一周期的 45% / ×0.940 / 有效士气 94.5，并真实消耗 1 FOOD 单位。唯一 mantou 因整件计量随后耗尽；下一周期回到 0% / ×0.850 / 有效士气 90.0。这只证明 StarSupply 临时惩罚随覆盖变化。后续隔离运行日志记录同一军队在旧版 StarCore 原生补给持续衰减后以士气 2 解散；该记录早于当前上游修正。现在小时消耗按分钟余量累计，低于 20 时每分钟扣 2 点，恢复到 20 或以上后每分钟恢复 1 点基础士气。当时的 StarSupply 尚未把实物 FOOD 结转为 StarCore 原生补给；后续 v10 回执闭环见下方独立验收记录。详见 [StarCore 对接与维护限制](STARCORE.md) 和原始闭环记录中的补充说明。

本次运行使用本地隔离服测试 JAR 额外添加 `folia-supported: true` 加载元数据；生产 `target/starsupply-0.1.0.jar` 没有该标记。该测试确认上述交易与周期路径能在此环境运行，不代表 StarSupply 加上固定 StarCore 和全部可选依赖获得完整 Folia 支持。完整环境边界、逐步时间戳、采购/运输/士气快照和原始证据哈希见 [真实军粮闭环记录](verification/folia-cookery-trade-war-loop-20261121.json)。

## v10 原生补给回执运行验收

2026-11-21 随后用当前 StarSupply v10 构建和兼容 StarCore 补丁完成独立 Folia 实服验收。构建包分别为 StarSupply `743F3F188186B475403792BCCED45DD3B6DC22EE7F1F5FBC647FE26EDFCA98A3` 和 StarCore `6D84B645268E9DFCCA7B0766B898C372E32F448CB6B317C919BF65E3B3AE8243`。隔离服 JAR 仅修改 `plugin.yml`，为加载器添加 `folia-supported: true`；逐条 JAR 内容比对确认两个运行副本的其他条目与构建包一致。生产描述符未更改。

运行使用六类物资各 8 件完成采购、运输与唯一结算，每类采购和运费各结算一次并观察到满覆盖。原始事件记录包含该军队的 20 个唯一消费事件，其中 6 个 30 秒周期同时满足六类资源的 1 单位需求，每个事件只发布一次。测试物资由管理员发放，因此不构成六类生存生产验收。实际 FOOD 消耗创建的原生补给 outbox 请求在 StarCore 获得持久回执；停服前及正常重启后重复同一请求均未重复增加补给或改变国库，请求参数冲突被拒绝。重启后 StarSupply 记录仍为 `APPLIED`，军队原生补给为 95，并在超过两个维护周期的 152 秒观察中看到基础士气从 72 恢复到 75。完整步骤、余额、回执、时间戳及运行 JAR 哈希见 [原生补给回执运行记录](verification/starcore-native-supply-receipts-20261121.json)。

该次验收关闭了可选 Cookery 与 Tavern 插件，只覆盖当前 StarSupply/StarCore 业务代码、耐久补给回执和重启重放路径。它与前述单件 Cookery 军粮生产链互补，但不证明完整 StarCore 可选模块、第三方插件或六类持续生产组合通过 Folia。

随后通过最新生产构建的隔离测试副本再次启动同一 Folia 栈。玩家打开 `/supply` 主菜单并读取持久酒馆情报板，服务端记录了 `generic_9x6` GUI；完整运行记录见 [Folia GUI 区域运行记录](verification/folia-ui-region-presentation-20261121.json)。`SupplyUi.present` 已将展示调用路由至玩家实体调度器，主工程仍不声明 Folia 支持。

围城财政与部署补丁只修复对应的玩法缺陷；Folia 需要完整后勤 profile 迁移与对应验收，见 [上游补丁说明](upstream/README.md)。生产支持声明仍保持关闭。

## v11 数据迁移与重启检查

2026-11-22 使用构建哈希 `43216A42FCC8345D12BEC7FCF548C2A87EFF2F2292BBC3487F8349269EBCF110` 在独立 Folia 1.21.11 副本运行。测试副本的 StarSupply JAR 仅增加 `folia-supported: true` 描述符，362 个其他 JAR 条目与构建产物逐一匹配。该服从 v10 schema 10 升级至 schema 11，原有 10,470 条消费记录全部保留为 `interval_ms=0` 幂等保护；采购、运单和结算行数保持为 61、56、112。两次启动均进入服务状态，两次都通过正常停服；第二次启动后 schema 仍为 11，`supply.db`、`insurance.db` 与 `tavern.db` 的 SQLite 完整性检查均为 `ok`。完整哈希、日志路径和数据库计数见 [运行证据](verification/folia-v11-schema-migration-20261122.json)。

此轮只验收 v11 迁移与服务器启停，没有玩家连接。运行仍记录了 CraftEngine 缺少 Deco/doll 行为提供方的内容警告及 StarCore 缺少 ProtocolLib 后使用回退菜单提供方；它不代表完整可选插件组合通过，也不改变生产插件未声明 Folia 支持的状态。

另有 2026-11-22 的独立 Paper v11 迁移记录，使用部分森罗可选插件完成 v10→v11 迁移与正常重启，详见 [Paper 运行证据](verification/paper-v11-optional-plugin-migration-20261122.json)。该记录覆盖 Paper 环境的数据库与启停路径，Folia 组合仍按本页所列运行证据单独评估。

## 2026-11-22 玩家售货断线恢复

隔离服使用旧运行包 `0EC1E4E4396649350F368EE822BCE441EE54C8B99C0DE8C7C4EA57A860B38B76`。第一次在 `/supply sell` 后立即断开时，快照未见交接记录，采购保持 `OPEN`。第二次延迟 50 ms 断开时，采购为 `RESERVED`、交接为 `PREPARED`，但玩家存档保留 4 个铁锭且没有库存收据。重连时，Cookery 白兰地物品与 StarCore 指南书的序列化映射仅发生键顺序变化，旧版完整背包指纹因此不匹配并把交接冻结为 `REVIEW`。这轮安全停止了付款和物资入库。

管理员先比对售货前、断线后和重连后的玩家存档，并确认公开玩家余额始终为 5562.77、Alpha 国库始终为 504.40，随后执行 `/supply reconcile-inventory <交接UUID> untouched`。最终采购为 `OPEN`，交接记录保留为 `RETURNED / no inventory debit`，批次已封存、无结算，玩家仍有 4 个铁锭且无回执。

v11 指纹修复构建改用递归排序后的 Bukkit 序列化映射计算新库存指纹，并在恢复时比对旧 `serializeAsBytes` 哈希以兼容既有待处理记录。回归测试和完整 99 项 `mvn verify` 已通过，该次构建哈希为 `77070BE6D8D25E9325D67DE8A9D04AF7EDC2BBC88AC22EB7F9627FCEA29CF1B3`。该构建已在隔离 Paper 运行中通过旧记录核对和原版物品自动恢复，也已在隔离 Folia 玩家流程中复测 `PREPARED` 无回执自动撤销及持久回执的恰好一次结算。两次 Folia 现场运行均使用仅添加加载元数据的测试 JAR，并在结束后还原原数据库和玩家存档。区域卸载与退役恢复已由本页上方 2026-11-25 新构建独立补验。此前状态与路径见[旧版断线记录](verification/folia-inventory-disconnect-recovery-20261122.json)、[当前构建 Folia 恢复记录](verification/folia-inventory-recovery-20261122.json)和 [Paper v11 库存指纹恢复记录](verification/paper-v11-legacy-fingerprint-recovery-20261122.json)。
