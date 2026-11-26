# 双边联盟与军事联盟持久退役

这是第二十份兼容补丁，按 [README](README.md) 在前十九份之后应用 `starcore-alliance-retirement.patch`。固定 Core 原始基线为 `f769e62799e769a18fe51305b545869a17f80cd1`。

## 协议与接线

`AllianceRetirementService.retireAlliances(operationId, nationId)` 与 `MilitaryAllianceRetirementService.retireMilitaryAlliances(operationId, nationId)` 分别在自己的权威中原子清理关系/军事协定、定向邀请与冷却，并保存不可变的 `RelationshipRetirementReceipt`。回执含原操作 UUID、国家 ID、原清理条目数。同号重试返回原结果，异号退役同一国家拒绝；空状态也能建立永久退役回执。

调用方须先持久授权解散；本接口不提供取消或重新开放。两入口通过服务注册表和 `StarCoreApi` 暴露。现有 `NationDisbandRecovery` 传递原解散 UUID 并校验回执归属；后续 WarService 失败时，重启继续复用原回执。第三方仅实现旧接口时仍采用旧清理，不能获得新保证；`clearNationRelations` 保持非永久清理语义。

普通修改与退役均有准入屏障，拒绝并发或回调重入。修改前读取当前权威，版本不同则刷新并要求业务重试；退役参与者不能再次结盟、邀请或创建协定。存储拒绝删除/更改旧回执或保存涉及退役国家的关系。服务保存成功后发布规范化的持久快照，避免内存纳秒时间与存储毫秒时间造成虚假冲突。

停用后旧服务引用拒绝新操作，不再把过时或未提交的内存整批刷盘。启用失败清理监听器、命令与自身服务注册，保留替换实例。目录准备同步完成，错误直接阻止启用。

## 存储与迁移

双边联盟沿用 `diplomacy/alliance_state.properties` 和 `starcore_alliance_state`；军事联盟沿用 `military-alliance/military_alliance_state.properties` 和 `starcore_military_alliance_state`。共享持久化机制不合并两套权威。`state.revision`、`authority.version` 与 `authority.sha256` 保护完整快照；`retired.<国家UUID>` 保存永久回执。

名称使用版本 2 的规范 UTF-8 Base64，保留竖线、中文与端点方向。有效旧版本 1 和三文件布局严格迁移，军事邀请保留旧 `v2:` 定向编码；重复、歧义或损坏条目直接拒绝，不静默遗漏或回滚备份。检测到已观察权威丢失或非零版本空 SQL 状态时拒绝重新迁入旧数据；完全丢失权威/版本/备份后的历史无法重建。

文件为单进程字节比较后原子替换；SQL 版本比较、全快照替换与回执在同一事务完成，SQLite 锁冲突有限重试。后端在构造时固定，迁入 SQL 需明确配置与重启。旧普通保存入口均接入同一权威，军事 `saveStateAsync` 现在同步提交后返回完成或失败 Future。共享 SQL 的所有写入者必须使用新协议，不混跑旧版本或两个独立后端。

提交失败会读取权威：有精确提交证据则返回成功，读回不可用则暂停新修改，需重载或重启后按原编号恢复。

## 验证

2,210 项 Core 定向回归（14 个不重叠批次、316 套件，其中退役专项 27 项）与实际最终 Core JAR 下 StarSupply 177 项通过，0 失败、错误、跳过。覆盖真实文件/SQLite 重启、独立实例竞争、迟到写入、回执不变性、空状态、名称编码、方向迁移、保存前失败、保存后响应丢失、读回冻结、SQL 回滚、固定后端及模块启停。两项 JDBC 代理在真实 commit 后抛错，分别验证可读回确认与无法读回后的恢复。

13 个生产源在空输出目录以 128 MiB 编译，19 个 class 与成品逐字节一致；其余旧 JAR 条目未变。29 文件补丁反向校验并重放后完整 3,653 个 src 文件与 API 基线一致。API 增加 18 条、删除 0 条。顺序回归 128 MiB、打包 192 MiB，仍基于保留的完整依赖，尚非全仓干净 Core verify。开发失败日志完整保留，最终通过数只汇总校验后的批次。

Core SHA-256：`281776212FD1A8653ABC4FFB94E05047174332F48A5EA5D742C12D22EC0FD2D5`。详见 [workspace-alliance-retirement-20261126.json](../verification/workspace-alliance-retirement-20261126.json)。

## 尚未闭环

国家解散共享意图/终态仍在独立文件日志；实际 WarService/赔款尚未永久退役。`module.alliance` 是独立的多国联盟系统，国家解散尚未清理其成员、邀请及关联状态。本次双边联盟退役不覆盖它。

普通双边结盟/解约仍分别提交 Diplomacy 关系与联盟快照，缺少跨权威持久意图；任一提交边界中断可能留下不一致，须另行补齐恢复。此项为当前源码复核，尚未完成专项故障行为验证。

只读缓存可以滞后，跨模块无事务隔离；停用期间已开始的提交可以完成。派生事件仍尽力发送，容量/归档、其他模块输入和玩法、全仓干净构建、最新 MySQL、Paper/Folia、生存及客户端视觉仍待完成。按用户要求，未启动服务器或修改世界、玩家存档。
