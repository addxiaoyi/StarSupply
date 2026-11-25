# StarCore 对接与故障恢复

StarSupply 对接 StarCore 1.0.0 的提交 `f769e62799e769a18fe51305b545869a17f80cd1`。编译仍使用该固定公开 API；当前完整运行版本还必须应用 [兼容补丁](upstream/README.md)，其中增加公开临时士气注册和查询接口。未打补丁的旧 Core 缺少真实后勤士气能力，StarSupply 会明确拒绝启用，先前 D2BFC503 等历史包的验证不代表当前新版。安装 Java 21，启用国家、财政、军队与战争模块。StarCore 的 `economy.provider` 应设为 `internal`。外部 Vault 提供方的入账线程和持久化行为由各自实现决定，StarSupply 会在启动时拒绝启用该结算路径。

| 用途 | StarCore 服务与入口 |
| --- | --- |
| API 定位 | 从 Bukkit `ServicesManager` 获取 `StarCoreApi`，用 `service(Class)` 读取各模块注册的实例 |
| 国家与权限 | `NationService.nationOf`、`nationById`、`isFounder`、`claimAt`。库管必须是本国成员，且是创始人或 `OfficerService` 的 `treasurer` 任职者 |
| 军队与战争 | `ArmyService.getArmy`、`getNationArmies` 和 `WarService.activeWarsOf`、`getWar`。实际战斗状态取自 `WarStatus.isCombatAllowed()` |
| 原生补给线 | `BattlefieldService.getSupplyLine` 提供位置、状态、容量和效率。补给路线绑定已有 ID，读取战争参与方并跟随 StarCore 的损毁和修复状态 |
| 国库扣款与补偿 | `NationSpendingService.spend`、`spendingReceipt`、`refund`。金额和唯一凭证由 StarCore 一起持久化 |
| 玩家资金与事务记录 | 新兼容补丁提供公开 `PlayerMoneyService.applyMoney/moneyReceipt`，余额与回执一起提交；`TransactionFabric` 保存原业务键与协议。旧 `deposit/withdraw` 仍保留保守路径 |
| 战略贸易协定与禁运 | `ResourceTradeService.getTradeAgreements`、`isEmbargoed`。跨国供应合同须有匹配出口国、进口国和 StarCore 资源 ID 的有效协定，并遵守协定资源额度 |
| 战时出口管制 | 读取 `WarService.activeWarsOf` 与当前战争 `WarStatus.isCombatAllowed()`，暂停配置资源从参战出口国向其他国家供货；不另建战争或禁运账本 |
| StarCore 抽象资源价格与税务 | `ResourcePriceService` 与 `TradeTaxService` 管理 StarCore 抽象资源账目；不将抽象资源库存或价格冒充 Minecraft 实物批次与价格 |
| 战斗影响 | `BattleCalculator` 经过 `army_combat` 扩展点时，StarSupply 提供当前军队的后勤倍率。退出参战、停火或卸载插件后回到 StarCore 自有计算 |
| 冬季与围城 | `SeasonService.getSeason(World)`、`isEnabled()`，以及 `SiegeService.getNationSieges`、`getWall`。在服务线程把可变对象复制为不可变条件，再计算有上限的临时需求 |
| 承运收入保险 | `NationSpendingService` 预留/释放赔款，新 `PlayerMoneyService` 支付/返还保费，新国库入账使用 `NationCreditService.credit/creditReceipt`；`TransactionFabric` 保存原操作身份与回执协议。未标记的旧交易保留 `TreasuryService.tryDeposit` 的保守复核路径。实际赔付继续使用原准备金编号 |

原生补给线在 StarCore 中创建、截断和修复。StarSupply 路径计算读取 `SupplyLine`，库存与货物批次保存在 StarSupply 自己的 SQL 数据库。进入路线时使用国家领地查询判断敌对控制，补给效果只匹配活跃战争的敌对双方。StarSupply 战略补给应用有限的战斗伤害倍率和可撤销的临时士气贡献，不写入永久士气状态。公开 `registerTemporaryMoraleModifier` 返回注销句柄，`effectiveMorale` 是 StarCore 权威查询；反射桥仅查找公开方法，不访问私有状态或另建军队。`ArmyCombatExtensionPoint` 采用最高贡献值，同一服务器其他战斗贡献者可能覆盖原后勤倍率；士气部分在原生战斗计算中独立应用，单独限制在 ±10% 伤害变化。

StarSupply 的实物批次账本与 StarCore 的 0–100 原生军队补给仍然独立。已提交的军队周期实际消耗 FOOD 后，StarSupply 按消耗占需求比例生成最多 10 点的结转请求，并以周期稳定 UUID 调用兼容补丁新增的 `ArmyService.applyLogisticsSupply(requestId, armyId, nationId, points)`。该接口不扣国库，将原生补给变更和回执一同持久化；重复请求返回原回执而不重复加点，达到 100 上限时 `appliedPoints` 可以为 0，但请求仍会被记录。StarSupply 不使用会额外扣款且不具备请求幂等性的 `resupplyArmy(UUID)`。

当前 Folia 兼容补丁修正了原生维护换算：每分钟维护累加小时消耗余量，驻扎军队的 1 点/小时会在 60 次更新中消耗 1 点。`ArmyUnit.consumeSupply` 不再隐藏扣士气，原生补给低于 20 时 `ArmyService.updateArmy` 每分钟扣 2 点；补给恢复到 20 或以上后，每分钟恢复 1 点基础士气。士气低于 10 时仍存在随机解散机会，历史基础士气损失也不会追溯补回。补丁另外提供带耐久 UUID 回执的实物后勤补给接口，回执与军队快照一同存于 StarCore 的 army 持久化文件；回执目录不可用或损坏时接口拒绝变更。补丁的后勤补给测试覆盖幂等重放、回执持久化失败与重启恢复，结果见 [上游补丁说明](upstream/README.md)。

StarSupply 在 SQLite 中持久保存每次 FOOD 结转请求。`PENDING` 会以同一 UUID 重试，确认回执后变为 `APPLIED`；发生输入拒绝等确定错误时转为 `REVIEW` 并停止自动调用。管理员执行 `/supply reconcile-native-supply <请求UUID>` 时，插件先查询 StarCore 的耐久回执，仅匹配请求、军队、国家和请求点数后标记完成。StarCore 明确确认无回执时才会按原 UUID 重新排队；查询异常或字段不匹配会保留 `REVIEW`。军需管理者可用 `/supply native-credits <军队UUID或前缀>` 查看最近结转记录。

## StarCore 国家贸易协定

国家间实物交易使用 StarCore `ResourceTradeService` 中的**方向性、有效期内双边协定**作出口授权。固定版本提供的资源键映射为 FOOD=`wheat`、MEDICAL=`medical_supplies`、EQUIPMENT=`weapons`、MATERIALS=`steel`、ENGINEERING=`explosives`、STRATEGIC=`ammunition`。由出口国的有权成员使用 StarCore 命令 `/resource trade agreements create <进口国> <资源键> <协定单位上限> <每单位价格> [天数]` 创建协定；例如 Alpha 向 Beta 出口 16 单位军粮、单价 2.50，应由 Alpha 成员执行 `/resource trade agreements create Beta wheat 16 2.50 30`。

买方国家仍通过 StarSupply 在自己的仓库发布采购单。卖方必须是协定出口国成员；每批真实物品按映射单位数累计并占用该协定额度，采购总价必须等于协定单价乘实际交付单位数。StarCore 禁运优先拒绝交易。协定额度、卖方国家、物品单位数与协定 ID 在 `supply.db` 事务中与库存交接记录一起持久化；同时准备的订单会序列化争用额度。确认入库的单位不会再次释放额度，退款、未触碰背包而撤销的操作不会占用额度。跨国付款继续通过 StarCore 国家支出与玩家内部经济结算，不直接改动国家数据库。

StarSupply 的 `wartime-export-controls` 默认限制 `EQUIPMENT`、`STRATEGIC` 的跨国出口。检查使用出口国是否参与当前可战斗战争，出口国与买方没有交战也可能受限；仅进口国参战、本国采购或不在配置集合内的资源不会被这项规则单独拒绝。原有 StarCore 协定、禁运和敌对检查继续独立生效。将配置设为 `[]` 可关闭该规则，缺省采用上述两类；无效资源名导致启动失败。物资交接前重新校验状态，不反向修改已经确认的经济交易。此项是基于权威战况的实物出口规则，不会调用或重写 StarCore 宣战、国策或禁运服务。

StarCore 的贸易协定执行器会搬运自己的抽象国家资源；StarSupply 只读取协定和禁运，实物仍由玩家背包、批次和运输合同负责。跨国采购通过 `TaxationService` 取得进口国 TARIFF 报价：国库按毛额支出，卖家收到净额，扣留税款回到进口国国库。税费默认关闭。应用[税款回执补丁](upstream/TAX-FUNDING.md)后，新增公开 `TaxFundingService` 将完整原报价与耐久国库入账绑定，StarSupply 在保存协议后按原编号恢复新税款；不会重新收取玩家税额或按变化后的税率重算。旧无协议税款仍按独立证据使用 `/supply reconcile-tax <结算UUID> <funded|unfunded>`；新协议的人工标志不能覆盖回执。

当前 StarSupply 实物采购仅支持卖家净额为正的报价。上游税务允许 100% 税率，若实际报价税额等于毛额，本插件在 SQL 托管准备校验时直接拒绝，在此之前不会扣除卖家背包或发起国库支出。该限制避免把零净额写成上游不接受的零金额玩家经济事务；并非已经扣款后转人工核验。调整税务配置，使该笔采购保留正净额后才能供货。

StarSupply 还会读取 StarCore 当前有效国策和已解锁科技，用它们影响物流。`war_economy` 默认降低 10% 军队补给需求并增加 5% 战备系数，`military_drill` 增加 8% 战备系数；`railways` 默认提升路线效率 50%。这些国家级键值映射可在 StarSupply 配置中调整。读取快照缓存 5 秒，以限制对 StarCore 同步服务的轮询；过期国策最多可能再影响物流 5 秒。军需折减最大为 50%，战备系数加成最大为 10%，运输科技倍率最大为 150%；不修改 StarCore 的政策、科技或军队状态。若服务器使用自定义键，可在 `policy-logistics-bonuses`、`policy-combat-bonuses` 和 `technology-logistics-bonuses` 中替换映射。

冬季远征和持续围城共用同一补给周期。StarSupply 每 5 秒在 Paper 服务线程读取 StarCore 季节与攻城快照，只有可战斗战争中的敌方器械围攻匹配本国前线附近的存活城墙时才累积压力。信息 15 秒过期后使用普通需求；持续性记录保存在 StarSupply v7 的 `campaign_siege`。当周期已经消费时，SQL 保存的需求不可变，中途季节、兵力或政策变化要到下个周期才改变消耗。效果与限幅见 [战役补给](CAMPAIGNS.md)。

保险使用 StarSupply 独立产品与 `insurance.db`，不把上游依赖其他交易处理器的 `InsuranceService` 冒充本插件运输保险。赔款先用 StarCore 权威收据从国库真实预留；新保费入国库使用固定提交已经提供的 `NationCreditService` 耐久回执，无需增加上游 API。追加[玩家资金补丁](upstream/PLAYER-MONEY.md)后，新保费扣款、返还、赔付也通过余额和回执的原子提交恢复。旧无协议标记的玩家资金及国库入账仍要求独立账务证据核对，已有 REVIEW 不自动解冻。StarSupply 不直接修改 StarCore 数据库。具体合同、退保和恢复规则见 [承运收入险](INSURANCE.md)。

## 国库与玩家之间的结算

StarSupply 先在本地 SQL 记下需支付的货物或交付工作，随后用其中的 UUID 作为 `starsupply:payment:<uuid>` 向 StarCore 发起支付。网络或进程故障后的重试使用同一个 UUID 和原始金额、国家与收款人。

1. `NationSpendingService.spend` 使用唯一键扣款。重复调用会返回原始国库收据。国库余额不足时本次结算被明确拒绝。
2. `TransactionFabric` 持久化玩家入账意图；新协议保存 `playerMoneyProtocol=1`，按原结算键派生操作 UUID 并绑定收款人、方向、净额及不可变业务元数据。
3. 新 `PlayerMoneyService` 原子提交余额与终态回执；响应丢失、事务记录落盘失败或重启后均查询/重试同一请求。匹配成功回执只补本地确认；匹配拒绝回执才允许国库退款。查询失败、字段冲突或接口缺失保持冻结。
4. 旧无标记交易继续使用原布尔接口的保守恢复规则。false 可表示存储失败，不能证明未到账；操作员须有独立证据才能确认。新协议下 paid/unpaid 不覆盖权威回执；新税款通过独立回执阶段恢复，旧税款保留核验。

生产部署保留 StarCore 的国库、玩家经济、TransactionFabric 和 StarSupply SQL 数据。恢复备份时使用同一个停服快照中的四份数据，避免从不同时间点恢复后丢失关联凭证。

## Folia 约束

实验兼容包现有四份顺序补丁：后勤、玩家资金回执、税款回执、[公共反馈与 HUD](upstream/FOLIA-FEEDBACK.md)。新 Core 的线程定向 132 项与 StarSupply 实际兼容包全套 177 项通过，生产描述符仍关闭 Folia 声明，白名单外模块与明确的 HUD 偏好命令仍需继续开发。本段下方的传统调度限制描述固定原始 Core。

StarSupply 的玩家操作走实体调度器，位置相关操作使用 Paper 区域调度器适用的线程边界；季节和围城快照在全局调度器执行，并通过 `Bukkit.isGlobalTickThread()` 检查。已验证的 Paper 实现把该检查委托给相同的主 tick 线程检查，使用此 API 不代表已经完成 Folia 组合验收。当前 StarCore 版本没有 `folia-supported` 声明，其 `ArmyService` 也调用传统 `BukkitScheduler.runTaskTimer`。因此完整组合部署和验证使用 Paper。需要 StarCore 自身支持 Folia 后才能验证完整 Folia 组合，单独修改插件声明不能解决线程兼容性。

## 从固定版本构建 StarCore

在 StarCore 的独立检出中切换到上述提交，执行 `mvn -Dmaven.test.skip=true -Dspotless.skip=true install`，生成 `target/starcore-1.0.0.jar` 并安装编译依赖。若 ProtocolLib 5.3.0 的 Maven 仓库不可用，可从该项目官方发布页下载同版本 JAR，再以 `com.comphenix.protocol:ProtocolLib:5.3.0` 安装到本地 Maven 缓存。 [ProtocolLib 5.3.0](https://github.com/dmulloy2/ProtocolLib/releases/tag/5.3.0)
