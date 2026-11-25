# StarSupply 开发进度与换设备交接

更新日期：2026-11-26。接续分支：`codex/strategic-logistics`；仓库：<https://github.com/addxiaoyi/StarSupply>。

当前是可继续开发的阶段检查点，**全部阶段尚未完成**。下表按需求分组汇报，验收口径以 [REQUIREMENTS](REQUIREMENTS.md)、[Paper 记录](PAPER-ACCEPTANCE.md) 和 [Folia 记录](FOLIA.md) 为准。不要把旧构建的运行记录套用于新构建，也不要把管理员准备库存的消费验收当成生存生产验收。

此前续接已同步检查 GitHub，恢复构建并修复生存验收脚本的熔炉断点和方块放置确认。该轮 124 项 Java、47 项 Node、7 项 PowerShell 恢复检查全部通过，复现当时的 87487098 生产 JAR，玩家存档也与交接哈希一致。本机初查约 325 MiB 可用物理内存，用户明确选择“先完成能运行的开发和测试，保留实服待验项”，此约束继续生效。新代码的实服结果仍待验证，见 [续接验证记录](verification/workspace-survival-resume-20261125.json)。

后续已补齐 `logistics` 验收阶段，将六类现有背包物资通过正常采购、运输接入至少三个连续完整消费周期，再核对唯一付款、实物批次、原生补给 outbox 与停服 NBT。新增 11 项真实 SQLite 夹具测试，总计 58 项 Node 测试通过。旧库的混合库存被正确拒绝，原库和玩家存档未变；该阶段仍待实服运行，不能替代生产来源或 Core 重启回执证据。见 [物流阶段记录](verification/workspace-survival-logistics-20261125.json)。

继续开发已将物流日志升级为先刷盘临时文件再替换的 v2 格式，按既有订单恢复，避免重发已记录的经济命令；原始背包基线跨尝试保留。新增生产记录与存档哈希串联，以及 `logistics-replay` 独立重启阶段：按关联 UUID 和探针启动 ID 核验原生补给回执、既有结算/消费与玩家存档。初始已有物资不自动获得生存来源证明，旧 v1 日志也不自动升级。代码与自动化测试范围见[恢复和证据串联记录](verification/workspace-survival-recovery-evidence-20261125.json)；这些新路径尚未在 Folia 实服执行。

最新开发增加 `supply-plan`：由探针读取实际军队与军需快照，使用已部署的生产规则计算冬季/围城需求上限及交货缓冲，正常停服后生成数量计划。六个生产阶段接受该计划，按配方批量与已有库存补产，NBT 也检查同一目标；每次预算生产/交付前重新读取需求，增加兵力或需求上限时停止并要求重新计划。91 项 Node 回归、7 项恢复检查及 Java 探针构建通过；未启动实服。详见[数量计划记录](verification/workspace-survival-demand-planning-20261125.json)。

## 阶段进度

2026-11-26 继续完成 Core 音效、屏幕震动和现代 HUD 的线程与生命周期适配，新增第四份 [反馈补丁](upstream/FOLIA-FEEDBACK.md)。屏幕反馈不再传送玩家，HUD 按实体处理菜单与粒子，旧任务退役不会清除新会话；独立音效配置与注册名称解析也已修复。最终 Core 定向 132 项及新 Core 成品下 StarSupply 全套 177 项通过；没有启动实服。完整模块兼容和 HUD 部分偏好命令仍有代码工作，见[本轮验证](verification/workspace-folia-feedback-20261126.json)。

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

- 本轮 StarSupply 生产和测试源码未变，保留 157 项 verify 与原 JAR 哈希；新 Core 为 `E87A005E2E313040D9F41F75B878C9DF75D6A5211D011B14CD76907D2E17FBA8`（19,586,514 字节），定向 132 项通过，与该成品配套的 StarSupply 全套 177 项通过。11 个生产源与 8 个测试源增量编译，19 个 Java 文件格式检查通过；不是 Core 全仓 verify。20 文件补丁可复现，原生存存档哈希不变。见[反馈适配记录](verification/workspace-folia-feedback-20261126.json)。

- Java 21；Maven Wrapper 3.9.16；Paper API 1.21.11。当前生产 JAR 大小 14,695,959 字节，SHA-256 `267DC0BB068714DFFB70D26C65B5AD4F09C7FF8E878489BD211D2A837C99C38C`。
- 完整 `mvnw.cmd -o -B -ntp -DforkCount=0 verify`：157 项测试，0 失败/错误/跳过；最终实际兼容 Core 下 177 项通过，包含 11 个真实玩家资金场景与 9 个真实税款场景。Core 财政定向测试 116 项：114 项执行通过，2 项 MySQL 环境测试跳过。未改写 Maven 依赖、未启动 Paper/Folia。详见[税款记录](verification/workspace-tax-funding-receipts-20261126.json)；此前玩家接口证据仍见[玩家资金记录](verification/workspace-player-money-receipts-20261125.json)。
- 验收器：当前 107 项 Node 测试通过；39 个 Node 脚本语法检查通过，7 项 PowerShell 恢复检查复跑通过。最新来源补产、容器库存读取和日志恢复见[来源补产验证](verification/workspace-survival-traced-production-20261125.json)。需求探针已用 Java 21 编译；此前数量计划见 [数量计划记录](verification/workspace-survival-demand-planning-20261125.json)，恢复和证据串联见 [恢复记录](verification/workspace-survival-recovery-evidence-20261125.json)。
- 此前 87487098 构建的独立 Folia 区域恢复：两种库存边界、实际退役异常栈、唯一付款、重复恢复不变、停服玩家 NBT 和六库完整性均通过。见 [区域恢复记录](verification/folia-region-retirement-20261125.json)。
- 最后实服运行 `2026-11-25T07-52-02-144Z`：Core/Supply/服务器均启用；Mineflayer 进程因内存分配失败退出 134，阶段未通过。服务器随后经 RCON 正常保存退出，两个端口关闭，全部插件按哈希恢复。玩家存档仍保留前述材料。

本机可用物理内存和 Windows 提交余量波动较大，曾在启动前预检通过后又耗尽原生内存。`vanilla` 默认堆 384 MiB，`full` 默认 1024 MiB，另保留 512 MiB 给客户端和原生分配。新设备应留足余量，勿通过降低验收标准绕过 OOM。

## 代码闭环与剩余范围

| 缺口 | 当前处理 | 完成条件 |
| --- | --- | --- |
| 玩家资金恢复的部署与旧账处理 | 新回执协议代码与自动化故障测试已完成；旧无标记交易不迁移，已有 REVIEW 不自动解冻 | 部署兼容 Core 后进行实服验收；旧账继续按独立证据核对。MySQL 存储与大规模历史回执性能仍未验证 |
| 税款自动恢复 | 新协议已通过完整报价绑定的国库耐久回执恢复，意图不作为到账凭证；SQL/事务记录写回失败保持原编号重试，人工标志不能制造到账 | 当前新组合待实服、MySQL 与规模验收；旧无协议税款继续独立核对 |
| 完整 Folia 支持范围 | 后勤 profile 已接回音效、客户端震动和 HUD；Clan、Religion 及其他白名单外模块仍限制；生产描述符未声明支持 | 完成目标模块/可选插件的线程归属与生命周期适配，并对明确组合验收；不能只改描述符 |
| HUD 偏好命令 | 已确认 `style` 未把所选样式用于主菜单、`breathing` 只发消息、`reload` 未加载实际配置；本轮仅完成所有权和会话适配 | 接通真实设置、重载与退出清理，并用行为回归证明命令结果 |

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

运行时则需要兼容 Core。另开原始固定提交的干净工作树，按 [上游补丁说明](upstream/README.md) 依次应用 `starcore-folia-logistics.patch`、`starcore-player-money-receipts.patch`、`starcore-tax-funding-receipts.patch` 和 `starcore-folia-feedback.patch`；这个组合补丁已包含攻城补丁，不能再重复叠加旧攻城补丁。生产仍以 Paper 为目标，实验 Folia 使用后勤 profile；描述符覆盖只用于隔离测试。本轮兼容 Core 成品 SHA-256 为 `E87A005E2E313040D9F41F75B878C9DF75D6A5211D011B14CD76907D2E17FBA8`，不同构建需登记自己的哈希。

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

> 读取 docs/HANDOFF.md、docs/REQUIREMENTS.md、docs/FOLIA.md 和最新 verification JSON，继续完成全部剩余阶段。用户当前选择先完成可运行的开发测试、保留实服待验；继续完整 Folia 范围的代码工作，先接通已明确的 HUD 样式/呼吸灯/重载命令，再推进白名单外目标模块；玩家资金与税款回执代码已有自动化证据，维持无权威凭证时的保守复核，不自行启动测试服。六阶段生产、自产物资采购运输与连续消费、当前完整 Folia 组合和图形资源包均保留待验。保留现有改动与历史证据，按具体构建哈希记录新结果，不把未完成项标成通过。
