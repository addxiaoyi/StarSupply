# 需求与验收对应

本表区分现有实现、已验证证据和剩余验证，完整目标仍包含最初的玩法与技术约束。构建和运行结果分别以 [BUILD](BUILD.md)、[Paper 验收](PAPER-ACCEPTANCE.md) 及其带哈希的证据文件为准。

| 原始要求 | 实现与证据 | 当前边界 |
| --- | --- | --- |
| 分析并复用 StarCore、森罗物语 | [StarCore 对接](STARCORE.md)、[森罗对接](SENLUO.md)，固定两个上游提交；`StarCoreGateway` 与公开服务 | 国家、战争、货币和国库由 StarCore 管理，不通过其数据库扣款 |
| 统一战略物资、国家仓库与前线 | 六类 `ResourceType`；物品映射、完整 ItemStack 批次和 SQL 仓库；六类 Paper 采购运输闭环 | 原版与 Cookery 成品进入真实托管；每周期消耗完整物品 |
| 军队需求、消耗、短缺和后勤状态 | `ArmySupplyRules`、`CampaignRules`、`computeArmySupply`、`SupplyStatus` 与军队 GUI | 战力临时有界；失守、超距暂停消费；不追扣停服周期 |
| 原生 SupplyLine、路线、道路、敌区、截断恢复、备选 | [路线](ROUTING.md)，异步图计算、完整区块扫描、交付重新规划 | 原生线仍以 StarCore 的战争、容量和损毁状态为准 |
| 玩家商队、货物、订单、护送、风险、费用、保险、失败结算 | SQL 实物预留与承运人、独立护送队、批次固定风险、StarCore 运费和 [收入险](INSURANCE.md) | 逻辑不依赖实体持续加载；NPC 表现属于原需求可选设计；护送无独立奖金 |
| 协定、进出口、供需价格、采购、禁运、收益、关税、战时管制、区域差异 | [市场](MARKET.md) 与 `openSale`；按仓库隔离的供需参考价；StarCore 方向协定与禁运、TARIFF；Paper 已现场验收额度耗尽、到期、取消、价格不符和有效禁运拒绝分支 | 合同锁价；实际货物不能同时计入 StarCore 抽象资源库存 |
| 农业、厨师、运输和侦察参与 | Cookery 身份品质验证；[2026-11-21 Folia 隔离实服记录](verification/folia-cookery-trade-war-loop-20261121.json) 完成玩家种小麦→磨粉→和面→蒸馒头→采购→运输→前线消耗；亲临道路侦察 | 真实生产链只验收 1 件 FOOD 的一个消费周期；StarSupply 不生成免费军粮 |
| Tavern 情报交换、商人集结与真实自定义物品 | [酒馆](TAVERNS.md)、国家情报板及开放合同；[Folia 严格认证拒绝与通过记录](verification/folia-tavern-strict-auth-20261122.json) | 情报有时效、按国家隔离；上游没有自动酒馆注册表，集合点由授权玩家登记 |
| 持续作战、短缺、围城、道路失守、冬季、经济负担、公平恢复 | 有界战力与真实物资成本；前线控制/距离、道路风险、季节/围城临时需求、采购运费保费；已消费 FOOD 通过 StarCore 耐久回执结转原生补给；Folia 验收观察到六类库存满覆盖、6 个完整资源消费周期、原生补给重启重放及基础士气恢复 | 六类生存生产尚未验收；九插件 Folia 测试组合已启动，Music 键盘、MIDI、停止与断线清理已验证；生产 Folia 支持和客户端渲染未认证；围城依赖 [上游财政初始化补丁](upstream/README.md)，第三方战斗扩展聚合可能影响倍率 |
| 物资映射、运输、军需公开 API、事件、GUI/地图接口 | `SupplyApi`、`TavernApi`、`InsuranceApi`；原生补给结转查询、可取消操作事件、经济结算事件、周期消耗事件、前线军队重绑定；`routeNetwork` | 地图渲染由接口接入，未强制捆绑特定地图插件；通知不是可靠消息队列 |
| 独立 SQL、唯一结算、跨库恢复、真实库存、权限 | [事务](TRANSACTIONS.md)，主库 v11、酒馆 v1、保险 v2；StarCore 收据与唯一经济阶段；实体线程权限与操作事件；[Paper 国库付款自动重试](verification/paper-v11-spending-live-retry-20261122.json)验证了足额余额下的合成失败保留待结算状态，随后用同一凭证完成一次扣款、入账及入库 | 没有外部幂等回执的结果保持复核，不能凭当前余额自动重试或退款 |
| 异步路线、Folia 调度兼容 | 玩家使用实体调度器，后台 SQL/路线使用限流工作线程，战役采样在全局服务器任务；v11 构建在隔离 Folia 环境完成 v10→v11 迁移；2026-11-22 九插件隔离 Folia 组合已启动并通过 Music 玩家流程 | StarSupply 生产描述符仍不声明 Folia 支持；StarCore 其他模块、资源包客户端渲染和生产 Folia 部署组合仍需认证，Paper 仍是生产验收目标 |
| 自动化回归与中断恢复 | 当前 124 项 Java verify 测试、58 项 Node 验收器测试与此前 7 项 PowerShell 恢复检查通过；API 返回 future 取消/提前完成不会抑制库存锁清理。当前构建在真实 Folia 观察实体退役回调、区块卸载、区域消失和重连新区域；未扣物自动撤销，已保存回执恰好一次入库/付款，重复恢复不变；停服存档和六库完整性核验通过。见 [构建](verification/workspace-inventory-cancellation-20261125.json)、[区域恢复](verification/folia-region-retirement-20261125.json)、[六类生存续接](verification/folia-survival-six-resource-progress-20261125.json)、[熔炉与放置修复](verification/workspace-survival-resume-20261125.json)、[采购运输阶段自动化验证](verification/workspace-survival-logistics-20261125.json) | 采用隔离测试夹具固定故障边界，未声称整个售货入口的随机中断覆盖；新熔炉/放置脚本、完整插件组合、世界整体卸载 API 与六类生存生产仍需独立验证 |

第一阶段的仓库、军需、基础路线、玩家订单、真实交货与经济结算、Cookery 物品映射示例、战争效果、GUI 和持久化均有代码实现，当前提供六类资源。2026-11-21 Folia 隔离服分别验证了一件真实 Cookery 军粮从种植到前线消耗，以及 v10 Folia 构建下六类管理员准备库存交付、六个周期的六类资源消耗、FOOD 原生补给回执重放、正常重启恢复和基础士气维护。前者不代表六类生产；后者证明该库存批次的多周期消费，但不证明六类生存生产或完整 Folia 组合。2026-11-22 v11 Folia 隔离运行从 v10 数据库迁移并保留 10,470 条旧消费幂等记录，正常重启后仍通过 SQLite 完整性检查；该轮没有玩家连接。之后的 v11 指纹修复构建 Folia 玩家复测分别验证了 `PREPARED` 无回执自动撤销，以及持久回执的恰好一次结算。同日 Tavern 严格认证和 Deco/Doll 部分组合启动记录中的 725 条 Doll 行为警告，已在后续更新 Doll 构建的九插件 Folia 组合中复测为零；Music 已通过键盘、音符、MIDI、停止和断线清理玩家流程。StarCore 兼容补丁提供原生补给耐久 UUID 回执，StarSupply 已实现基于周期实际 FOOD 消耗的持久结转、重启重试及待核验恢复入口。完整目标仍取决于上表未验证的组合与验收分支。

2026-11-22 Paper 隔离运行也从 v10 升级到 v11，保留 10,470 条历史消费保护行并通过两次启动、正常停服及三库完整性检查。CraftEngine 记录 725 条未知 Doll 行为，且此次运行没有玩家连接；该记录证明迁移及生命周期路径，不覆盖交易工作流或完整插件组合。

随后以 v11 修复构建在同一 Paper 隔离环境完成两名玩家的真实铁锭托管和 5.00 采购结算。两笔订单均为 `BOUGHT`、实物转移为 `STORED`，两笔玩家余额各增加 5.00、对应国库各扣除 5.00；重复售货被拒绝，结算事件各仅一次。付款均一次成功，线上没有触发 StarCore 忙冲突重试；`INSUFFICIENT_FUNDS` 但余额仍足额的恢复分支由 `CorePaymentTest` 覆盖。正常退出并停服后的玩家存档、六个 SQLite 数据库和详细证据见 [Paper v11 付款回归记录](verification/paper-v11-payment-retry-20261122.json)。测试库存由管理员在隔离服预置，不代表生存生产。

2026-11-25 的后续隔离 Folia 验收完成了已有挖矿/烧炼进度到铁镐的续接：正常协议确认后挖出熔炉凹位、烧炼两份已有粗铁，另一次运行通过服务器确认的合成和原版成就，并在停服玩家 NBT 中保存 1 把铁镐。生产 JAR 的 105 项 verify 测试及仅覆盖描述符的隔离测试 JAR 均有哈希记录；这不代表六类资源持续生存生产或完整 Folia 组合；区域卸载已在后续独立新构建记录中验收。见 [铁镐续验](verification/folia-survival-iron-pickaxe-20261125.json)。
