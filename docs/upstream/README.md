# StarCore 攻城财政依赖与部署生命周期修复

2026-11-27 最新开发检查点为 [和约绑定前取消 A13](development/ARMY-PREBINDING-CANCELLATION.md)：Diplomacy/Army 可永久封存尚未预留或尚未绑定的原请求，迟到预留不能恢复效果，已绑定仍须原 War 来源证明；生产纯政治和约已接入取消意图、封存、ACK/释放和竞争绑定恢复。接收器与 War 源均保留未来终态容量。最终四批 373 项 / 20 个 suite 通过；10 个生产源、6 个测试源空目录编译，346 文件累计补丁独立正反重放后 3,837 个 src、API 与 pom 一致，见[证据](../verification/workspace-army-prebinding-cancellation-development-20261127.json)。**全部阶段尚未完成。** 下一步持久化生产军事计划/终态向量、接通模块启停，再启用军事/混合条款。累计补丁仅用于第二十份固定基点并替代旧开发补丁；成品仍第二十份，全仓干净构建、新 JAR 和实服待验。

以下 A12 及更早段落为历史检查点，已包含在 A13 累计补丁中。

2026-11-27 最新开发检查点为 [军事条约真实权威联合协议 A12](development/ARMY-MILITARY-TREATY-AUTHORITIES.md)：War/Diplomacy 原生协议支持政治＋军事 manifest，逐 receiver 检查能力与 BOUND 证明；复读完整向量后准入/结束，完整一致终态后释放。真实文件/SQLite 的三权威覆盖十处丢回复重启、仅军事缴械、外交否决/取消/取代与生命周期变化。最终六批 607 项 / 36 个 suite 通过，见[证据](../verification/workspace-army-military-treaty-authorities-development-20261127.json)。**全部阶段尚未完成。** 联合测试直接驱动原生步骤，生产批次编排及模块注册仍待接线，玩家接受入口仍拒绝军事/混合条款。接下来完成批次计划/终态、取消恢复与模块启停后启用军事条款。成品仍第二十份；全仓干净构建、新 JAR 和实服待验。 累计补丁仍仅应用于第二十份固定基点，替代旧开发补丁，不能叠加。

以下 A11 及更早段落为历史检查点，已包含在 A12 累计补丁中。

2026-11-27 最新开发检查点为 [Army 军事条约接收器 A11](development/ARMY-MILITARY-TREATY-RECEIVER.md)：完整原条款、冻结计划、不可变 binding 和外交来源证明持久保存；核对 War 原结束/取消与外交终态后原子提交军事效果。未绑定可永久 abandon，绑定后按来源完成；预留先校验计划哈希容量，schema9 / row13 保留旧格式读取。最终五批 1,514 项 / 115 个 suite 通过，见[证据](../verification/workspace-army-military-treaty-receiver-development-20261127.json)。**全部阶段尚未完成。** Army 使用真实文件/SQLite，War 与外交来源在专项测试中为接口替身；生产尚未注册军事 receiver，和约仍拒绝军事/混合条款。接下来完成真实联合接线与全向量释放。生产 Army 仍为文件后端，成品仍第二十份；全仓干净构建、新 JAR 和实服待验。 累计补丁仍仅应用于第二十份固定基点，替代旧开发补丁，不能叠加。

以下 A10 及更早段落为历史检查点，已包含在 A11 累计补丁中。

2026-11-27 最新开发检查点为 [Army 持久军限与裁军 A10](development/ARMY-MILITARY-RESTRICTIONS.md)：完整本国军队预留、确定性整军裁撤与永久结果同 authority 提交，多个生效限制取最严格值；所有 writer 校验现有兵力和建军预留，解除使用独立原号且不复活军队。待决国家暂停普通业务/维护，旧付款与战斗必须先结清；schema8 / row12 保留旧格式读取，未来决定/解除容量与代数先预留。最终五批 1,492 项 / 113 个 suite 通过，见[证据](../verification/workspace-army-military-restrictions-development-20261127.json)。**全部阶段尚未完成。** 本阶段为 Army 权威原语，军事 receiver 的完整条款、manifest binding、War 来源证明和全向量释放仍待接线；生产和约仍拒绝军事/混合条款。生产 Army 仍为文件后端，成品仍第二十份；全仓干净构建、新 JAR 和实服待验。 累计补丁仍仅应用于第二十份固定基点，替代旧开发补丁，不能叠加。

以下 A9 及更早段落为历史检查点，已包含在 A10 累计补丁中。

2026-11-27 最新开发检查点为 [Army 国家总兵数配置 A9](development/ARMY-SOLDIER-CAPACITY.md)：创建和指挥官增援计算现有兵力与待完成建军预留，原操作上限纳入持久请求和存储复验；降低配置仍结清原付款，额度不足不扣款、不消费冷却。schema7 / row10、row11 保留旧行及摘要读取；列表显示现有/上限/预留人数，文件和 SQLite 的冲突、丢确认、战斗及退役边界已有验证。最终五批 1,466 项 / 110 个 suite 通过，见[证据](../verification/workspace-army-soldier-capacity-development-20261127.json)。**全部阶段尚未完成。** 配置额度不等于永久军事条约限制；下一阶段为持久军限、裁军及军事 receiver。生产 Army 仍为文件后端，成品仍第二十份；全仓干净构建、新 JAR 和实服待验。 累计补丁仍仅应用于第二十份固定基点，替代旧开发补丁，不能叠加。

以下 A8 及更早段落为历史检查点，已包含在 A9 累计补丁中。

2026-11-27 最新开发检查点为 [Army 运行时切换 A8](development/ARMY-RUNTIME-ACTIVATION.md)：ArmyService / Commander、玩家命令和 GUI、战争集成与 Nation 解散已经接通持久权威；付款、技能/冷却、战斗与国家退役均使用原编号或同次提交，getter 返回脱离视图。资金策略固定直接国库，失败准入不消费冷却。启动恢复失败保留管理员命令并暂停普通业务，完整恢复后开放。最终六批 1,651 项 / 122 个 suite 全部通过，见[证据](../verification/workspace-army-runtime-activation-development-20261127.json)。**源码运行时已激活，全部阶段尚未完成。** 下一阶段为真实总兵数增长限制与军事 receiver，随后继续领土、混合条约及其他模块。生产 Army 当前固定文件后端；SQLite 通过注入验证。成品仍为第二十份，尚未全仓干净构建或生成新 JAR；实服待验。 累计补丁基点为 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代此前开发补丁，不能叠加。

以下 A7 及更早段落为历史检查点，已包含在 A8 累计补丁中；后续工作以上段及当前清单为准。

2026-11-27 最新源码入口为 [Army 战斗技能干预 A7](development/ARMY-BATTLE-INTERVENTION.md)：技能、完整目标与全部命中战斗一次提交；支援、非致命冲锋继续战斗，撤退与致命冲锋明确结束，结算/取消以最新快照为准。schema6 / row9 保存完整历史，旧 row6 保持；未来终态容量、关闭对手退出及原号重放已有回归。最终四批 1,351 项通过，见[证据](../verification/workspace-army-battle-intervention-development-20261127.json)。**ArmyService / Commander 与 Nation 解散尚未接线，全部阶段未完成。** 继续真实准入、失败冷却策略、解散、全部 writer、预算、军限与 receiver。累计补丁仅应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代旧开发补丁；JAR、安装序列与 review ZIP 仍第二十份。未启动实服或修改世界、玩家存档。

以下 A6 段落为此前检查点，已包含在 A7 累计补丁中。

2026-11-27 最新源码入口为 [Army 国家关闭与退役 A6](development/ARMY-NATION-CLOSURE.md)：schema5 永久保存原号关闭/退役，关闭阻止新写入，已接受付费与战斗先结清，再原子清退完整本国集合；历史证明、精确墓碑和未来容量保留。1,309 项、100 个套件通过，9 个生产源与 7 个测试源空目录编译；299 文件累计补丁重放后 3,811 个 src、API、pom 一致，见[证据](../verification/workspace-army-nation-closure-development-20261127.json)。**ArmyService / Commander 与 Nation 解散尚未接线，全部阶段未完成。** 战斗技能/撤退、旧失败冷却、目录删除前 Army 屏障、Treasury 退役前 drain、预算及全 writer 迁移继续开发。累计补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代旧开发补丁，不能叠加；JAR、安装序列与 review ZIP 仍第二十份。未启动实服或修改世界、玩家存档。

以下 A5 段落为此前检查点，已包含在 A6 累计补丁中。

2026-11-27 最新源码入口为 [Army 技能效果与冷却 A5](development/ARMY-SKILL-LEDGER.md)：schema4 在一次 CAS 中确认固定 v1 效果、完整原请求、永久施法证明与完整玩家 UUID / 技能冷却链；完整选择、精确重放、纳秒截止、阵亡墓碑及跨账本同 token 快照一致性已覆盖。1,216 项、95 个套件通过，9 个生产源与 7 个测试源空目录编译；292 文件累计补丁重放后 3,804 个 src、API、pom 一致，见[证据](../verification/workspace-army-skill-ledger-development-20261127.json)。**ArmyService / Commander 尚未激活，全部阶段未完成。** 战斗干预/战术撤退、旧失败冷却策略、国家生命周期/解散、预算策略和全 writer 迁移仍待完成，当前仅直接国库策略。累计补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代旧开发补丁，不能叠加；JAR、安装序列与 review ZIP 仍第二十份。未启动实服或修改世界、玩家存档。

以下 A4 段落为此前检查点，已包含在 A5 累计补丁中。

2026-11-27 最新源码入口为 [Army 战斗会话 A4](development/ARMY-BATTLE-SESSIONS.md)：schema3 双方完整快照冻结、原子开始 / 终态、取消不回滚、永久阵亡墓碑及严格同代效果证明已实现；付费与战斗联合预留未来 revision / 字节，真实文件 / SQLite 覆盖重启、丢响应和恰好容量的全部结果。998 项、90 个套件通过，9 个生产源与 4 个测试源空目录编译；284 文件累计补丁重放后 3,796 个 src、API、pom 一致，见[证据](../verification/workspace-army-battle-sessions-development-20261127.json)。**ArmyService / Commander 尚未激活，全部阶段未完成。** 继续核对战斗冻结与技能玩法，补技能 / 冷却、国家生命周期 / 解散、预算策略及全 writer 迁移，当前仅直接国库策略。累计补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代旧开发补丁，不能叠加；JAR、安装序列与 review ZIP 仍第二十份。未启动实服或修改世界、玩家存档。

以下 A3 段落为此前检查点，已包含在 A4 累计补丁中。

2026-11-27 最新源码入口为 [Army 付费操作恢复 A3](development/ARMY-PAID-RECOVERY.md)：schema2 原请求、容量/目标预留、持久取消意图与完整国库回执，同 authority 原子确认效果；未来字节/revision 余量在外部扣款前保留。880 项最终测试、87 个套件通过，9 个生产源和 3 个测试源空目录编译；278 文件累计补丁重放后 3,790 个 src、API 与 pom 一致，见[证据](../verification/workspace-army-paid-recovery-development-20261127.json)。**ArmyService/Commander 尚未激活，全部阶段未完成。** 国家生命周期/解散、预算策略、session/技能证明及全 writer 迁移继续为激活门槛，当前仅直接国库资金策略。累计补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代旧开发补丁，不叠加；JAR、安装序列与 review ZIP 仍第二十份。未启动实服或修改世界、玩家存档。

以下 A2 段落为此前检查点，已包含在 A3 累计补丁中。

2026-11-27 最新源码入口为 [Army 权威接线边界 A2](development/ARMY-AUTHORITY-BOUNDARY.md)：新增私有 owner、显式 readiness/永久关闭、完整确认后发布、原子多军队更新/退役、精确后勤重放、原始字节 bootstrap 和 detached runtime adapter；正常被动 `.bak` 仅保留并记录摘要。706 项最终测试、83 个套件通过，3 个生产源和 4 个测试源空目录编译；271 文件累计补丁正反向重放后 3,783 个 src、API 与 pom 一致，见[证据](../verification/workspace-army-authority-boundary-development-20261127.json)。**运行时 ArmyService/Commander 尚未激活，全部阶段未完成。** 下一步先以现有 `NationDebitService` 和版本化同 authority 的付费/session/技能冷却证明补齐恢复边界及国家生命周期/预算准入，再统一迁移 writer 与快照读取；真实军限和军事 receiver 仍待实现。累计补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代此前全部开发补丁，不叠加；JAR、成品安装序列与 review ZIP 仍第二十份。未启动实服或修改世界、玩家存档。

下段为此前条约 v2 记录，已由上述新累计补丁包含。

2026-11-27 最新源码入口为[和约 effect manifest v2](development/TREATY-EFFECT-MANIFEST.md)：完整原始 envelope、request/local/global 三层 hash、不可变 bind、原生 prepare/end 的 BOUND proof 验证与 release 全终态向量已接通。schema4 显式保留旧 v1；新 v2 仍只允许空/纯政治和单一 diplomacy receiver，文件及经实际连接元数据确认的 SQLite 具备 proof 门禁和未来终态容量保护，其他 SQL 失败关闭。管理命令与 GUI 展示协议和三层 hash，原编号取消、审查和权限保留。最终回归与累计重放见[证据入口](../verification/workspace-treaty-effect-manifest-development-20261127.json)。新累计补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代全部旧开发补丁，不能叠加，也尚未加入下方成品安装序列。**全部阶段尚未完成，JAR、安装序列与 review ZIP 仍第二十份。** 下一 P1 为 Army 耐久权威/真实军限及 receiver，其后分别接两套领土权威；赔款/贡纳/混合、其他付款和模块审计继续开发。生产 Folia 声明未扩大，实服待验，未启动服务器或修改世界、玩家存档。

以下战争—外交镜像和费用恢复段为此前开发记录，已被上述新累计补丁覆盖。

此前源码入口为[战争—外交镜像开发检查点](development/war-diplomacy-mirror-20261126.patch)，在宣战费用恢复检查点之上接入完整战争快照、原编号版本围栏、历史回执、归档战争和定期补投；7 个生产源低内存编译及真实文件/SQLite 18 项测试通过。最新定向 117 项、7 套件通过；最终六批累计 986 项、128 套件通过（含最终 API 兼容套件一次，较早独立 API 检查 1 项不另累计），空目录累计编译 155 个生产 class、92 个测试 class 与最终测试 class 一致，独立回放全部 3,697 个 src 文件、API 基线和 pom.xml 一致。新累计补丁只应用于第二十份基点 9e380d62fd94cdc54f6f703ea09e14f575660f18，替代全部旧开发补丁，不叠加旧开发补丁。它尚未加入下方成品安装序列，JAR、review ZIP 和默认安装仍为第二十份补丁。已扣费取消会完成原战争；外交接收端、事件耐久、和约/结盟效果、旧日志及其他安全取消继续待开发，全仓干净构建、新成品、完整解散进程强杀、SQL 多进程/MySQL 和 Paper/Folia 实服仍待验。历史[文件多进程保护检查点](development/FILE-AUTHORITY-LOCKS.md)保留其本轮之前的记录，当前缺口见[代码清单](../CODE-CLOSURE.md)。

当前最新兼容包按顺序应用二十份补丁：在前十九份后追加[双边联盟与军事联盟持久退役](ALLIANCE-RETIREMENT.md)（`starcore-alliance-retirement.patch`）。现有解散按原编号退役两套权威，关系、邀请、冷却与永久回执同次保存；严格文件/SQL 提交、重启恢复与启停保护已接通。2,210 项 Core 定向回归及实际成品下 177 项配套测试通过。共享解散、实际战争/赔款、独立多国联盟与普通结盟跨权威一致性仍待处理，详见[代码清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前外交退役兼容包按顺序应用十九份补丁：在前十八份后追加[外交持久退役](DIPLOMACY-RETIREMENT.md)（`starcore-diplomacy-retirement.patch`）。现有解散按原编号关闭外交关系权威，永久回执与全部关系子状态同次保存，拒绝旧实例迟到写入；文件/SQL 同步校验及完整数组更新已接通。2,075 项 Core 定向回归及实际成品下 177 项配套测试通过。共享解散、联盟/军事联盟/实际战争退役和其他范围见[代码清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前城市输入兼容包按顺序应用十八份补丁：在前十七份后追加[城市文本输入](CITY-INPUT-RECOVERY.md)（`starcore-city-input-recovery.patch`）。名称、公告和金额输入采用原生文本窗口，保留预览/确认并绑定连接和原归属，避免真实物品替换。1,938 项 Core 定向回归及实际成品下 177 项配套测试通过。共享解散、其他模块书本输入和剩余工作见[代码清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前城市资金兼容包按顺序应用十七份补丁：在前十六份后追加[城市资金恢复](CITY-MONEY-RECOVERY.md)（`starcore-city-money-recovery.patch`）。命令/GUI 真实转移玩家与城市资金，持久意图、同号回执、原编号恢复和精确余额已接通。1,911 项 Core 定向回归及实际成品下 177 项配套测试通过。共享解散和其余外部退役、城市临时物品/其他交互及其他未闭环项见[清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前城市退役兼容包按顺序应用十六份补丁：在前十五份后追加[城市退役与恢复](CITY-RECOVERY.md)（`starcore-city-recovery.patch`）。城市删除/退役与原始结果永久保存，文件/SQL 权威拒绝旧对象写回并恢复失败缓存。1,852 项 Core 定向回归及实际成品下 177 项配套测试通过。共享解散与其余外部退役、城市资金/交互仍有代码缺口，见[清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前领土与国库退役兼容包按顺序应用十五份补丁：在前十四份后追加[领土与国库退役](RETIREMENT-RECOVERY.md)（`starcore-retirement-recovery.patch`）。已接入现有解散清理，永久关闭领土所有者和国库账户，保留原结果。1,754 项 Core 定向回归及实际成品下 177 项配套测试通过；完整共享解散与城市/外交/战争退役仍待实现，见[清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前分裂兼容包按顺序应用十四份补丁：在前十三份后追加[国家分裂恢复](SPLIT-RECOVERY.md)（`starcore-split-recovery.patch`）。共享意图、原编号领土转移与目录终态、取消和管理恢复已接通；不再反向转地。1,712 项 Core 定向回归与实际成品下 177 项配套测试通过。完整国家生命周期仍需解散和外部退役协议，其他缺口见[代码清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前圈地兼容包按顺序应用十三份补丁：在前十二份后追加[圈地付款与领土回执恢复](CLAIM-RECOVERY.md)（`starcore-claim-recovery.patch`）。原报价/选区意图、原编号付款/退款、整批地块与不可变回执、管理员恢复已接通；文件/SQL 领土同步版本提交。1,666 项 Core 定向回归和实际最终 Core 下 StarSupply 177 项通过。完整国家生命周期、圈地派生通知及其他待办见[代码清单](../CODE-CLOSURE.md)。生产 Folia 声明关闭。


此前创建恢复兼容包按顺序应用十二份补丁，在下述十一份后追加 [建国与城邦恢复](CREATION-RECOVERY.md)（`starcore-creation-recovery.patch`）。新创建按原编号恢复付款/退款，并保留终态、提供管理入口；SQL 普通账户与回执快照共享版本，防止旧实例覆盖收入。1,322 项 Core 定向回归和实际最终 Core 下 StarSupply 177 项通过。圈地、完整国家生命周期跨实例协议及其他未闭环范围见[当前清单](../CODE-CLOSURE.md)。生产 Folia 声明继续关闭。


此前演习兼容包按顺序应用十一份补丁：后勤、玩家资金回执、税款回执、公共反馈、HUD 设置、[Clan 闭环](CLAN-CLOSURE.md)、[Religion 闭环](RELIGION-CLOSURE.md)、[国家 Faith 闭环](FAITH-CLOSURE.md)、[海军可靠性修复](NAVY-RELIABILITY.md)、[预算付款恢复](BUDGET-RECOVERY.md)、[演习与经验恢复](EXERCISE-RECOVERY.md)。第十一份保存演习、唯一结果与国家经验回执，修复权限、阵营统计、线程归属和启停，加入实验后勤 profile。1,238 项 Core 定向回归及实际成品下 StarSupply 177 项通过。建国创建费用恢复、海军真实运兵、伤兵、学说、预算/演习运维及归档仍待开发，详见[当前清单](../CODE-CLOSURE.md)。生产 Folia 声明继续关闭。

此前 HUD 兼容包按顺序应用五份补丁，在下述四份补丁后追加 [starcore-hud-settings.patch](starcore-hud-settings.patch)。个人设置持久化、真实重载、呼吸动画和彩色主题等 HUD 缺口已补齐；配置、命令、构建与验收范围见 [HUD-SETTINGS](HUD-SETTINGS.md)。

此前公共反馈检查点在前三份补丁后追加 [starcore-folia-feedback.patch](starcore-folia-feedback.patch)：音效、客户端震动与现代 HUD 现在进入实体/区域线程并接回 profile，停用、退役与菜单回调有定向测试。应用顺序、已知命令缺口和实服边界见 [FOLIA-FEEDBACK](FOLIA-FEEDBACK.md)。下方未叠加第四份补丁的历史描述仍按其原始范围理解。

当前玩家资金恢复还需在 `starcore-folia-logistics.patch` 之后追加 [starcore-player-money-receipts.patch](starcore-player-money-receipts.patch)。公开协议、文件/SQL 迁移、旧版兼容、定向测试与打包顺序见 [PLAYER-MONEY](PLAYER-MONEY.md)。随后追加[税款回执补丁](starcore-tax-funding-receipts.patch)，接口、原报价恢复和构建范围见 [TAX-FUNDING](TAX-FUNDING.md)。这些补丁不扩大 Folia 支持声明；原后勤组合补丁已包含本页的攻城修复，不要重复叠加。

补丁 [starcore-siege-treasury.patch](starcore-siege-treasury.patch) 适用于公开仓库 `addxiaoyi/starcore` 的固定提交 `f769e62799e769a18fe51305b545869a17f80cd1`。这是单独、可审查的上游源码修复，不包含编译产物，也未推送到远端。StarSupply 的编译 API 仍以该固定提交为基础。

固定版本的 `SiegeModule` 只声明 `nation` 和 `economy` 依赖，但在创建器械时必须通过 `TreasuryService` 查询、扣除国家资金。模块管理器可能先启用 `siege`、再启用 `treasury`；原来的可选查询把 null 永久传入攻城服务，后续创建器械便会失败。StarSupply 读取真实围城状态的功能需要服务器上的权威攻城服务正常运行。

隔离服随后还发现，固定版本的公开 `deploySiege` 只把器械改为 `DEPLOYING`，既有分钟更新任务从不调用 `completeDeployment`。因此正常服务调用不能到达 `READY`，部署后的器械一直不能开始攻城。组合补丁保留原来的更新周期和存档格式，在下一次既有更新中完成仍处于部署状态、存活且有部署坐标的器械。

补丁改变以下业务约束：

- 将 `treasury` 加入 `SiegeModule` 显式依赖，保证财政服务先启用。
- 使用 `serviceRegistry.require(TreasuryService.class)`，服务缺失时在创建定时攻城服务之前拒绝启动。
- 在既有一分钟攻城更新中把有效 `DEPLOYING` 器械转为 `READY` 并沿原路径持久化。撤退、解散、失去生命/船员或缺少部署位置的器械不会提前就绪。重新部署冷却仍保持原值；没有新增独立计时器，也没有修改编解码器。

新增 `SiegeModuleTreasuryLifecycleTest` 包含三项运行行为测试：真实 `ModuleManager` 在反向注册顺序下仍先注册财政服务，再创建攻城服务；创建真实器械调用所注册的财政权威；财政模块缺失或直接启用时未注册财政服务，都不会创建攻城定时任务。测试还检查正常关闭释放服务、取消两项定时任务。

新增 `SiegeDeploymentLifecycleTest` 覆盖三条部署路径：公开创建和部署、捕获的原有周期任务使其就绪、公开开始攻击真实城墙；经过原有编解码与持久化加载后首次更新恢复待完成部署；撤退、解散以及无效存活/坐标条件不会完成部署。

## 应用与验证

在独立干净检出上操作，保留原服务器和原 StarCore 检出：

```powershell
git worktree add --detach C:\path\to\starcore-siege-fix f769e62799e769a18fe51305b545869a17f80cd1
Set-Location C:\path\to\starcore-siege-fix
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-siege-treasury.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-siege-treasury.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1'
.\mvnw.cmd '-Dspotless.skip=true' '-Dtest=SiegeModuleTreasuryLifecycleTest,SiegeDeploymentLifecycleTest,ModuleManagerTest,ArmyExtensionLifecycleTest' test
```

2026-11-20 第一版财政修复已在该独立检出用 Java 21 执行定向测试：`SiegeModuleTreasuryLifecycleTest` 3 项、`ModuleManagerTest` 8 项、`ArmyExtensionLifecycleTest` 6 项，共 17 项全部通过，0 失败、0 错误、0 跳过。Maven 从源码重新编译了 2233 个生产文件和 1008 个测试文件；日志位于独立检出的 `target-siege-tests.log`。该记录早于部署生命周期修复，当前组合补丁的新增部署测试和新产物仍待重新构建验证。`git diff --check` 与对原始固定版本的 `git apply --check` 对当前组合补丁均通过。选定生命周期测试不能替代上游全量测试或 Paper 运行验收。

在该独立目录执行 `.\mvnw.cmd '-DskipTests' '-Dspotless.skip=true' package` 构建供隔离服务器验收的 StarCore 包；不执行 `install`，保留固定版本的本地 Maven 编译依赖。正式服部署应等待相应运行验收。

2026-11-20 第一版财政修复的独立 `package` 已成功，产物为该检出中的 `target/starcore-1.0.0.jar`，大小 19,507,084 字节，SHA-256 为 `3440310F482196EB322CC6EE792A94477C98EE91ABE63106F2AB1BF69109F8EA`；构建日志为 `target-siege-package.log`。此旧产物尚不包含部署生命周期修复，不应作为当前组合补丁的运行验证依据。产物没有安装到本地 Maven 仓库或覆盖原固定依赖。

2026-11-20 组合补丁已重新完整编译生产源码与测试源码，并通过四个选定套件：财政生命周期 3 项、部署生命周期 3 项、模块管理 8 项、军队扩展生命周期 6 项，共 20 项，0 失败、0 错误、0 跳过。构建完成于 13:54:02，用时 3 分 37 秒，日志为 `target-siege-tests-combined.log`。组合包另行构建，Paper 围城和部署验收仍以主验收记录为准；该结果不替代上游全部测试。

组合补丁独立 `package` 于 14:00:32 成功，用时 2 分 59 秒；产物仍为 `target/starcore-1.0.0.jar`，SHA-256 `676CF4E4D6AE52183AA80DB13A6E4A2E486A1AF93AE965A0176B08108C42AFC3`。日志为 `target-siege-package-combined.log`。该包包含财政与部署两项修复，未执行 `install`。

隔离 Paper 已实际验证该组合包：公开创建和部署器械后，等待正常分钟更新进入 `READY`，再公开开始真实围城；StarSupply 压力达到配置上限，公开撤退后下周期恢复普通需求。真实军队战斗也证明后勤倍率进入实际伤害。详细运行环境、哈希和证据见 [Paper 验收记录](../PAPER-ACCEPTANCE.md)；验收后隔离服恢复原 StarCore JAR，原上游与正式服未被替换。

复现工作目录为 `C:\Users\Administrator\Documents\starsupply-deps\starcore-siege-fix`，其 HEAD 保持上述固定提交，改动只包含此补丁的生产文件和新增测试。原检出 `starsupply-deps\starcore` 未被修改。

## 实验 Folia 后勤 profile 与构建边界

另一个独立检出 `starcore-folia` 正在迁移 StarSupply 所需的上游模块。实验开关为 `platform.folia-logistics-profile`，默认 `false`；启用后，模块范围限制为 `nation`、`economy`、`government`、`resolution`、`treasury`、`diplomacy`、`resource`、`technology`、`policy`、`officer`、`army`、`war`、`siege`、`taxation`、`event`。Treasury 仍注册 loan、budget、bankruptcy 权威服务。实验源码已导出为 [完整 Folia 后勤补丁](starcore-folia-logistics.patch)，包含上方攻城修复，应直接应用于固定原始提交，不要叠加旧攻城补丁。

实验迁移使用显式全局、实体和区域调度，保留服务对权威数据的串行访问。覆盖范围包括军队与攻城周期、战争通知与动画、国家异步传送及退款台账、财政与政策科技周期，以及补给线路和战争模型的并发快照。玩家操作在实体所有权下执行，固定位置效果使用区域任务；关键确认写入与失败补偿仍保留原有持久确认语义，不将这些路径描述为全部异步。

2026-11-20 17:21:56 的历史 `package` 使用 Java 21 和 Maven 3.9.11，重新编译 2234 个生产文件、1030 个测试文件。选中 971 项测试，其中 **967 项实际执行并通过，4 项跳过，0 失败、0 错误**。两项 MySQL 集成测试因未设置 `starcore.mysql.it` 而跳过；另外两项旧 `WarModuleAuthorityTest` 已因引用移除的方法而标记废弃。选定范围、具体用例名与原始跳过理由见 [上游定向测试记录](../verification/folia-upstream-targeted-tests-20261120.json)，不能把四项跳过计为执行通过。

该批次全部 106 个改动或新增 Java 文件通过定向 Spotless apply/check；浅历史检出显式使用 `ratchetFrom=HEAD`。构建前后登记的 107 项改动或新增 `src` 文件，内容哈希及路径集合均无变化。这是该批改动的冻结核验，不是整个上游仓库所有源文件的哈希清单。历史 JAR 大小 19,546,152 字节，SHA-256 `5643DBBBA0E61F7B267491716FCDA561468E68C82816B5B2EA20ABA7A0D82F60`；未执行 `install`。后续可选命令与 profile 注册修订需另记新构建和新哈希，不能沿用这份历史产物的验证结论。

17:26:48 的后续构建只追加 `StarCorePlugin` 与 `EconomyCommand` 两类源改动，处理缩减 profile 下未初始化的 HUD/旧压测命令注册，以及排行榜数据读取与玩家回调边界；34 项针对性测试实际执行通过，0 失败、0 错误、0 跳过。该新源包 SHA-256 为 `CDD264D1D5A4F4836D91CC645EB3DDD1A14342FF69F81F7146040CD3E4A40C74`。前述 971 项没有在这个新包上全量重跑，两次测试结果不能当作一个未发生的运行。

为后续隔离 Folia 运行测试，单独测试副本只覆盖插件描述符的 `folia-supported: true`，使运行副本的哈希区别于源构建：StarCore 为 `0C0D340C40E540674F9DE7E9BB03216894D26C78C51D4350DD331C07C807D509`，StarSupply 为 `16A1ED43CB3AC67F7992BA6C6DF79B78683983B8EE4105B0B6AB6309978A867B`。这些副本描述符不改变主发布声明；本节记录的是测试准备与构建证据，没有 Folia 现场通过结论。

这 971 项记录不包含 Folia 启动、多区域玩家、真实运输、重启或停服现场验收。当前主发布插件仍未声明 `folia-supported`，完整组合支持尚未完成；上述定向测试也不替代上游全量测试。后续运行验收应明确关联实际部署的 JAR 哈希、启用的模块和选装插件版本。

补丁对原始固定检出的 `git apply --check` 已通过。可在独立目录复现，不覆盖原始 Maven 依赖：

```powershell
git worktree add --detach C:\path\to\starcore-folia f769e62799e769a18fe51305b545869a17f80cd1
Set-Location C:\path\to\starcore-folia
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
# 完整定向 selector 见上方 JSON；浅历史使用 HEAD，避免不存在的 HEAD^。
.\mvnw.cmd '-Dspotless.ratchetFrom=HEAD' '-Dtest=CompatSchedulerTest,*Folia*Test,ModuleManagerTest,ArmyService*Test,*Siege*Test,War*Test,SupplyLine*Test,Battlefield*Test' package
```

该源码补丁保留生产描述符的保守状态。隔离运行夹具只对测试 JAR 增加 Folia 加载标识，并分别记录修改前后哈希；这允许实际检查线程归属，不构成发布支持声明。仅应用原后勤补丁时，profile 下的可选现代 HUD 和压力测试命令不注册；叠加新反馈补丁后 HUD 已接回，压力测试命令仍不注册，国家及 StarSupply 常规 GUI 继续提供。Folia 下停用 StarCore 时，其他实体线程上的瞬态科技属性随玩家重登或服务器重启清理；不支持通过热重载验证正式服生命周期。

## SQLite 国库快照并发争用修复

真实 Folia 双区域验收在并发圈地与军队创建时发现国库快照写入 `SQLITE_BUSY`。原调用正确回滚：Gamma 国库在失败前后均为 66500.00，军队数量保持 1；随后一次明确的人工创建才扣除 1000.00，余额变为 65500.00。日志中的 `txId=1` 是持久化失败的诊断计数，不是经济收据 ID。原始堆栈保存在隔离服轮转日志 `logs/2026-11-20-2.log.gz`，公开状态与后续成功创建记录见 [争用修复证据](../verification/folia-sqlite-contention-recovery-20261120.json)。证据所指的手工 `six-loop-before-restart` 归档目录未实际生成，因此该记录引用现存压缩日志的完整哈希。

独立工作树的 `SqlTreasuryStateStorage.writeSnapshot` 现在只重试已确认回滚的 SQL 快照事务，不重放国库扣款、军队创建或外部支付。只有尚未尝试提交、回滚成功、连接状态恢复成功的 SQLite 锁争用才可再尝试；最多额外两次，启动后续尝试时检查累计耗时是否仍小于 100 毫秒。该预算不等同于对已进行中 JDBC 调用的超时。每次重新读取仍核对原有版本 CAS，不能覆盖别处已经提交的新国库状态。提交回执或回滚结果不明时进入待核对状态，不自动重试；回滚不明时也不重新开启 auto-commit，避免意外提交未确认事务。

2026-11-20 18:03:50 的针对性 `package` 重新编译 2234 个生产文件、1031 个测试文件：选中 67 项，**65 项实际执行通过、2 项 MySQL 测试因缺少 `starcore.mysql.it` 跳过，0 失败、0 错误**。新增四项使用真实 SQLite 连接并覆盖 WAL 快照升级争用、陈旧版本拒绝、提交回执不明以及回滚不明。两份改动 Java 文件通过定向 Spotless apply/check；构建前后 110 个改动或新增 `src` 条目的内容及路径集合一致。源 JAR 大小 19,548,109 字节，SHA-256 `BD0B09DDA770E523B6666EF1126EA3ACDDA71DC2F2DC4CE502803D06E87C898A`，未执行 `install`。这 65 项与前述 967 项、34 项分别记录，不能合并描述成同一构建的全量测试。

2026-11-22 在隔离 Folia 副本进行了后续观察。双玩家区域探针由线程 `#0`、`#1` 同时执行，`maxActive=2`。随后 SupplyBuyer 在 `#0` 执行 `/army create infantry 10`，SupplyGamma 在 `#1` 执行 `/sc nation claim`，两者均成功，提交时间相差 79 ms。正常停服、使用相同 JAR 和数据库重启后，Alpha 与 Gamma 的国库、军队数、领土数及六个玩家存档哈希保持一致。详细状态、哈希和快照对照见[现场并发与重启记录](../verification/folia-sqlite-contention-retest-20261122.json)。

现场日志未出现 `SQLITE_BUSY`、`SQLITE_LOCKED` 或国库持久化失败。当时构建没有成功重试日志标记，因此该次运行不能证明 `writeSnapshot` 的重试循环在现场被执行。此后加入的 `SQLITE_BUSY_RETRY` 观测日志已由含真实 SQLite 连接的定向测试触发；最新观测包仍待部署到 Folia 现场，完整运行证据和构建哈希见 [复核记录](../verification/folia-upstream-full-retest-20261122.json)。

## 军队供给维护换算与士气恢复

2026-11-21 对既有 Folia 后勤补丁作了额外修正。`ArmyState.supplyConsumptionPerHour` 现在由 `ArmyUnit` 以 0–59 的整数余量跨分钟累计；驻扎军队的 1 点/小时准确消耗为每 60 次分钟维护 1 点，行军、进攻、攻城和防御使用相同的累积方法。余量写入既有 ArmyState JSON，复制、失败回滚和重启均保留；旧记录缺少该字段时从 0 开始。

`ArmyUnit.consumeSupply` 原先藏有额外 -5 士气，和 `ArmyService.updateArmy` 的 -2 重复作用。上游模型扣减已移除，只保留明确的每分钟 starvation -2；原生补给恢复到 20 或以上时，每分钟恢复 1 点基础士气，使短期断粮损失可逐步恢复。低士气下 StarCore 原有的溃散规则仍保留；这项调整没有把战斗伤亡或已保存的历史士气损失追溯清零。上方 19 项维护测试记录对应的旧补丁快照尚未包含 StarSupply 实物补给结转接口，不能将其作为当前接口的测试记录。

新增 `ArmyUnitSupplyConsumptionTest` 与扩展后的 `ArmyStateCodecTest`、`ArmyServiceStatePersistenceTest` 覆盖 1/3 点每小时的 60 分钟累积、余量跨复制/回滚和存档保留、耗尽后清零、旧 JSON 默认余量、非法余量拒绝、单次饥饿扣士气及补给后的逐分钟恢复。Java 21 定向构建通过 19 项测试，0 失败、0 错误、0 跳过。更新后的补丁先在固定原始提交的新工作树 `git apply --check` 成功，再应用到该干净工作树并重新执行同样的 19 项测试，均通过。

该版本使用 Maven Wrapper 3.9.16 执行 `package -DskipTests`，成功重新编译 2235 个生产和 1034 个测试源文件；因该次 package 明确跳过测试，测试结论仅取自前述单独的 19 项定向测试。StarCore 源包为 19,552,956 字节，SHA-256 `8D28C62313DF04C478B2E0A29CB5F663B61029762D9AC4217E7543AEB762650D`。更新后的完整可移植补丁 SHA-256 为 `B5B04DBBC7619DC417932C143C487AFF9545AE9C3466A5B04E5ACB0AAA810C88`，详细记录见 [维护修正验收数据](../verification/folia-army-supply-maintenance-20261121.json)。此修正版尚未部署到 Folia 隔离服，因此当前证据证明源码测试和构建，不证明运行现场通过。

## StarCore 原生补给耐久回执

当前 Folia 后勤补丁为 `ArmyService` 增加 `applyLogisticsSupply(requestId, armyId, nationId, points)` 与 `logisticsSupplyReceipt(requestId)`。补给变化不调用国库扣款，军队快照与请求回执写入同一份 StarCore 持久快照。以相同 UUID 和输入重试会返回原回执；回执在重启和军队解散后仍可读取。补给已达 100 时 `appliedPoints` 可以为 0，该请求仍永久占用其 UUID。持久层不可用或回执数据损坏时，服务拒绝处理补给请求。

2026-11-21 的 StarCore 专项报告通过 `ArmyLogisticsSupplyTest` 8 项、`ArmyServiceStatePersistenceTest` 12 项和 `ArmyUnitSupplyConsumptionTest` 4 项，共 24 项，0 失败、0 错误、0 跳过。测试覆盖不扣国库、重复请求不重复加点、写入确认不明后的重试、重启后的已提交和未提交请求、请求身份冲突、补给上限、损坏回执与失败加载保护。StarCore 兼容包随后以 `-DskipTests package` 成功构建，重新编译 2236 个生产文件和 1035 个测试文件；该 package 阶段跳过了测试执行。JAR 为 19,556,178 字节，SHA-256 `6D84B645268E9DFCCA7B0766B898C372E32F448CB6B317C919BF65E3B3AE8243`。包含 2026-11-22 API 基线、聊天测试、Windows Dungeon 修复和 SQLite 重试观测日志的完整 Folia 补丁 SHA-256 为 `90340B2BE29EF76D071F618EE65B7F44ED82F4A803FC8BE8CD9A40C812FD5350`；此前运行证据仍关联各自记录时的补丁哈希。

StarSupply v10 对应 JAR 通过 92 项自动化测试，SHA-256 为 `743F3F188186B475403792BCCED45DD3B6DC22EE7F1F5FBC647FE26EDFCA98A3`。StarCore 补丁构建 SHA-256 为 `6D84B645268E9DFCCA7B0766B898C372E32F448CB6B317C919BF65E3B3AE8243`。Folia 隔离运行副本仅在两个 JAR 的 `plugin.yml` 添加 `folia-supported: true`，其余 JAR 条目与上述构建一致。该组合已完成六类采购运输、6 个完整六资源消费周期、原生补给请求唯一回执、重启后重放和基础士气恢复验收；完整哈希、步骤及边界见 [补给回执验证记录](../verification/starcore-native-supply-receipts-20261121.json)。本次物资由管理员发放且可选 Cookery/Tavern 未启用，因此不代表六类生存生产或完整 Folia 插件组合认证。

## 2026-11-22 Folia 补丁测试复核

Windows Java 21 上首次执行完整 `mvnw.cmd -Dspotless.skip=true test`，共运行 7,095 项，21 项跳过，3 项失败、100 项错误。失败包括公共 API 基线未包含 Folia 并发改动增加的 `synchronized` 修饰符，以及两个未修改的 `DungeonWorldFilesTest` 路径/目录替换用例。错误包括 Windows 临时路径短别名被严格路径校验识别为符号链接 69 项、目录同步失败 29 项，以及聊天监听器测试夹具未给模拟插件设置服务器 2 项。随后将现有 `starcore-api-v1.txt` 更新为反射采集到的当前公共方法修饰符，并补全插件服务器 mock；API 基线和 `ResolutionChatListenerContractTest` 共 5 项定向用例通过。首次全量结果发生在这些修正之前，未在修正后再次运行全量套件。

修正后的 Folia/调度/军队/战争/攻城选择器执行 `package`，运行 464 项，0 失败、0 错误、2 项跳过，构建成功。StarCore 包为 19,556,178 字节，SHA-256 `6D84B645268E9DFCCA7B0766B898C372E32F448CB6B317C919BF65E3B3AE8243`，与此前原生补给回执 Folia 验收使用的生产字节相同。设置独立 Windows 临时根目录后，初次 Dungeon 子集发现目录身份 `fileKey()` 为空，以及目录同步返回 `AccessDeniedException`。为这两个 Windows 文件系统行为增加兼容分支后，`*Dungeon*Test` 与 `DungeonFileDurabilityTest` 共 340 项全部通过，0 失败、0 错误、0 跳过。容器缺少原生目录身份时使用 `.starcore-world-container-id` 持久 UUID 标记；Windows 不支持打开目录同步时记为不支持并记录一次告警，其他 I/O 失败继续中止。随后 Windows Java 21 完整测试运行 7,096 项，0 失败、0 错误、21 项跳过。跳过项为未配置的 MySQL/Redis 集成、需真实玩家的叙事测试，以及已废弃的旧战争权威测试。完整验证与最新产物哈希见 [复核记录](../verification/folia-upstream-full-retest-20261122.json)。

同日按格式化后的工作树执行完整 `mvnw.cmd -B -ntp -Dspotless.ratchetFrom=HEAD verify`，Spotless、全量测试、shade 和 verify 均成功。新 JAR 为 19,557,252 字节，SHA-256 `F335AF774F8DF259A263FBF5779E9BEA4CE7A7588EF995CE993ECF292F494D2F`。CycloneDX 创建并校验了 36 个组件的 JSON/XML BOM；部分缓存的可选依赖缺少当前远端 POM 元数据，记录了解析警告，但没有使 verify 失败。

随后为 `SqlTreasuryStateStorage.writeSnapshot` 增加结构化 `SQLITE_BUSY_RETRY` 告警，记录重试次数、预算内累计毫秒和是否继续重试，不输出账户或玩家信息。`SqlTreasuryBusyRetryTest` 在四个真实 SQLite 双连接场景全部通过，并断言首次回滚后重试日志为 `attempt=1` 且 `retryAllowed=true`。含观测日志的完整 `mvnw.cmd -B -ntp -Dspotless.ratchetFrom=HEAD verify` 再次通过：7,096 项，0 失败、0 错误、21 项跳过，Spotless、JaCoCo、shade 与 CycloneDX 均成功。新 JAR 为 19,557,442 字节，SHA-256 `6BB059707AEDCFE3E7D65BAD768329E891BA7144C6DA6B1B9A1C30790E8A4B0A`。随后在 Folia 1.21.11-14 隔离副本通过独立 WAL 写连接与公开 `ArmyService.createArmy` 触发该分支；同一建军请求先记录 `retryAllowed=true` 再成功完成，测试 SQLite 重启关闭后完整性为 `ok`、Gamma 国库余额为 58504.00。测试使用 StarCore 原库的隔离克隆，原始库哈希未变；完整运行哈希、回执及清理结果见 [SQLite 忙锁 Folia 运行记录](../verification/folia-sqlite-contention-live-retry-20261122.json)。
