# 普通双边联盟原编号恢复与投影围栏

日期：2026-11-27。此为源码开发检查点，**全部阶段尚未完成，本检查点不是第二十一份发布补丁。** 普通双边结盟和解约新增源端持久意图、外交接收端原编号永久回执，以及防止旧结果复活关系的所有权与 generation 围栏。发布 JAR、安装序列和 review ZIP 仍为第二十份补丁，不包含本页新增实现。

本页接续[原生输入与外交派生事件检查点](NATIVE-INPUT-OUTBOX.md)，保留其历史实现和验证记录。本文的“普通双边联盟”是 `module.diplomacy.alliance`，不等于另一套 `module.alliance` 多国联盟，也不代表和约、全部外交或其他阶段完成。未启动服务器或修改世界、玩家存档；Paper/Folia、客户端交互与完整生存链实服按用户要求保留待验。

## 累计补丁与证据

累计开发补丁：[alliance-operation-recovery-20261127.patch](alliance-operation-recovery-20261127.patch)。只应用于 Core 第二十份固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，包含此前开发成果并替代所有旧开发补丁；不要叠加原生输入、战争镜像等旧开发补丁，也不要加入当前二十份成品安装序列。

机器证据：[workspace-alliance-operation-recovery-development-20261127.json](../../verification/workspace-alliance-operation-recovery-development-20261127.json)。最终三批共 **1,759 项测试、239 套件通过，0 失败、错误、跳过**：GUI／权限／启停 711 项、外交／联盟／事件及 API 548 项、战争／解散／和约及赔款 500 项。按 class 与 case 名去重，不把历史检查点或修复过程中的重跑累计为本轮结果。API 基线检查通过，未使用更新标记。

第三批请求的 38 个顶层测试类实际执行 37 套件：现有 `pom.xml` 的 compiler testExclude 已排除旧 `war/PeaceTreatyTest.java`，该类仍引用已删除的 `TreatyType` 等 API，未生成测试 class。本轮追加的独立 javac 核查确认其编译不兼容；该失败日志已保留，此类不计入通过数量。这是未执行的旧测试源码，不能用汇总中的“0 跳过”表示它已通过；完整干净构建及这项旧测试修复仍待完成。

13 个改动生产源从空目录编译为 29 个 class，17 个改动测试源编译为 32 个 class，并核对与最终测试 classpath 逐字节一致；前两批的源码与 class 在最终夹具修复后仍一致。验证使用 JDK 21、Maven 3.9.16、单处理器、SerialGC 和 160 MiB heap，构建与测试串行执行。

累计补丁含 **203 个文件差异、1,751,765 字节**，SHA-256 为 `5c7b4416b4ec6a97172cf7352ab4bf2c1e6a9e86efe2378d409108299cbd83d2`。独立冻结目录完成正向检查、应用、反向检查和 diffcheck；重放后全部 **3,730 个 src 文件**、API 基线及 `pom.xml` 经 LF 规范化后与捕获快照一致。相对上一原生输入／outbox 检查点变更 30 个 Java 源；本证据仅对应这一冻结检查点，不自动覆盖之后的开发。

## 源意图与外交接收端

接受邀请、直接建立联盟和单方面解约均先保存 `PREPARED` 意图，再调用外交接收端。意图保留原操作 UUID、有序国家对、FORM/BREAK、实际操作方、原始时间、接收端原关系及 expected generation；同时冻结源端原邀请及时间、原联盟、拟建联盟和原／拟冷却。接受邀请保留原邀请方向，规范化国家对不改变接受方和事件方向。重试不重新生成 UUID、时间、国家名称或前置版本。

同一国家对只允许一个 `PREPARED` 意图。与其来源、操作方和原邀请相符的重复入口继续原操作，其他竞争操作被拒绝；不能通过重新点击或创建替代操作绕过待恢复状态。意图保存失败时不调用接收端；接收端返回异常或源端确认保存失败时，也不能据此判断外交效果尚未提交。

`DiplomacyAllianceOperationService` 按原 UUID 查永久回执，并校验完整请求一致性。已有同号回执先于当前关系、战争和冷却检查返回；同号不同 payload 被拒绝，重试不会把原拒绝决定变成一次新成功。新请求检查国家退役、外交 WAR／战争记录／战争镜像所有者及可用的实际战争权威，随后检查 expected relation、expected generation 和结盟冷却。FORM 不覆盖 ALLIED、HOSTILE 或 WAR；BREAK 只接受原 ALLIED。

合法请求在外交权威中同次保存关系效果、原时间、递增 generation、操作所有者、永久回执和派生事件 outbox。APPLIED 的 generation 必须是请求 expected generation 加一，FORM 的目标为 ALLIED，BREAK 的目标为 NEUTRAL。退役、战争、前置状态和冷却拒绝分别保存 `REJECTED_RETIRED`、`REJECTED_WAR`、`REJECTED_PRECONDITION`、`REJECTED_COOLDOWN`；关系变更事件取消时保存 `CANCELLED`。非 APPLIED 回执不改变关系。

源端只接受与原请求完全一致的接收端回执。确认后将永久意图置为 `ACKNOWLEDGED`；只有国家未退役、源业务行仍与冻结前像一致，且最新外交状态仍由原 UUID 持有、generation 与回执相同、关系等于原结果时，才发布对应本地投影、邀请消耗和冷却。接收端已经 APPLIED，但随后发生 WAR、管理员修改或其他较新关系变更时，旧操作仍保留原 APPLIED 回执，却不能再次把本地联盟投影变为有效。

外交派生 outbox 与 EventModule inbox 继续使用前一检查点的耐久协议。ACK 只覆盖 EventModule 事件日志；第三方 bus 订阅者仍可能收到重复通知，不能将同号回执表述为所有外部副作用恰好执行一次。

## 可见性与所有权失效

联盟查询、列表、统计和普通业务判断不再仅以缓存或旧联盟行为准。可见联盟必须同时满足：最新源快照包含同一业务行、投影 owner 有效、owner 对应本地已 ACK 的 APPLIED FORM 意图、拟建联盟与业务行一致，以及最新外交接收端仍由同一操作 UUID 和 generation 持有 ALLIED。权威忙、不可读、缺接收端、缺存储或服务停用时按不可见处理，不降级为旧缓存或普通 `setRelation` 写入。

独立外交修改、战争镜像、清理与退役会释放或推进接收端所有权。旧 APPLIED 回执属于历史事实，不是重获当前所有权的凭证。源投影 owner 另有永久 `active=false` 标记；同一操作及 generation 已失效后，存储拒绝任何迟到快照将其改回有效。不同操作替换 owner 必须具有更高 generation。

`clearNationRelations` 在任何业务删除前检查该国相关的 `PREPARED` 意图，发现后拒绝清理并给出原操作 UUID。管理员应先查询原号并完成 retry 或 cancel，不能直接删除意图或猜测接收端是否执行。没有待恢复意图时，联盟、邀请、冷却删除与相关 owner 失效同次保存；永久意图和历史确认不随业务清理删除。

国家退役先规范化 schema3 并持久失效相关 owner，再发布原编号永久退役回执。两次提交之间中断会保留已失效投影，不能把它当作尚可复活的联盟。退役计数只计算实际清除的业务行，不把新增协议记录计为业务删除。退役后的原待恢复操作通过同号取消查询外交决定；若外交此前已 APPLIED，仍保留原结果，但不重新发布退役国家的投影。

## 原编号恢复与安全取消

管理命令使用 `starcore.alliance.admin` 权限，允许控制台执行：

~~~text
/alliance recovery list [页码]
/alliance recovery show <完整UUID>
/alliance recovery retry <完整UUID>
/alliance recovery cancel <完整UUID>
~~~

`list` 仅列 `PREPARED`，每页十项；`show` 可查询已保留的终态。输出包含原 UUID、动作、国家对、原时间、状态与结果／最近失败原因。UUID 必须完整，未知原号不会被自动替换为新操作。`/alliance cancel <国家名称>` 仍是取消邀请，与这里的耐久操作取消命令不同。

| 原状态／接收端决定 | cancel 的结果 |
| --- | --- |
| PREPARED，接收端尚无原号决定 | 源端先持久标记 cancellationRequested，再要求接收端按原请求永久封存 CANCELLED；迟到 apply 或 retry 只能取得同一取消回执 |
| PREPARED，接收端已 APPLIED，源 ACK 丢失或未保存 | 返回原 APPLIED，继续确认原操作；不反向解约、不恢复旧联盟，也不生成补偿操作。是否仍可发布本地投影继续受最新 owner 和 generation 限制 |
| PREPARED，接收端已有拒绝或 CANCELLED 回执 | 保留该原终态，不改写为另一种决定 |
| 已 ACKNOWLEDGED | 返回保存的原终态，不重新调用业务效果 |
| 接收端或存储暂不可确认 | 保留 PREPARED 和已保存的取消请求，以原 UUID 继续查询／恢复；不能将超时当作取消成功 |

取消请求只能从未请求变为已请求，不能被迟到快照撤销。retry 也会尊重该标记，后续仍走同号取消。永久回执、终态意图和失效 owner 不提供自动归档删除；人工运维不得靠删除这些记录“重试”。

初始化加载权威后尝试恢复至多 25 项；模块定时器首次延迟 200 ticks，之后每 200 ticks 再恢复至多 25 项，按原始时间和 UUID 排序。单项失败保留待恢复并继续受控批次；不能把 tick 周期换算成实服实时恢复承诺。服务 API 接受的单批上限为 1–100，模块固定使用 25。停用关闭准入，不将回调局部或未提交 map 整体刷回存储；启用失败与旧任务不能绕过停用状态继续提交。

## schema3 与历史行隔离

普通联盟权威写入 `format.version=3`。v1／v2 的联盟成立、邀请和冷却时间严格按 epoch 毫秒读取，v3 改为 ISO `Instant`，保留纳秒精度；v2／v3 的名称采用编码值。意图内的原请求时间、拟建联盟时间和冻结前像也保留精确 `Instant`，避免保存再加载后因毫秒截断产生 payload 冲突。旧毫秒数据只能保留其已有精度，不会推测缺失纳秒。

快照同时保存永久 intent、接收回执摘要、投影操作 UUID／generation／有效标记和既有退役回执。普通保存合并已有协议历史，拒绝修改原 payload、改写终态、撤回取消标记、倒退 owner generation 或复活已失效 owner。文件与 SQL 都沿用已固定的权威后端与版本检查；存储或外交协议不支持时失败关闭，不回退到无回执结盟。

历史联盟行没有接收端 owner 或同号 ACK 证据时保留原数据用于人工核对，但从有效联盟查询和统计中隔离。汇总会显示未具接收端所有权的历史行数量；直接形成新联盟遇到已有历史行会拒绝并提示人工核对。系统不凭现存 ALLIED、国家名称或相似时间伪造原 UUID、generation 或回执，也不会自动把旧行升级成已确认投影。即使迟到保存再次写回旧业务行，缺少有效所有权仍不能恢复其可见性。

## API、成品与验证边界

源码新增 `DiplomacyAllianceOperationService` 的 apply／cancel／pair state／receipt 查询，以及 `AllianceRecoveryService` 的 list／show／retry／cancel／有界恢复。未实现耐久协议的扩展默认抛 `UnsupportedOperationException`，不能被当作已确认接收端。已有 `AllianceService` 入口继续存在，但其成功判定和历史联盟可见性采用上述更严格边界；异常或 false 不等于原操作绝未生效。API 基线检查通过仅证明该套件核验的兼容边界，不能仅凭保留旧方法名声明全部行为兼容。

本页不证明全仓干净 Core 构建、新 Core／StarSupply 成品或新成品配套回归通过。累计补丁及证据是源码交接材料，**JAR、安装序列和 review ZIP 仍第二十份**；不增加顺序安装补丁、不扩大 profile，也不打开生产 Folia 支持声明。完整进程中断、SQL 多进程、最新 MySQL 和 Paper/Folia 实服仍需各自证据，本轮不启动服务器验收。

## 后续实现

下一 P1 是和约政治关系、领土和军队接收端的原编号回执、固定计划与较新权威状态围栏；普通联盟协议不能替代这些跨权威效果。完整解散进程强杀恢复、和约及其他付款安全取消、旧无回执记录核对、圈地派生通知、贷款和其余资金恢复仍需继续。

全模块权限／线程／启停、其他城市交互、海军真实运兵与港口、伤兵生成与康复回补、学说实效及耐久费用、预算／演习运维、失效奖励、归档容量和离线世界继续按[代码闭环清单](../../CODE-CLOSURE.md)推进。原生窗口客户端体验、完整插件组合和六类生存链实服待验；**全部阶段尚未完成**。
