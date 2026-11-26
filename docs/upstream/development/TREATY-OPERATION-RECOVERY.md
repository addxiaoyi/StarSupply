# 空条款与纯政治和约原编号恢复

日期：2026-11-27。本页接续[普通双边联盟检查点](ALLIANCE-OPERATION-RECOVERY.md)，记录空条款和已知纯政治和约的源码开发进度。**全部阶段尚未完成，本检查点不是第二十一份发布补丁。** JAR、安装序列和 review ZIP 仍为第二十份，不包含本页新增实现。未启动服务器或修改世界、玩家存档，实服验收继续待验。

## 累计补丁和证据

累计开发补丁：[treaty-operation-recovery-20261127.patch](treaty-operation-recovery-20261127.patch)。只应用于 Core 第二十份固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，包含此前开发成果并替代全部旧开发补丁；不要叠加普通联盟、原生输入或战争镜像旧开发补丁，也不要加入当前二十份成品安装序列。

机器证据：[workspace-treaty-operation-recovery-development-20261127.json](../../verification/workspace-treaty-operation-recovery-development-20261127.json)。证据包含最终回归批次、按 class/case 去重的数量、改动源空目录编译及最终 classpath 逐字节比对，以及累计补丁正向应用、反向检查和全量 src/API/pom 的 LF 规范化哈希核对。失败开发日志保留，不累计为通过数量。

最终四批共 **1,939 项、248 套件通过**，零失败、错误或跳过；其中 GUI/权限/启停 711 项、外交 575 项、War/和约/解散 640 项、菜单接线 13 项。27 个生产源、16 个测试源分别从空目录生成 88/35 个 class，与最终测试 classpath 逐字节一致。累计补丁含 227 个文件差异、2,179,414 bytes，SHA256 为 `06593890f50734f2560656ec5241a98f949a534304efcf2456d67ed808bb4601`；独立重放 3,745 个 src、API 基线与 pom 一致。

验证使用 JDK 21、Maven 3.9.16、单处理器、SerialGC 和 160 MiB heap，编译与测试串行运行。此前 compiler-excluded 的旧 `PeaceTreatyTest` 已迁移为当前模型，移除该项 compiler 排除，并通过独立 javac 纳入本轮定向测试；未据此声明现有 Maven 配置的完整干净构建已通过。

## 支持的请求与冻结范围

新提议只接受空条款，或完全由以下已知 `PoliticalTerm` 构成的条款清单：

| requirement | 胜败国家对目标关系 |
| --- | --- |
| `vassal` | VASSAL |
| `regime_change` | HOSTILE |
| `embargo` | HOSTILE |
| `no_war` | CEASE_FIRE |
| `neutrality` | NEUTRAL |

这些名称沿用现有关系模型；本检查点不新增附庸贡赋、贸易禁运执行、政府变更或期限到期玩法。每条政治限制保留原条款索引、稳定 restriction UUID、描述和原时间。相同目标的多个条款可以保留；同一国家对要求不同目标时，接收端永久拒绝该原号，不挑选其中一个生效。

外交计划冻结原战争全部参与国的两两国家对，包括同侧盟友；这些国家对先以 NEUTRAL 为默认目标，再应用政治条款。`neutrality` 还纳入败国当时已有外交关系涉及的国家对。完整范围、每对原关系和 generation、目标关系、原始条款及限制清单共同形成固定 planHash。恢复不重新计算范围、生成新 UUID 或使用较新的时间替换原请求。容量、已存在的冲突战争、退役或其他 RESERVED 计划会拒绝或封存该原号。

新赔款、旧 UUID 领土割让、军队限制、资源贡纳及任意混合类型在提议/接受路径失败关闭，不继续调用旧逐条执行器。历史已完成赔款和支付回执保留原查询、付款恢复；存在旧执行进度或旁表证据却无本协议 batch 的记录保留为 NEEDS_REVIEW，不根据现余额、现领土或旧状态猜测是否可重放。

## 三个权威与阶段

源端 PeaceTreaty、原生 War 与外交接收端分别保存自己的耐久事实，不以一次进程内方法返回代替其他权威的提交。

1. 源端先将原 operation UUID、条约 UUID、精确战争身份、战争 generation/fingerprint、胜败国、原时间和完整条款保存为 PREPARED，再调用外交 reserve。
2. 外交在自己的权威中永久保存 RESERVED 冻结计划。源端核对完整原请求后保存 plan 与 RESERVED；响应丢失时按原 operation UUID 查询，不生成替代操作。
3. War 验证精确战争身份、原 generation/fingerprint、完整国家对范围、退役和待处理效果，并永久保存准入决定。ACCEPTED 在原 War 权威中保留范围围栏，阻止竞争战争或旧 War 对象越过较新版本。
4. 原生结束与原 end receipt 在同一 War 权威 CAS 提交。源端持久保存原 end 证据，按既有镜像协议同步已结束战争，再要求外交 apply。
5. 外交通过窄接口核对完整原生 end receipt、精确战争释放镜像和原计划 generation；关系、限制证据、永久终态及派生事件 outbox 同次提交。任何前像变化都不能被旧计划覆盖。
6. 源端只确认匹配原请求/计划的终态。APPLIED 时保存本地政治限制投影及 ACCEPTED；其他终态保留审查原因。War 只在查到外交原号终态并核对 planHash 后永久 release，撤去范围围栏。最后源端保存 RELEASED。

状态为 `PREPARED → RESERVED → WAR_PREPARED → WAR_ENDED → ACKED → RELEASED`，拒绝/取消可直接带终态证据进入 ACKED。单调阶段不能替代证据校验；已有原请求、plan、end、终局回执和已写入的取消意图均不可被迟到快照改写或删除。缓存 War 对象携原 generation，不能通过一次新权威查询为旧对象借用最新 generation。

原生 durable end 事件带原 operation UUID；旧领土监听器据此跳过隐式所有权转移，保留通知和占领记录清理。旧无 operation UUID 的事件构造保留既有行为。这是阻止纯政治和约触发未纳入计划的领土副作用，不代表旧领土割让已经获得耐久协议。

## 持久取消与先提交者

管理员取消先在源 STATE 中保存不可撤回的 `cancelRequested`，然后才查询或调用外部权威。该保存失败时不调用原生取消；保存成功后，即使取消请求在提交前失败，自动恢复仍继续原号取消，不把它重新解释为接受。

War 的永久 cancellation seal 与 end receipt 共享同一权威 CAS，二者互斥。seal 完整绑定原 Request，可在尚无准入时先封存；迟到 prepare/end 不能绕过 seal。若已有 ACCEPTED 准入，其范围围栏继续保留到外交原号终态确认并 release，不能以“已请求取消”直接撤去围栏。

外交非终态取消要求已有 RESERVED 计划，并通过窄接口查到匹配该计划全部字段的原生 cancellation receipt；缺证据、不同 payload、存在任意原 end receipt 或来源服务不可用时拒绝提交。终态查询校验完整原请求后直接返回原决定。外交 apply 过程中事件 veto 导致的内部 CANCELLED 仍可在 War 已结束后保存，不能冒充原生取消 seal。

若原 end 已先提交，取消保留原 end 事实并沿原操作完成 apply/ACK/release，不反向恢复战争。如果 end 在取消负查询与 seal CAS 之间获胜，本次取消可以返回错误，但源取消意图仍在，后续恢复根据永久 end receipt 完成原操作。超时和异常均不等于取消成功或尚未执行。

## 管理与只读可见性

管理命令使用 `starcore.war.admin` 权限，允许控制台执行：

~~~text
/war treatyrecovery list [页码]
/war treatyrecovery show <完整条约UUID>
/war treatyrecovery retry <完整条约UUID>
/war treatyrecovery cancel <完整条约UUID>
~~~

`list` 每页十项，列出待恢复或需审查记录；`show` 可以查询保留终态。retry/cancel 使用完整条约 UUID 查回其既有 operation UUID，不创建新执行。旧无 batch 的 NEEDS_REVIEW 不提供自动重放。

命令 info、恢复详情及 WarMenu 展示条约/操作 UUID、phase、planHash、reviewReason 和 recoveryPending。只读展示不调用业务恢复。NEEDS_REVIEW 或存在 reviewReason 时，菜单不提供“重试接受”或拒绝；已打开的旧按钮回调也重新核验。元数据读取失败时关闭决策入口，不把无法确认当作没有执行记录。

启动和定时恢复每批至多 25 项，定时器首次及后续间隔均为 200 ticks，按 UUID 游标轮换；服务单批允许 1–100。单项失败保留原号并记录待恢复，不能将 ticks 表述为实服实时保证。停用、阻塞加载后返回、扩展回调和镜像回调均复验生命周期；旧回调不能在新生命周期写入兼容投影。

## 持久化与验证边界

和约 schema3 保留纳秒时间、完整冻结计划和原回执，继续读取 v1/v2 的既有精度。取消标记只接受真正 JSON boolean，缺字段按历史 false 读取，显式 null、字符串或其他类型拒绝；后继状态不能把 true 改回 false。War 的准入/end/cancel/release 与外交操作均使用现有文件或 SQL 权威，保留永久 payload 及终态，不能靠删除回执恢复业务。

文件和 SQLite 测试覆盖提交边界中断、提交丢响应、取消请求提交前失败、原生 end/cancel 独立 writer 竞争、重启、同值较新写入、后续战争、损坏/旧快照、生命周期、管理命令和 GUI。该测试范围不等于完整进程强杀、最新 MySQL、多服务器全流程或 Paper/Folia 客户端验收。派生 outbox 的 ACK 继续仅覆盖 EventModule 日志，第三方 bus 订阅者可能重复收到通知。

## 下一阶段

领土和军队仍需独立接收端的固定计划、永久回执、generation/删除墓碑及共同释放证明。foundation 的 chunk 领土与旧 UUID/cuboid 领土是两套权威，不能把旧割让 ID 自动解释为新 chunk。下一阶段须分别保存完整原请求 hash、接收端 localPlanHash 和全局 manifestHash；War release 要验证全部指定接收端终态，不能仅靠 producer 调用顺序或外交终态提前释放。

赔款/资源贡纳/贷款及其他付款安全取消、旧无回执证据核对、全模块权限/线程/启停、其他城市交互、海军真实运兵与港口、伤兵回补、学说实效及费用、预算/演习运维、归档容量和离线世界继续按[代码闭环清单](../../CODE-CLOSURE.md)推进。全仓干净 Core、配套新成品与 StarSupply 回归、最新 MySQL 和用户保留的实服验收仍未完成。
