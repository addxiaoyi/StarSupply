# StarCore 对接与故障恢复

StarSupply 对接 StarCore 1.0.0 的提交 `f769e62799e769a18fe51305b545869a17f80cd1`。安装 Java 21、Paper 1.21.11 和该版本的 StarCore，启用国家、财政、军队与战争模块。StarCore 的 `economy.provider` 应设为 `internal`。外部 Vault 提供方的入账线程和持久化行为由各自实现决定，StarSupply 会在启动时拒绝启用该结算路径。

| 用途 | StarCore 服务与入口 |
| --- | --- |
| API 定位 | 从 Bukkit `ServicesManager` 获取 `StarCoreApi`，用 `service(Class)` 读取各模块注册的实例 |
| 国家与权限 | `NationService.nationOf`、`nationById`、`isFounder`、`claimAt`。库管必须是本国成员，且是创始人或 `OfficerService` 的 `treasurer` 任职者 |
| 军队与战争 | `ArmyService.getArmy`、`getNationArmies` 和 `WarService.activeWarsOf`、`getWar`。实际战斗状态取自 `WarStatus.isCombatAllowed()` |
| 原生补给线 | `BattlefieldService.getSupplyLine` 提供位置、状态、容量和效率。补给路线绑定已有 ID，读取战争参与方并跟随 StarCore 的损毁和修复状态 |
| 国库扣款与补偿 | `NationSpendingService.spend`、`spendingReceipt`、`refund`。金额和唯一凭证由 StarCore 一起持久化 |
| 玩家入账与事务记录 | `InternalEconomyService.deposit` 同步持久化并发布经济事件。`TransactionFabric` 以结算 ID 记录经济请求与各阶段结果 |
| 战略贸易协定与禁运 | `ResourceTradeService.getTradeAgreements`、`isEmbargoed`。跨国供应合同须有匹配出口国、进口国和 StarCore 资源 ID 的有效协定，并遵守协定资源额度 |
| 战时出口管制 | 读取 `WarService.activeWarsOf` 与当前战争 `WarStatus.isCombatAllowed()`，暂停配置资源从参战出口国向其他国家供货；不另建战争或禁运账本 |
| StarCore 抽象资源价格与税务 | `ResourcePriceService` 与 `TradeTaxService` 管理 StarCore 抽象资源账目；不将抽象资源库存或价格冒充 Minecraft 实物批次与价格 |
| 战斗影响 | `BattleCalculator` 经过 `army_combat` 扩展点时，StarSupply 提供当前军队的后勤倍率。退出参战、停火或卸载插件后回到 StarCore 自有计算 |
| 冬季与围城 | `SeasonService.getSeason(World)`、`isEnabled()`，以及 `SiegeService.getNationSieges`、`getWall`。在服务线程把可变对象复制为不可变条件，再计算有上限的临时需求 |
| 承运收入保险 | `NationSpendingService` 预留/释放赔款，`InternalEconomyService.withdraw` 支付保费，`TreasuryService.tryDeposit` 接收保费，`TransactionFabric` 保存各笔操作身份和开始证据。实际赔付继续使用原准备金编号 |

原生补给线在 StarCore 中创建、截断和修复。StarSupply 路径计算读取 `SupplyLine`，库存与货物批次保存在 StarSupply 自己的 SQL 数据库。进入路线时使用国家领地查询判断敌对控制，补给效果只匹配活跃战争的敌对双方。原生军队现有的补给值和数值衰减仍由 StarCore 管理。StarSupply 战略补给应用有限的战斗伤害倍率，不写入永久士气状态。`ArmyCombatExtensionPoint` 采用最高贡献值，同一服务器其他战斗贡献者可能覆盖 StarSupply 的减益。

## StarCore 国家贸易协定

国家间实物交易使用 StarCore `ResourceTradeService` 中的**方向性、有效期内双边协定**作出口授权。固定版本提供的资源键映射为 FOOD=`wheat`、MEDICAL=`medical_supplies`、EQUIPMENT=`weapons`、MATERIALS=`steel`、ENGINEERING=`explosives`、STRATEGIC=`ammunition`。由出口国的有权成员使用 StarCore 命令 `/resource trade agreements create <进口国> <资源键> <协定单位上限> <每单位价格> [天数]` 创建协定；例如 Alpha 向 Beta 出口 16 单位军粮、单价 2.50，应由 Alpha 成员执行 `/resource trade agreements create Beta wheat 16 2.50 30`。

买方国家仍通过 StarSupply 在自己的仓库发布采购单。卖方必须是协定出口国成员；每批真实物品按映射单位数累计并占用该协定额度，采购总价必须等于协定单价乘实际交付单位数。StarCore 禁运优先拒绝交易。协定额度、卖方国家、物品单位数与协定 ID 在 `supply.db` 事务中与库存交接记录一起持久化；同时准备的订单会序列化争用额度。确认入库的单位不会再次释放额度，退款、未触碰背包而撤销的操作不会占用额度。跨国付款继续通过 StarCore 国家支出与玩家内部经济结算，不直接改动国家数据库。

StarSupply 的 `wartime-export-controls` 默认限制 `EQUIPMENT`、`STRATEGIC` 的跨国出口。检查使用出口国是否参与当前可战斗战争，出口国与买方没有交战也可能受限；仅进口国参战、本国采购或不在配置集合内的资源不会被这项规则单独拒绝。原有 StarCore 协定、禁运和敌对检查继续独立生效。将配置设为 `[]` 可关闭该规则，缺省采用上述两类；无效资源名导致启动失败。物资交接前重新校验状态，不反向修改已经确认的经济交易。此项是基于权威战况的实物出口规则，不会调用或重写 StarCore 宣战、国策或禁运服务。

StarCore 的贸易协定执行器会搬运它自己的抽象国家资源。StarSupply 只读取协定和禁运状态，实物仍由玩家背包、StarSupply 批次和运输合同负责，避免一份贸易同时改变两套库存。跨国实物采购通过 StarCore `TaxationService` 取得进口国 `TARIFF` 报价：国家国库按毛额支出，出口玩家收到扣除关税后的净额，税款进入进口国国库。税费默认关闭，须由 StarCore 国家税务配置启用。StarSupply 将原始税额快照和税款阶段写入独立 SQL；若外部税收结果含糊，订单与货物保持冻结，管理员核查后用 `/supply reconcile-tax <结算UUID> <funded|unfunded>` 恢复。StarCore 当前税务 API 没有单笔税收回执查询接口；配置在报价后变化或入账异常时，不能凭余额推断结果，缺少独立证据时订单继续冻结。

当前 StarSupply 实物采购仅支持卖家净额为正的报价。上游税务允许 100% 税率，若实际报价税额等于毛额，本插件在 SQL 托管准备校验时直接拒绝，在此之前不会扣除卖家背包或发起国库支出。该限制避免把零净额写成上游不接受的零金额玩家经济事务；并非已经扣款后转人工核验。调整税务配置，使该笔采购保留正净额后才能供货。

StarSupply 还会读取 StarCore 当前有效国策和已解锁科技，用它们影响物流。`war_economy` 默认降低 10% 军队补给需求并增加 5% 战备系数，`military_drill` 增加 8% 战备系数；`railways` 默认提升路线效率 50%。这些国家级键值映射可在 StarSupply 配置中调整。读取快照缓存 5 秒，以限制对 StarCore 同步服务的轮询；过期国策最多可能再影响物流 5 秒。军需折减最大为 50%，战备系数加成最大为 10%，运输科技倍率最大为 150%；不修改 StarCore 的政策、科技或军队状态。若服务器使用自定义键，可在 `policy-logistics-bonuses`、`policy-combat-bonuses` 和 `technology-logistics-bonuses` 中替换映射。

冬季远征和持续围城共用同一补给周期。StarSupply 每 5 秒在 Paper 服务线程读取 StarCore 季节与攻城快照，只有可战斗战争中的敌方器械围攻匹配本国前线附近的存活城墙时才累积压力。信息 15 秒过期后使用普通需求；持续性记录保存在 StarSupply v7 的 `campaign_siege`。当周期已经消费时，SQL 保存的需求不可变，中途季节、兵力或政策变化要到下个周期才改变消耗。效果与限幅见 [战役补给](CAMPAIGNS.md)。

保险使用 StarSupply 独立产品与 `insurance.db`，不把上游依赖其他交易处理器的 `InsuranceService` 冒充本插件运输保险。赔款先用 StarCore 权威收据从国库真实预留；没有可重放收据的保费扣款、入国库或玩家赔付在结果不明时冻结，要求独立账务证据核对。StarSupply 不直接修改 StarCore 数据库。具体合同、退保和恢复规则见 [承运收入险](INSURANCE.md)。

## 国库与玩家之间的结算

StarSupply 先在本地 SQL 记下需支付的货物或交付工作，随后用其中的 UUID 作为 `starsupply:payment:<uuid>` 向 StarCore 发起支付。网络或进程故障后的重试使用同一个 UUID 和原始金额、国家与收款人。

1. `NationSpendingService.spend` 使用唯一键扣款。重复调用会返回原始国库收据。国库余额不足时本次结算被明确拒绝。
2. 把国库收据已确认写入 `TransactionFabric`，并在调用 `InternalEconomyService.deposit` 前把 `starsupply:payment:<uuid>:player` 持久化为 `RESERVED`。玩家账户以 UUID 标识，断线不阻断入账。
3. 正常返回且已同步持久化的玩家入账可以重放本地确认，StarCore 不再重复付款。当前 InternalEconomyService.deposit 仅返回布尔值；false 同时涵盖校验/扩展拒绝与存储故障，存储异常不能证明数据库未提交，因此 false 和异常都会进入人工核对，绝不据此自动退款。
4. 如果玩家记录仍为 RESERVED（包括存款异常、返回 false 或重启），玩家余额写入的结果无法仅凭现有 StarCore API 推断。结算进入复核，库存与支付都暂停推进。操作员应根据经济审计与事故记录查实玩家是否入账。有独立证据确认入账时记录成功，有证据确认未入账时对原凭证执行退款。无法确认时保留待复核状态。单独比较当前余额无法证明是否入账，因为其他交易也会影响余额。

生产部署保留 StarCore 的国库、玩家经济、TransactionFabric 和 StarSupply SQL 数据。恢复备份时使用同一个停服快照中的四份数据，避免从不同时间点恢复后丢失关联凭证。

## Folia 约束

StarSupply 的玩家操作走实体调度器，位置相关操作使用 Paper 区域调度器适用的线程边界；季节和围城快照在全局调度器执行，并通过 `Bukkit.isGlobalTickThread()` 检查。已验证的 Paper 实现把该检查委托给相同的主 tick 线程检查，使用此 API 不代表已经完成 Folia 组合验收。当前 StarCore 版本没有 `folia-supported` 声明，其 `ArmyService` 也调用传统 `BukkitScheduler.runTaskTimer`。因此完整组合部署和验证使用 Paper。需要 StarCore 自身支持 Folia 后才能验证完整 Folia 组合，单独修改插件声明不能解决线程兼容性。

## 从固定版本构建 StarCore

在 StarCore 的独立检出中切换到上述提交，执行 `mvn -Dmaven.test.skip=true -Dspotless.skip=true install`，生成 `target/starcore-1.0.0.jar` 并安装编译依赖。若 ProtocolLib 5.3.0 的 Maven 仓库不可用，可从该项目官方发布页下载同版本 JAR，再以 `com.comphenix.protocol:ProtocolLib:5.3.0` 安装到本地 Maven 缓存。 [ProtocolLib 5.3.0](https://github.com/dmulloy2/ProtocolLib/releases/tag/5.3.0)
