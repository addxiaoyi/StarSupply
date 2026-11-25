# StarSupply 开发进度与换设备交接

更新日期：2026-11-26。接续分支：`codex/strategic-logistics`；仓库：<https://github.com/addxiaoyi/StarSupply>。

当前是可继续开发的阶段检查点，**全部阶段尚未完成**。下表按需求分组汇报，验收口径以 [REQUIREMENTS](REQUIREMENTS.md)、[Paper 记录](PAPER-ACCEPTANCE.md) 和 [Folia 记录](FOLIA.md) 为准。不要把旧构建的运行记录套用于新构建，也不要把管理员准备库存的消费验收当成生存生产验收。

2026-11-26 已完成第十二份 [建国/城邦创建恢复补丁](upstream/CREATION-RECOVERY.md)：原意图与目录同存、原编号扣款/退款、终态重放、管理员恢复/取消、重入和待恢复日志保护；另修复 SQL 资金旧快照覆盖其他实例普通收入，并保护重载后的旧异步队列。1,322 项 Core 定向回归及实际最终 Core 下 StarSupply 177 项通过；14 个生产源空目录编译，21 文件补丁重放后全源码一致。详见[验证记录](verification/workspace-creation-recovery-20261126.json)。圈地付款、完整跨实例国家生命周期、全仓 Core、最新 MySQL 与实服仍待完成。

此前（第十二份补丁实施前）2026-11-26 第十一份补丁后的源码复核补充了三个国家侧风险：圈地扣款/退款只有进程内补偿，分裂旧快照可能覆盖后续写入，创建及解散可覆盖未完成的单一日志。建国资金恢复仍在分析设计，尚未提交第十二份实现补丁。详见[当前代码闭环清单](CODE-CLOSURE.md)和[源码复核证据](verification/nation-lifecycle-source-audit-20261126.json)。此次只更新审计记录，生产代码、JAR 和上一轮测试结论未变。

此前续接已同步检查 GitHub，恢复构建并修复生存验收脚本的熔炉断点和方块放置确认。该轮 124 项 Java、47 项 Node、7 项 PowerShell 恢复检查全部通过，复现当时的 87487098 生产 JAR，玩家存档也与交接哈希一致。本机初查约 325 MiB 可用物理内存，用户明确选择“先完成能运行的开发和测试，保留实服待验项”，此约束继续生效。新代码的实服结果仍待验证，见 [续接验证记录](verification/workspace-survival-resume-20261125.json)。

后续已补齐 `logistics` 验收阶段，将六类现有背包物资通过正常采购、运输接入至少三个连续完整消费周期，再核对唯一付款、实物批次、原生补给 outbox 与停服 NBT。新增 11 项真实 SQLite 夹具测试，总计 58 项 Node 测试通过。旧库的混合库存被正确拒绝，原库和玩家存档未变；该阶段仍待实服运行，不能替代生产来源或 Core 重启回执证据。见 [物流阶段记录](verification/workspace-survival-logistics-20261125.json)。

继续开发已将物流日志升级为先刷盘临时文件再替换的 v2 格式，按既有订单恢复，避免重发已记录的经济命令；原始背包基线跨尝试保留。新增生产记录与存档哈希串联，以及 `logistics-replay` 独立重启阶段：按关联 UUID 和探针启动 ID 核验原生补给回执、既有结算/消费与玩家存档。初始已有物资不自动获得生存来源证明，旧 v1 日志也不自动升级。代码与自动化测试范围见[恢复和证据串联记录](verification/workspace-survival-recovery-evidence-20261125.json)；这些新路径尚未在 Folia 实服执行。

最新开发增加 `supply-plan`：由探针读取实际军队与军需快照，使用已部署的生产规则计算冬季/围城需求上限及交货缓冲，正常停服后生成数量计划。六个生产阶段接受该计划，按配方批量与已有库存补产，NBT 也检查同一目标；每次预算生产/交付前重新读取需求，增加兵力或需求上限时停止并要求重新计划。91 项 Node 回归、7 项恢复检查及 Java 探针构建通过；未启动实服。详见[数量计划记录](verification/workspace-survival-demand-planning-20261125.json)。

## 阶段进度

2026-11-26 最新检查点追加第十一份[演习恢复补丁](upstream/EXERCISE-RECOVERY.md)：演习持久保存、唯一终态、国家经验耐久回执、双向冷却、权限与实体线程/启停接线已完成，加入实验 Folia 后勤 profile。1,238 项 Core 定向回归（演习/经验 87 项）和实际最终 Core JAR 下 StarSupply 177 项全部通过，见[证据](verification/workspace-exercise-recovery-20261126.json)。45 文件补丁可在前十份之后重放；Core SHA-256 为 `B2B19F1F5EEDA9FC81A4085CC026A45C6A563427F42121282FB9E2DFA1BE48EA`。实服及全仓干净构建仍待验。

本轮额外核实建国/城邦创建收费仍依赖普通扣款/退款与余额推断，已列为下一 P1；不能因演习经验安全而认定国家全部资金路径闭环。演习失效收件国奖励处置、单进程存储/归档，以及海军真实运兵、伤兵、学说、其他模块和预算运维仍见[当前清单](CODE-CLOSURE.md)。以下段落保留各历史检查点状态。

2026-11-26 已追加第十份[预算恢复补丁](upstream/BUDGET-RECOVERY.md)：预拨、国库补差和退款先保存原编号意图，再凭耐久资金回执完成；跨保存中断、SQLite 多实例竞争、丢响应和旧退款迁移已有测试。876 项 Core 定向回归通过（预算 81 项）；最新成品哈希与配套测试见[预算记录](verification/workspace-budget-recovery-20261126.json)。待恢复付款的安全撤销/管理入口、旧月份余款结转和归档仍待开发。下一优先项是演习保存和唯一结算，全部阶段尚未完成。

2026-11-26 已追加第九份[海军可靠性补丁](upstream/NAVY-RELIABILITY.md)：完整/短 UUID 授权、直接国库原编号回执恢复、舰队串行保存、分钟补给余量、海战冷却与启停已修复。718 项 Core 定向回归通过。真实军队运输、港口补给仍未接通，海军仍不进入实验 profile。该海军检查点曾用真实文件复现预算服务的中断重复扣款；后来由第十份修复新协议，海军仍保持原直接国库来源。总目标仍未完成，当前优先级见[代码清单](CODE-CLOSURE.md)。

2026-11-26 已追加第七份 [Religion 闭环补丁](upstream/RELIGION-CLOSURE.md)：SQLite 串行事务、耐久命令反馈、离线排行榜、完整圣地范围索引、实体线程和启停接线已完成。466 项 Core 定向测试（Religion 新增 37 项）与实际最终 Core 成品下 StarSupply 全套 177 项通过。未启动实服；独立国家 Faith、其他未适配模块、回执归档、全量 Core 与 MySQL 验证仍未闭环，见[代码清单](CODE-CLOSURE.md)和[本轮证据](verification/workspace-religion-closure-20261126.json)。

2026-11-26 已追加第六份 [Clan 闭环补丁](upstream/CLAN-CLOSURE.md)：存取款接入真实玩家资金与耐久回执，氏族元数据先于转账落盘，损坏存档冻结写入；修复聊天状态、成员并发、权限、命令结果和实体线程回调，并接入启动/停用及实验后勤 profile。370 项 Core 定向测试（新增 48 项 Clan）与该实际 Core 成品下 StarSupply 全套 177 项通过。该检查点未启动实服，当时 Religion 尚未适配；其缺口已由后续第七份补丁修复。见[本轮验证](verification/workspace-clan-closure-20261126.json)。

2026-11-26 已继续补齐 HUD 的实际设置与菜单行为，追加第五份 [HUD 设置补丁](upstream/HUD-SETTINGS.md)：个人样式、粒子与呼吸开关持久化，权限控制下真实重载，彩色主题和实体线程背景动画，以及禁用模块入口和设置导航。Core 定向 154 项与新 Core 成品下 StarSupply 全套 177 项通过。没有启动实服；完整 Folia 模块和当前组合验收仍待完成。见[本轮验证](verification/workspace-hud-settings-20261126.json)。

此前第四份反馈补丁已完成音效、客户端震动和 HUD 线程/会话适配；该轮 132 项 Core 与 177 项配套测试的历史证据仍见[反馈验证](verification/workspace-folia-feedback-20261126.json)。

2026-11-25 最新代码已接通按来源补产：示例计划启用 `traceProduction`，六阶段按已记录来源的库存计算缺口，正常停服后由独立读取器重算配方与存档链。生产日志保存跨阶段余额与待完成运行，支持结果已落盘而日志尚未推进时的恢复；旧库存不自动获得证明，旧熔炉内容先正常收回再补产。顺带修正打开箱子/熔炉时读取滞后背包副本的问题。107 项 Node、39 项语法检查及 7 项 PowerShell 恢复检查通过，生产 Java 未变，未启动实服。详见[来源补产验证](verification/workspace-survival-traced-production-20261125.json)。

随后新保费入国库已接入原固定 StarCore 的 `NationCreditService`，按保单原键和持久协议标记恢复；旧普通入账不自动换协议。新增 13 项回执测试使用真实国库、SQLite 与 TransactionFabric，验证响应丢失、各层结果写回失败、同进程/重启恢复和冲突隔离；全量 137 项 Java 测试通过。该检查点当时尚无玩家业务回执；税收认领也不等于国库到账。本轮未启动实服，详见[保费回执记录](verification/workspace-premium-credit-receipts-20261125.json)。

最新补齐玩家资金耐久回执：Core 将余额与业务 UUID 一起提交，StarSupply 新采购款、运费、理赔、保费扣款/返还按原编号恢复。148 项完整 Java verify、最终 Core 兼容包下的 159 项回归及上游 77 项定向测试通过；旧无标记交易和税款不明仍保守冻结，当前组合未实服验证。见[玩家资金回执记录](verification/workspace-player-money-receipts-20261125.json)及[上游接口与构建说明](upstream/PLAYER-MONEY.md)。

2026-11-26 又完成新税款的权威回执恢复：税务意图与国库凭证绑定完整原报价，丢失响应、事务文件或 SQL 写回失败可沿原编号推进；人工 funded/unfunded 不能覆盖权威结果。国库不明状态及新格式损坏快照均拒绝被旧内存覆盖。157 项完整 verify、最终兼容 Core 下 177 项回归通过；上游 116 项定向测试中 114 项执行通过、2 项 MySQL 环境测试跳过。旧无协议税款仍 REVIEW。见[税款回执记录](verification/workspace-tax-funding-receipts-20261126.json)。

| 阶段 | 当前结果 | 后续工作 |
| --- | --- | --- |
| 核心后勤与经济 | 六类资源、国家仓库/前线、采购托管、运输护送、道路风险、区域市场、协定/禁运/关税、战时出口控制、保险、GUI/API 已实现；有 Paper 和指定 Folia 构建的真实闭环记录 | 新设备恢复构建，按需求表回归当前组合 |
| 战役与原生补给 | 冬季/围城需求、临时战力/士气、实际 FOOD 消费结转 StarCore 耐久回执、重启重放已实现；既有 Folia 记录覆盖六个消费周期和士气恢复 | 继续以六类生存自产物资完成持续作战验收 |
| 一致性与中断恢复 | 主库 v11、酒馆 v1、保险 v2；库存指纹兼容、SQL 不确定提交隔离、停用/重启屏障、实体回调退役、保险结果写回中断、调用方取消 future 的库存锁清理均已加固；新保费、玩家资金及税款回执恢复；157 项 Java verify 和 177 项兼容回归通过 | 旧无协议标记的玩家资金、保费和税款不明仍需复核；当前 JAR 未实服复测 |
| Folia 区域恢复 | 此前 124 项测试构建实际验证实体 scheduler 退役、未执行回调清理、区块卸载/区域消失、重连新区域；未扣物撤销和已保存回执恰好一次结算均通过 | 夹具固定了故障边界，尚不代表当前新 JAR、整个售货入口随机中断、世界整体卸载 API 或完整插件组合 |
| 六类生存生产 | 六阶段脚本入口已实现；实际存档有 6 煤、6 粗铁、146 圆石、铁镐，生命/饥饿各 20；之前真实 Cookery 军粮生产只验证 1 FOOD/1 周期 | 完成烧炼/瓶子、地表工具、种植面包、采蜜、脚手架、烟花；再采购、运输、持续消费。六阶段目前未通过整体验收 |
| 完整组合与交付 | 旧九插件 Folia 组合已启动，Music 键盘/MIDI/停止/断线清理有记录；可移植 StarCore 补丁、构建和验收工具在仓库 | 当前完整可选插件组合、相关 Core 路径的生产 Folia 认证、图形客户端资源包渲染仍未完成；生产 `plugin.yml` 仍不声明 Folia 支持 |

## 最新构建与验证

- 最新创建恢复 Core：`F6B5BCF965CD2279B096A55D9961DDF00E3D1A5F5E428698293D8C12077DC91B`（19,796,345 字节）；1,322 项定向测试及配套 177 项通过。21 个 class 与空目录编译一致，其余旧 JAR 条目未变；生产 Folia 声明关闭，实服未启动。下方是各历史检查点。

- 最新预算恢复检查点 Core：`E934CF6E84C8DC08B71910A9B2192F4FFDA4B623F10852133ABC209A30EE95DC`（19,729,727 字节），876 项定向回归通过，预算专项 81 项。13 文件补丁重放后全部 3,562 个 src 文件匹配；13 个预算 class 与空目录编译结果一致，移除旧 SpendingMutation class，其他既有 JAR 内容不变。不是全仓干净 verify，MySQL 和实服待验。详见[本轮记录](verification/workspace-budget-recovery-20261126.json)。
- 此前海军可靠性检查点 Core：`7CBBF81FB402E3B888D097D7213DC15585B68FEE39A36640C978FC6EBDB55AB4`（19,713,101 字节），718 项定向回归通过。22 文件补丁重放后全部 3,557 个 src 文件匹配；25 个海军 class 与空目录编译结果一致，其他既有 JAR 内容保持一致（除本次三份资源）。不是当前全仓干净 verify，未启动实服。最终兼容测试及详细证据见[本轮记录](verification/workspace-navy-reliability-20261126.json)。下方 Faith 构建是历史检查点。

- 最新国家 Faith 检查点：StarSupply 生产源码和 157 项 verify 的 JAR 未变；Core `E3CCA19A08A602197FB9C816BE85D405BA0FDB6286455D1B3BDD43F4654895B7`（19,684,304 字节）通过 629 项定向测试，配套 StarSupply 177 项通过。26 个改动生产源在空输出目录重新编译，9 个改动测试源编译、35 个 Java 格式检查通过。第八份 38 文件补丁重放后全部 3,551 个 src 文件匹配；46 个编译 class 与 JAR 字节一致，仅移除已删除的匿名 Faith 退出监听 class。实服未运行，原玩家存档哈希不变。见 [Faith 证据](verification/workspace-faith-closure-20261126.json)；之前 Religion 证据仍独立保留。

- Java 21；Maven Wrapper 3.9.16；Paper API 1.21.11。当前生产 JAR 大小 14,695,959 字节，SHA-256 `267DC0BB068714DFFB70D26C65B5AD4F09C7FF8E878489BD211D2A837C99C38C`。
- 完整 `mvnw.cmd -o -B -ntp -DforkCount=0 verify`：157 项测试，0 失败/错误/跳过；最终实际兼容 Core 下 177 项通过，包含 11 个真实玩家资金场景与 9 个真实税款场景。Core 财政定向测试 116 项：114 项执行通过，2 项 MySQL 环境测试跳过。未改写 Maven 依赖、未启动 Paper/Folia。详见[税款记录](verification/workspace-tax-funding-receipts-20261126.json)；此前玩家接口证据仍见[玩家资金记录](verification/workspace-player-money-receipts-20261125.json)。
- 验收器：当前 107 项 Node 测试通过；39 个 Node 脚本语法检查通过，7 项 PowerShell 恢复检查复跑通过。最新来源补产、容器库存读取和日志恢复见[来源补产验证](verification/workspace-survival-traced-production-20261125.json)。需求探针已用 Java 21 编译；此前数量计划见 [数量计划记录](verification/workspace-survival-demand-planning-20261125.json)，恢复和证据串联见 [恢复记录](verification/workspace-survival-recovery-evidence-20261125.json)。
- 此前 87487098 构建的独立 Folia 区域恢复：两种库存边界、实际退役异常栈、唯一付款、重复恢复不变、停服玩家 NBT 和六库完整性均通过。见 [区域恢复记录](verification/folia-region-retirement-20261125.json)。
- 最后实服运行 `2026-11-25T07-52-02-144Z`：Core/Supply/服务器均启用；Mineflayer 进程因内存分配失败退出 134，阶段未通过。服务器随后经 RCON 正常保存退出，两个端口关闭，全部插件按哈希恢复。玩家存档仍保留前述材料。

本机可用物理内存和 Windows 提交余量波动较大，曾在启动前预检通过后又耗尽原生内存。`vanilla` 默认堆 384 MiB，`full` 默认 1024 MiB，另保留 512 MiB 给客户端和原生分配。新设备应留足余量，勿通过降低验收标准绕过 OOM。

## 代码闭环与剩余范围

第九份已修复海军授权、付款恢复和分钟补给；第十份已修复预算新协议的跨保存重复扣款。下一优先项是演习空存档与唯一结算，其后继续真实运兵/港口、伤兵回补、学说实效、雇佣兵和社交线程/生命周期、预算运维/结转及回执归档。当前完整 Core 干净构建与 MySQL 验证也仍待完成；详见 [代码闭环清单](CODE-CLOSURE.md)。

| 缺口 | 当前处理 | 完成条件 |
| --- | --- | --- |
| 玩家资金恢复的部署与旧账处理 | 新回执协议代码与自动化故障测试已完成；旧无标记交易不迁移，已有 REVIEW 不自动解冻 | 部署兼容 Core 后进行实服验收；旧账继续按独立证据核对。MySQL 存储与大规模历史回执性能仍未验证 |
| 税款自动恢复 | 新协议已通过完整报价绑定的国库耐久回执恢复，意图不作为到账凭证；SQL/事务记录写回失败保持原编号重试，人工标志不能制造到账 | 当前新组合待实服、MySQL 与规模验收；旧无协议税款继续独立核对 |
| 完整 Folia 支持范围 | 后勤 profile 已接回音效、客户端震动、HUD、Clan 和 Religion；国家 Faith 已加入，其他未适配模块仍限制；生产描述符未声明支持 | 完成目标模块/可选插件的线程归属与生命周期适配，并对明确组合验收；不能只改描述符 |
| Religion 宗教与圣地 | 已完成耐久命令反馈、SQLite 串行保存与失败恢复、离线排行榜、范围索引和实体/启停接线；新增 37 项行为测试 | 当前公开功能的代码与定向自动化已闭环；实服效果仍待验；独立国家 Faith 后由第八份补丁完成 |
| 国家 Faith | 原子保存、失败处理、持久冷却/每日奖励/祝福、权限与实际四类加成、owner 回调和启停重载已完成，93 项 Faith 测试通过 | 同步快照写入的规模/延迟、跨区域联机及效果待验 |
| Clan 功能与资金 | 已完成真实存取款、耐久恢复、存档屏障、聊天/权限/并发和生命周期接线；新增 48 项行为测试 | 代码与定向自动化已闭环；当前组合的现场迁移、跨区域操作和重启仍待验 |
| 历史流水规模与存储 | 玩家/国库/Clan 新回执保证原编号恢复；Clan 使用完整文件快照，没有历史归档/压缩协议 | 补 MySQL 与大规模历史数据验证，再完成有界存储方案；归档不能删除重放保护 |
| HUD 偏好命令与动画 | 已接通个人设置持久化、默认配置重载、管理员权限、彩色背景动画、禁用模块入口及实际导航；关闭/重连/停用回调与保存失败有行为测试，详见 [HUD 设置](upstream/HUD-SETTINGS.md) | 本轮代码与自动化闭环完成；当前新 JAR 的实服界面/效果仍待验 |

六类来源补产、采购运输、连续消费及重启核对入口已经实现并有自动化测试，剩余是实服证明。当前无法将其标为六类完整生存闭环；图形资源包也属于待验项。

## 新设备获取与构建

```powershell
git clone --branch codex/strategic-logistics https://github.com/addxiaoyi/StarSupply.git
Set-Location StarSupply
```

已克隆的设备先提交或妥善保存自身改动，再执行 `git fetch origin`、切换该分支并 `git pull --ff-only`。

编译依赖使用原始 StarCore 固定提交 `f769e62799e769a18fe51305b545869a17f80cd1`。先单独检出并安装此版本，再构建 StarSupply：

```powershell
git clone https://github.com/addxiaoyi/starcore.git ..\starsupply-starcore-api
git -C ..\starsupply-starcore-api checkout --detach f769e62799e769a18fe51305b545869a17f80cd1
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\tools\build.ps1 -StarCoreRoot ..\starsupply-starcore-api -JavaHome $env:JAVA_HOME
```

运行时则需要兼容 Core。另开原始固定提交的干净工作树，按 [上游补丁说明](upstream/README.md) 依次应用 `starcore-folia-logistics.patch`、`starcore-player-money-receipts.patch`、`starcore-tax-funding-receipts.patch`、`starcore-folia-feedback.patch`、`starcore-hud-settings.patch`、`starcore-clan-closure.patch`、`starcore-religion-closure.patch`、`starcore-faith-closure.patch`、`starcore-navy-reliability.patch`、`starcore-budget-recovery.patch`。后勤组合已包含攻城补丁，不能重复叠加旧攻城补丁。生产仍以 Paper 为目标，实验 Folia 使用后勤 profile；描述符覆盖只用于隔离测试。当前兼容 Core SHA-256 为 `E934CF6E84C8DC08B71910A9B2192F4FFDA4B623F10852133ABC209A30EE95DC`。

依赖已安装时，低内存构建命令为：

```powershell
$env:MAVEN_OPTS = '-Xms32m -Xmx256m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
.\mvnw.cmd -B -ntp '-DforkCount=0' verify
$tests = Get-ChildItem tools/acceptance/survival-progression -Filter '*.test.cjs'
node --max-old-space-size=64 --test --test-concurrency=1 @($tests.FullName)
.\tools\acceptance\survival-progression\restore-stage.test.ps1
```

仓库不包含 `target`、依赖缓存、验收插件编译输出、崩溃转储或测试世界。需要 JAR 时在新设备构建；审查 ZIP 可用 `tools/package-review.ps1 -StarCoreJar <兼容Core.jar>` 生成。

## 接续生存流程

接续检查点仍从 `mining` 开始。完整军需验收应先按正常权限准备专用空仓库/前线、道路和军队，安装当前隔离探针并运行 `supply-plan`；将生成的同一计划依次传给 `mining`、`surface`、`farming`、`honey`、`scaffolding`、`fireworks` 和 `logistics`。不传计划时保留旧数量默认值。入口、数量预算及存档条件见 [生存运行说明](../tools/acceptance/survival-progression/README.md)。

严格自产验收保留示例计划的 `traceProduction: true`，通过运行器启动；来源日志由运行器自动维护，不再手工追加 `productionEvidence`。旧存档的 6 粗铁、146 圆石等不作新来源证明，客户端会另补可追溯数量；旧物资不通过管理员命令清空。失败后正常保存且有有效记录时可续跑，存档变化而证据缺失时停下核查。进物流前先验证六阶段来源数量；不足不会创建物流基线，仍可继续生产。新流程尚未在实服执行。

需要重点继续实测的代码是 `six-resources.cjs` 的工作台/熔炉放置、垫方块上升、地表分段寻路和天然地牢取火药。旧路径会从 y=49 直接找沙而失败，现已改为先烧炼再上升；改后尚未成功跑通。服务器挖掘确认包装必须在 Mineflayer 登录后安装，避免被其异步插件覆盖；这项最终时点修正只有单元测试，尚无成功的后续实服确认。饲养/种植和正常掉落不能用免费物品代替。

最新续接将放置操作也接入单方块/批量方块协议确认，不再仅凭客户端缓存判断成功。熔炉续跑会先计入炉内原料、成品和打开窗口中的玩家库存，再补足材料；途中燃料耗尽时从真实煤/木炭补充，异类物品不改动，缺料和超时会停止。新增测试覆盖了这些边界，但仍需从 `mining` 开始现场验证。

运行器会记录脚本哈希，在正常停服后保存失败阶段的权威背包检查点，但只有目标产物在生存模式 NBT 中持久存在才允许阶段通过。已完成的物品可以断点续用；纸、瓶子、煤和工作台也会被后续配方消耗，需检查真实背包。现有世界为 peaceful，烟花脚本计划从自然生成地牢箱取得火药；这是待验收路线。

完成六类生产后，用正常权限建立专用空仓库、前线和道路，填写 `logistics-plan.example.json`，再运行 `-ClientArguments @('logistics', '<计划JSON绝对路径>')`。旧 `alpha-depot` / `morale-front` 有历史物资，当前不能直接用于这条严格归因验收。客户端只走已有通道；测试玩家需拥有正常采购管理权限。数量须覆盖真实季节/围城需求和交货期间可能发生的部分周期。v2 中断日志支持使用相同计划继续观察、完成后续动作；无法确定是否受理的命令不会重发，旧 v1 日志仍须核查。可在开始前配置 `productionEvidence` 和 `nativeReplay`，完成正常停服后再执行 `logistics-replay`。完整命令和证据边界见[运行说明](../tools/acceptance/survival-progression/README.md)。

## 旧设备的本地状态

GitHub 提供源码和验证摘要，**不会同步世界、玩家存档或运行数据库**。要接着同一个玩家的进度跑，应在停服状态下私下整体复制下面的测试服目录；必须一起迁移世界/玩家、StarSupply 与 StarCore 数据库、配置和匹配的插件，不能只搬单个 `.dat` 或某一个数据库。

| 用途 | 旧设备路径 |
| --- | --- |
| 当前生存隔离服（首要迁移） | `C:\tmp\starsupply-folia-sqlite-retry-runtime-20261122` |
| 当前构建区域恢复隔离服 | `C:\tmp\starsupply-folia-region-retirement-20261125` |
| 九插件历史完整组合 | `C:\tmp\starsupply-folia-music-full-stack-20261122` |
| 兼容 Core 源码/成品 | `C:\Users\Administrator\Documents\starsupply-deps\starcore-folia` |
| Mineflayer 依赖 | `C:\Users\Administrator\Documents\starsupply-deps\minecraft-client-1.21.11\node_modules\mineflayer` |
| Pathfinder 依赖 | `C:\tmp\starsupply-survival-progression-client\node_modules\mineflayer-pathfinder` |

当前玩家 `SupplyCook`，离线 UUID `904364cc-bf39-35a0-b688-6cc8a6d846c8`，位置 `[5.499105686866461,49,58.4309376655394]`。保存文件 `starsupply-stage1-world/playerdata/<UUID>.dat` 的 SHA-256 为 `747194DCF7BBF6FAA52996FA9DA2A59939227354612FC69A0F05AC92D0BD24D0`。测试游戏/RCON 端口为 `25729/25739`，仅绑定 loopback；在新设备保持隔离。没有迁移世界时，可继续代码开发和测试，但不能宣称沿用这个生存检查点。

原始日志、测试 JAR 和插件恢复清单在各测试服 `folia-proof/`。结构化证据已加入 `docs/verification/`。旧设备退出前确认无测试 Folia JVM、两个端口关闭、无 pending 插件恢复清单。

## 给下一次 Codex 的接续说明

> 读取 docs/HANDOFF.md、docs/CODE-CLOSURE.md、docs/REQUIREMENTS.md、docs/FOLIA.md 和 workspace-budget-recovery-20261126.json，继续全部剩余阶段。用户选择先完成可运行开发测试、保留实服待验，不自行启动测试服。十份补丁已有 876 项 Core（预算 81 项）回归通过，实际 Core JAR 配套结果以最新证据为准。预算预拨/支出/退款可按原编号恢复；仍缺安全撤销/管理入口、旧月份余款结转和归档。优先补演习保存及唯一结算，其后真实运兵/港口、伤兵回补、学说实效、雇佣兵/社交线程和生命周期。当前 Core 全仓干净 verify、MySQL 和实服门槛继续待验。保留本地 Core 改动和历史证据，只同步 StarSupply codex/strategic-logistics，不推 Core、不合并 main。
