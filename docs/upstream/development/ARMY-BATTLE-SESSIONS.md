# Army 战斗会话开发检查点

日期：2026-11-27。A4 在 A3 的未激活 owner 之上实现同一 authority 内的战斗会话、完整参与者围栏和永久终态证明，尚未接入 ArmyService、Commander 或玩家入口。

累计补丁：[army-battle-sessions-20261127.patch](army-battle-sessions-20261127.patch)。
只应用于固定 Core 基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代此前全部开发补丁，不能叠加。
JAR、安装序列与 review ZIP 仍为第二十份成品；本开发补丁尚不进入安装链。
机器证据：[workspace-army-battle-sessions-development-20261127.json](../../verification/workspace-army-battle-sessions-development-20261127.json)。

## 已实现的协议

- `ArmyBattleRequest` 冻结原 session UUID、操作者、双方完整原快照和接受时间。双方必须为不同国家的不同存活军队，时间不得倒退，generation 预留开始与终态两次推进。
- 开始在同一次 CAS 内保存原请求，并把双方状态改为 ATTACKING / DEFENDING、generation 各推进一次。原请求和会话编号永久保留；相同编号必须完整匹配，终态后也返回原证明。
- ACTIVE 使用保守的完整快照冻结策略：双方补给、位置、数值、状态及 token 均不得被普通维护、后勤、付费补给、其他战斗或退役修改。pending 付费操作与 ACTIVE 战斗不能争用同一军队。这是当前未激活协议的明确边界，正式接线前仍须核对玩法要求。
- 完成将双方精确效果、generation 再各推进一次及终态证明原子保存。身份、位置、补给、余量、创建时间固定；兵力和生命不得增长，伤害必须有限且非负，士气允许变化。战斗结果按实际兵力差与最终存活状态派生。
- 取消只把双方推进到 STATIONARY 并保留冻结快照的全部数值，伤害为零，不恢复原来的 MARCHING 状态，不写回旧生命或补给，也不伪造完成结果。
- 阵亡军队从 live 图移除，同次写入 `battle-defeated` 墓碑，operationId 为原 session UUID。冷启动绝对校验要求该完整墓碑永久相等，不能替换原因、编号、时间或以更高 generation 复活。
- 存活终态的当前 live 图若仍在同 generation，必须与完整效果快照相等；后续退役必须使用更高 generation。A3 付费效果证明同步补上相同检查，防止仅身份相等的同代数值篡改。
- schema3 增加 battle 行，保留 schema1 / schema2 和既有行布局；旧 schema 不能夹带新会话，版本不能回退。pending 行为完整终态预留固定二进制槽，严格拒绝非 canonical padding、非法 phase 和尾随数据。
- 全部 writer 联合预留 pending 付费与战斗所需的未来 revision，避免各自检查通过后合计溢出。存储提交前还预留全部付费成功、全部战斗取消及最大 revision 位数的完整 envelope 字节。
- 每种 ArmyType、最短世界名和四种双方存活组合都验证实际编码大小不超过 all-live 取消投影。真实文件与 SQLite 在恰好未来容量下，覆盖混合付费、取消意图、重启、最大时间及双方阵亡组合；容量不足在发布任何开始效果之前拒绝。
- 旧 owner、真实提交后丢失确认和恢复沿用 A2 失败关闭规则。恢复只确认原持久证明，异常不推断未提交，不自动换号重做，也不回滚已接受的终态。

## 最终验证

采用单 JVM、串行批次，160 MiB 编译 / 测试堆、SerialGC、单处理器；没有启动 Minecraft 服务或修改世界、玩家存档。

| 批次 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| Army authority、owner、adapter、付费与 A4 战斗协议 | 450 | 0 / 0 / 0 |
| 既有 Army 行为 | 462 | 0 / 0 / 0 |
| 持久化、容量与公开 API 兼容 | 61 | 0 / 0 / 0 |
| 真实 Treasury debit authority | 25 | 0 / 0 / 0 |
| **去重总计** | **998** | **0 / 0 / 0** |

共 90 个套件，按类与 case 名去重，日志和 XML 总数一致。9 个改动生产源空目录编译为 19 个 class，4 个测试源编译为 4 个 class；与最终测试 classpath 字节一致。Spotless apply / check 通过，未更新 API 基线。26 项战斗 owner file / SQLite 调用检查原子开始和终态、完整冻结、永久重放、取消、stale owner 及真实提交后丢失确认。模型另有 66 项约束用例，存储状态 / 容量 22 项；这些均已包含在上表，不重复累计。

首轮 446 项有一项测试夹具错误：付费创建的创建 / 更新时间早于接受时间，被模型正确拒绝。已修正夹具并补四项真实存储容量用例，最终 450 项重新编译重跑。失败日志保留于机器证据，首轮不计入最终通过数。

284 文件累计补丁为 3,033,481 字节，SHA-256：`258903d20c9ef56668320edc611ac09343e2926bc4420a5af0a0cef25db4d8f0`。固定基点正向检查、应用、反向检查及 diff 检查通过；独立重放后全部 3,796 个 src 文件、API 基线与 pom 经 LF 标准化逐文件一致。A3 之后变更 13 个 Java 源。

## 激活前仍须完成

当前仅完成协议边界，**全部阶段尚未完成**。

- 完成 Commander 技能效果 / 冷却证明及授权策略，核对 ACTIVE 完整冻结与既有战斗、技能和维护玩法的兼容性，再统一迁移 ArmyService 与所有直接 writer、快照读取和旧 rollback / restore。
- 增加 Army 持久国家 closing / retired 准入，并接入 Nation 解散。已接受或已扣款请求必须获得明确终态，不能因国家消失而丢弃。
- 当前付费请求仍明确绑定 `TREASURY_DIRECT`。真实 Budget pool 预留 / 消费尚未接通，不得静默更换已接受请求的资金来源。
- 实现真实总兵数限制与军事条约 receiver，再分别接两套领土 authority、混合效果和剩余付款恢复。Siege 还涉及外部领土转移，不能用本战斗协议宣称已经闭环。
- 新价格、玩家权限、线程 / 启停、完整干净构建、新成品及配套回归尚未由本检查点验证。SQL 多进程、最新 MySQL 和按用户要求保留的 Paper / Folia 实服验收继续待完成。
