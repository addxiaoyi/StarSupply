# Treaty Effect Manifest v2 开发记录

本轮为和平条约的原生 WAR 结束与外交效果之间增加显式 manifest 绑定、可恢复握手和完整终态证明。当前执行范围仍为纯政治条款，或没有条款的外交结算；只有一个 `diplomacy` v2 receiver。文档中的完整 manifest 与 proof vector 是协议约束，不代表已经支持多个 receiver、混合条款或军队效果。

最终测试数量、编译结果与补丁哈希见机器证据：

- [workspace-treaty-effect-manifest-development-20261127.json](../../verification/workspace-treaty-effect-manifest-development-20261127.json)
- [treaty-effect-manifest-20261127.patch](treaty-effect-manifest-20261127.patch)

## 最终验证

五个互不重叠的最终批次通过 2,098 项测试、255 个套件，失败、错误和跳过均为 0，包含未更新基线的 `PublicApiCompatibilityTest`。本轮 26 个生产源与 10 个测试源分别在空目录编译生成 86、29 个 class，最终测试 classpath 与新编译结果逐字节一致；相对上一检查点共有 36 个 Java 源改变。早期失败尝试与中间 smoke 检查保留原日志，不计入上述最终通过数量。

累计补丁含 244 个文件差异、2,535,515 字节，SHA-256 为 `d9d4872f6b7a9f369f32e636437a501a591ab0e9748d8f9383264454eb6d0d70`。在第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18` 独立重放，通过正向、反向和差异检查；全部 3,757 个 `src` 文件、API 基线和 `pom.xml` 在 LF 规范化后与验证源码一致。以上验证不包含全仓干净构建、新 JAR 或实服验收。

## 三层摘要与完整请求

协议将三个用途不同的摘要分开计算，避免把本地执行计划当作全局承诺，也避免将筛选后的条款重新编号后误认为原始请求。

| 名称 | 覆盖内容 | 用途 |
| --- | --- | --- |
| `requestDigest` | 完整、规范化的原始 envelope，包括原始条款顺序、索引、类型、payload、描述及结算身份 | 固定用户提交的完整请求，receiver 只能引用分配给自己的原始索引 |
| `localPlanHash` | receiver 请求、明确的原始索引分配及冻结的本地计划 | 固定该 receiver 的本地准备结果，不包含全局 manifest hash |
| `globalManifestHash` | 完整、规范化的 manifest，包括原始 envelope 和全部 entry | 固定全局效果承诺，作为原生 v2 settlement binding 的 `planHash` |

receiver 本地 API 中仍可见名为 `planHash` 的参数或访问器；在本地 `Plan` 上，它表示 `localPlanHash`。原生 `WarSettlementAdmissionService.Request.planHash` 在 v2 中表示 `globalManifestHash`。二者不能互换。

完整 envelope 保留 operation、treaty、精确 WAR 身份、WAR generation、WAR fingerprint、胜败方、准备时间和所有原始条款。精确 WAR 身份包括双方及各自盟友；不能仅以 WAR ID 或主战双方替代。manifest entry 保存 receiver 身份和版本、原始条款索引分配、`localPlanHash` 及精确 pair scopes。原始索引不能过滤后重新从零编号。

新执行在原生 prepare/end 之前校验支持范围。未知 receiver、receiver 版本、未支持的条款类型或不受支持的 manifest 结构会失败，不能先结束 WAR 再补做兼容处理。全局 pair scopes 上限为 4,096，外交请求上限为 32 个原始条款；当前实现不会把这些上限解释为多 receiver 已经可用。

## 持久化握手与恢复

v2 的正常顺序为：

1. source 保存完整请求意图，receiver `reserveTreaty` 冻结本地 `Plan`。
2. source 持久化冻结计划并构造预期 binding；receiver `bindManifest` 永久绑定同一份完整原生请求和 manifest，source 验证返回值后保存 binding 与 `MANIFEST_BOUND`。
3. 原生 prepare 验证所有 entry 的精确 `BOUND` proof 后保存 admission；原生 end 再次验证 `BOUND` proof 后提交精确 WAR 结束和永久 `EndReceipt`。
4. receiver 根据精确原生结果执行 apply 或 cancel，保存不可变终态及其原因依据。
5. source 保存 receiver 终态；原生 release 保存完整 proof vector；source 最后进入 `RELEASED`。

绑定具有永久性。重复绑定必须与已有 binding 完全相同，不能在重试时生成新的 operation、替换 manifest 或重算已经冻结的本地计划。source 的阶段包括 `PREPARED`、`RESERVED`、`MANIFEST_BOUND`、`WAR_PREPARED`、`WAR_ENDED`、`ACKED`、`RELEASED`。阶段进度与持久化 binding 共同决定恢复动作；存在 binding 不等于 receiver 绑定调用已经得到确认，恢复仍须查询或重放同一个幂等绑定。

bind 前后发生进程退出、调用返回不确定或 source 保存失败时，保留原始意图和冻结身份。恢复以同一个 operation 查询永久记录并继续握手，不通过重新 reserve 一个不同请求来绕过冲突。新 prepare/end 要求所有精确存储的 proof 都仍为 `BOUND`；已经终态的 proof 不能授权一次新的原生 mutation。

receiver 查询在原生 mutation gate 外执行，以免跨模块回调形成锁内重入。回调后重新检查服务生命周期与 receiver 实例身份，再进入新的原生 CAS。永久 admission、end、cancellation、release 的精确重放先于新 receiver 查询和新容量门禁；已有终态不能因为 receiver 暂时离线而被改写。

## 完整 proof 与终态依据

`WarSettlementEffectProofReader.EffectProof` 包含完整原生 binding、对应的完整 entry、phase、精确终态时间和终态 evidence。原生 v2 `ReleaseReceipt` 保存按 canonical manifest entry 顺序排列的完整 proof vector；不能仅保存摘要、receiver 名称或终态布尔值。

| phase | terminal basis | 必需的外部依据 |
| --- | --- | --- |
| `BOUND` | 无 | 没有终态时间和终态 evidence |
| `APPLIED` | `END_APPLIED` | 与原生永久记录完全相同的 `EndReceipt` |
| `CANCELLED` | `END_REJECTED` | 与原生永久记录完全相同的 `EndReceipt` |
| `CANCELLED` | `CANCEL_SEAL` | 与原生永久记录完全相同的 `CancellationReceipt` |
| `SUPERSEDED` | `LOCAL_SUPERSEDED` | 不携带原生 end 或 cancellation receipt |

phase、basis 与 receipt 的组合由构造与持久化校验共同约束。release 中每个 proof 必须绑定原始 admission，entry、operation 和全局 hash 必须完全一致；当前支持的完整向量必须是与 release status 对应的统一终态。原生会再以自己的永久 end/seal 记录核对外部 evidence，不能借用另一份 binding 或仅构造一个字段相似的回执。

proof codec 使用显式版本、确定性二进制及 Base64。时间保留纳秒；文本使用有界 UTF-8 长度，不使用 `writeUTF`。解码拒绝未知版本、超界或截断计数、非法 UTF-8、非法纳秒、尾随数据和非 canonical 编码。单个 proof 的解码字节上限为 8 MiB；在 Base64 解码分配前检查精确预测长度。`encodedLength` 通过同一 writer 和有界计数输出流计算长度，容量估算不需要保留一份完整 proof 字节数组。

## schema 4 与旧 v1

source aggregate 使用 schema 4，v2 字段必须显式出现且彼此一致。旧 schema 1–3 通过明确的 legacy 路径补齐 v1 语义，不根据缺失字段猜测一个 v2 请求；旧 schema 中出现新版本字段会被拒绝。显式 v2 缺少 envelope、manifest、分配或 binding 等必需数据时不能降级为 v1。

既有 v1 operation 不升级为 v2，不重新计算 v1 hash，也不重写其永久 receipt。原生 v1 JSON 保持旧字段和编码路径；v1 release 仍使用旧回执结构。新 v2 的完整 proof vector 不能由旧的空向量 release 代替，旧回执也不能借用 v2 admission。

## 存储能力与实际容量

新 v2 在创建执行意图和远端 reserve 前检查原生及 receiver 的 proof 持久化能力。已绑定请求在恢复过程中发起新的原生 prepare/end 时仍须重新检查两端能力，不能仅凭一份可读的旧 BOUND proof 跳过。生产数据库包装器将能力检查委托给构造时选定的实际后端；运行标记变化不会重新选择后端，SQL 不可用时也不会回退到文件。当前允许文件模式，或经实际连接元数据确认的 SQLite。配置名称或 JDBC URL 本身不作为容量证明；已知非 SQLite SQL 后端拒绝新 v2。SQLite gate 固定已验证的 DataSource 身份，并检查后续连接的实际数据库产品，防止连接路由或运行时后端变化绕过检查。这里没有为未验证的 MySQL `TEXT` 容量作支持承诺。

原生文件 authority 的完整序列化快照上限为 64 MiB，永久回执会持续累计。仅限制单个 manifest 或 proof 不能保证结束 WAR 后还有空间保存终态，因此每次本地 authority 写入都会计算当前真实 `Properties.store` 字节量，并为全部尚未完成的 v2 operation 保留未来空间。计数使用不保留完整快照的有界输出流、`long` 和精确算术；容量不足时拒绝当前写入。

预留覆盖尚未写入的 admission、精确 end 或 cancellation seal、完整 release proof vector，以及 source 的后续阶段快照。admission 被拒绝后仍可能需要永久 cancellation seal，该路径也保留容量。原生 release 完成后，source quota 继续存在，直到 source 自身保存 `RELEASED`；不能提前释放空间，让另一个 writer 挤占最后一次 source 保存。

source 固定 slot 按冻结的请求、计划和 binding 构成的 anchor 序列化大小计算：`anchor × 8 + 128 KiB + 原始条款数 × 4 KiB`。当前 source 占用从该 slot 中扣除，其余部分继续作为未来空间保留；这不是每次写入都重新增加一个无限增长的额度。固定余量包括允许的 review 文本在 JSON 和 Properties 中的最坏转义开销。完整快照仍必须同时满足总量上限，多个 operation 的预留会相加；无关写入不能消耗已经预留的终态空间。

为使 slot 的边界可执行，所有新 v2 source 保存共同校验以下协议约束，包括 binding 尚未完成的阶段；SQL 路径也执行这些协议检查：

- `reviewReason` 最多 8,192 个 UTF-8 字节。当前系统终态原因是固定的 `TREATY_BATCH_` 加状态值。
- 每条款 compensation 可以为空；非空时只能是与冻结计划精确一致的 `batchOperationId` 和 `restrictionId` 两个 UUID 映射。
- sidecar 仅允许冻结政治计划的精确子集，身份、受约束国家、requirement、description 和 imposedAt 必须相同；`liftedAt` 可以正常更新。
- 不允许为该纯政治 v2 source 附加 reparations、军队限制、资源贡赋或 payment sidecar。

这些是新 v2 协议约束，不是迁移或清理旧 v1 字段的规则。容量检查保留实际序列化与后续 generation 增长的余量，不承诺无限历史容量；达到边界时需要运维处理，而不是丢弃永久证据或切换另一个未经验证的后端。

数值容量也必须在不可逆结束之前保留。原生 authority 对全部 v2 source 从 `PREPARED` 起预留剩余 source 阶段提交、尚可写入的取消意图，以及尚缺的 native admission/end-or-seal/release 提交；同一 operation 不重复计数。没有 source 的独立原生 v2 admission 也保留终态与 release 余量。文件和 SQLite 每次校验都执行该规则，候选快照已增加的 revision 也纳入检查，无关 writer 不能消耗最后的恢复提交次数。旧 v1 不追加这套预留约束。

receiver 对每个冻结 pair 预检后续 generation，主战国家对为镜像释放和效果提交保留两次增长，其他 pair 保留一次。全部待镜像 operation 的全局 mirror revision 需求相加，SQLite authority revision 同时保留各 operation 的后续镜像/终态提交。先有一份合法计划，不代表可以在数值即将溢出时继续 bind。

文件重载与新提交分别校验：重载可以观察到另一个 writer 已经从 RESERVED 跨过 bind 到终态，仍须保持既有请求、计划、binding 和终态证据不可逆；真实 file/SQLite CAS 则继续要求绑定以已存在的 RESERVED 为前像。不能把“重启没有看到中间快照”解释为允许一次新写直接伪造已绑定终态。

文件迁移到 SQLite 时只允许从实际文件 authority 的加载路径导入已绑定快照，保留完整 binding 后再以 SQL 事务提交；普通 supplier 或手工 Properties 不能借迁移接口导入已绑定操作。文件迁移、SQLite 重启及通用导入拒绝分别有行为回归。

## generation、取消与竞争结果

原生 admission 固定精确 WAR generation、fingerprint 和完整参与方身份；pair scopes 形成结算 fence。新的 end 必须基于该 admission 和当前精确 WAR 行提交，通过同一 authority CAS 原子地记录 WAR 结束与 `EndReceipt`。end receipt 固定 before/after generation，成功结束只推进一次；其他字段不能被一个“结束战争”的更新顺便替换。数值溢出不会通过截断或回绕继续执行。

取消必须区分原生尚未结束与原生已经结束两种结果：

- `CANCEL_SEAL` 是原生永久取消决定，阻止之后为同一请求提交 end。seal 与 end 不能同时成为永久赢家。
- receiver 的事件 veto 可以发生在原生 end 已经永久提交之后。此时 receiver 保存 `CANCELLED / END_REJECTED`，以精确 end receipt 为依据；允许释放 fence，但 WAR 仍然结束。不能伪造 cancellation seal，也不能宣称已恢复 WAR。
- `LOCAL_SUPERSEDED` 是 receiver 对本地计划失效的永久决定，不携带外部 end/seal。它可能与稍后的原生结束竞争；即便原生 end 最后获胜，也不能把已经保存的 superseded proof 改写为另一种终态依据。

处理 supersession 的 release 时，原生检查自己的永久 winner facts。若 end 尚未存在，永久 release 阻止之后的迟到 end；若 end 已存在，则保留该精确事实并释放原有 fence。这里的释放是结束恢复责任和解除结算 fence，不等于撤销已经提交的 WAR 结束或重新执行 receiver 效果。

## 本轮范围

本轮闭合的是政治结算的 manifest 绑定、恢复、proof 持久化、原生 release 和容量边界。未实现的混合条款、多 receiver 编排、军队效果、其他 receiver 类型及未经验证的 SQL 容量仍需各自的协议设计、持久化证明和测试，不能从当前 manifest 的通用结构推断为已经支持。
