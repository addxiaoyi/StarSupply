# Army 付费操作恢复开发检查点

日期：2026-11-27。A3 在 A2 的未激活 owner 之上实现付费创建与补给的持久操作协议，尚未接入 ArmyService、Commander 或玩家入口。

累计补丁：[army-paid-recovery-20261127.patch](army-paid-recovery-20261127.patch)。
只应用于固定 Core 基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代此前全部开发补丁，不能叠加。
JAR、安装序列与 review ZIP 仍为第二十份成品；本开发补丁尚不进入安装链。
机器证据：[workspace-army-paid-recovery-development-20261127.json](../../verification/workspace-army-paid-recovery-development-20261127.json)。

## 已实现的协议

- `ArmyPaidRequest` 冻结原操作 UUID、操作者、创建/补给类型、完整前后快照、精确报价、接受时间和国家军队数量上限。创建使用预选的 generation-1 身份；补给只能提高供给并推进原 incarnation 的 generation，其他业务字段保持一致。
- canonical SHA-256 使用带长度的 UTF-8/二进制字段，包含所有请求事实及 `TREASURY_DIRECT` 资金策略；保留有限浮点正负零和时间纳秒，拒绝孤立代理字符。传给现有 `NationDebitService` 的 purpose 为 `army-paid:v1:<digest>`，始终使用原 UUID 和原金额。
- authority schema2 与旧 schema1 明确区分。旧数据不自动产生付费历史；首次新意图升级 schema2，后续不可降级。schema1 仍可按原布局读取，不能携带付费行。
- prepare 在同一次 authority CAS 中保存意图和预留，先于任何外部扣款。每军队最多一个 pending 操作；pending 补给冻结完整原快照，pending 创建占用身份和军队数量额度。失败/取消的创建身份亦永久禁用，不能换请求重用。
- 所有提交均为 pending 操作保留剩余 authority revision：尚未取消保留取消意图及终态两次，已有取消意图保留一次终态。无关 writer 也不能耗尽这些余量。
- prepare 及后续写入按全部 pending 成功后的完整图预留终态字节与最大 revision 位数。付费行的状态及决定时间占固定 13 字节；混合创建/补给、全部状态组合和最大 native 毫秒时间均有容量回归。超限在外部付款前拒绝发布。
- `ArmyPaidCoordinator` 先查询原 UUID 的耐久回执，缺失时才按已保存的取消决定调用原请求的 apply/cancel；完整比较 native receipt request，不接受只匹配金额或 UUID 的凭据。
- cancel 先持久化不可回退的取消意图。receiver 若已经永久 APPLIED，仍按原购买效果完成；取消不退款，也不把已扣款改写成未付款。
- APPLIED 的 native 证明和完整精确军队效果同一次 authority 提交。永久负面回执只完成无效果终态并释放预留。请求及终态永久保留，后续移动、消耗或退役不抹除已完成效果证明。
- 异常与确认不明不推断付款结果、不换 UUID、不自动退款、不自动刷新重试。owner 延续 A2 的失败关闭规则，须显式验证持久图后恢复。
- codec 在解析金额前限定最长 19 字符、固定两位的 canonical 文本；公开模型在缩放前拒绝小于一分的数值，避免短指数输入驱动巨大缩放计算。解码还拒绝非法布尔值、proof padding、状态、尾随数据和被静默截断的 native 纳秒时间。

## 最终验证

采用单 JVM、串行批次，160 MiB 编译/测试堆、SerialGC、单处理器；没有启动 Minecraft 服务或修改世界、玩家存档。

| 批次 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| Army authority、owner、adapter 与 A3 付费协议 | 332 | 0 / 0 / 0 |
| 既有 Army 行为 | 462 | 0 / 0 / 0 |
| 持久化、容量与公开 API 兼容 | 61 | 0 / 0 / 0 |
| 真实 Treasury debit authority | 25 | 0 / 0 / 0 |
| **去重总计** | **880** | **0 / 0 / 0** |

共 87 个套件，按类和 case 名去重，日志与 XML 总数一致。9 个改动生产源空目录编译为 17 个 class，3 个测试源编译为 4 个 class；与实际回归 classpath 字节一致。Spotless apply/check 通过，未更新 API 基线。

付费 coordinator 的 20 项 file/SQLite 用例使用实际 `TreasuryModule` 和永久回执；wrapper 仅注入查询失败、提交丢响应或错误凭据。覆盖重启、原号恢复、取消先持久化、取消遇既有扣款、永久余额不足、补给 generation 和容量预留。测试报价为冻结输入，不能作为价格计算规则的验证。

首轮 324 项亦通过；之后增加金额预检及八项回归，最终 332 项重新编译重跑。首轮不重复计入上述总数；本检查点没有失败尝试。

278 文件累计补丁为 2,914,420 字节，SHA-256：`f40a94254cebafb419b7fd72b98811a333f79e6c8702d08a0fe5e4ee1852751b`。固定基点正向检查、应用、反向检查及 diff 检查通过；独立重放后全部 3,790 个 src 文件、API 基线与 pom 经 LF 标准化逐文件一致。A2 之后变更 12 个 Java 源。

## 激活前仍须完成

当前仅完成协议边界，**全部阶段尚未完成**。

- 增加 Army 持久国家 closing/retired 准入，并接入 Nation 解散。已接受或已扣款请求须在国家消失前获得明确终态，不能用不同 JVM monitor 冒充生命周期协调。
- 本请求明确绑定直接国库资金。`NationDebitService` 证明不等于 Budget pool 预留/消费；若产品策略选择预算资金，须实现真实预算协议并使用新的明确版本，不能静默替换已接受请求的来源。
- 完成战斗 session、技能效果/冷却证明及授权策略，再统一迁移 ArmyService 与所有 Commander 直接 writer、快照读取和旧 rollback/restore。
- 实现真实总兵数限制和军事条约 receiver，再分别接两套领土 authority、混合效果和剩余付款恢复。
- 新价格、玩家权限、线程/启停、完整构建、新成品及配套回归尚未由本检查点验证。SQL 多进程、最新 MySQL 与按用户要求保留的 Paper/Folia 实服验收仍待完成。
