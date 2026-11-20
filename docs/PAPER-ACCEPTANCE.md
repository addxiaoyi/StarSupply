# Paper 游戏内验收

使用独立的 Paper 1.21.11 测试服与 Java 21。把对应版本的 StarCore 和 `target/starsupply-0.1.0.jar` 放入 `plugins/`，确认二者均启用。StarCore 使用 `economy.provider: internal`，由它管理真实国库和玩家余额。要验收自定义军粮，再安装兼容的 CraftEngine、森罗物语 Cookery 及对应资源包。客户端需要接受资源包才能看到自定义物品模型。

准备军需官、厨师、运输者三个账号。为三者授予 `starsupply.use`，为军需官授予 `starsupply.manage` 并在 StarCore 拥有本国管理权限。用两个国家进行战争验收。下列 StarCore 命令可用于测试服准备，前置费用和权限以 StarCore 反馈为准。

```text
/nation create Alpha
/nation claim
/starcore treasury deposit Alpha 1000
/army create infantry 10
```

先在初始区块附近执行 `/nation create Alpha` 与 `/nation claim`。只能在 StarCore 已占领区块登记仓库或前线站，故移动到其他区块后要再次执行 `/nation claim`。按这版命令实现，`/nation create` 直接经 StarCore 的 `NationLifecycleCommand` 建国；国库入款可依服务器实际命令和权限操作。

从 StarSupply 的军队菜单记录完整军队 UUID。StarCore 的 `/army list` 仅显示 UUID 前缀。用第二国的元首创建 Beta、领取军费并占领测试区，再让其中一支敌方军队处于 StarCore 战斗范围。用服务器该版本 `/war help` 显示的正式战争命令开始战争，并等待 StarCore 将战争置为 `ACTIVE`；仅处于准备期时补给不会消耗、战力系数不会变化。正式服应使用正常宣战流程和准备期。

为快速重复验收，可在**隔离测试服**安装一次性辅助插件：它应通过 `StarCoreApi.service(WarService.class)` 取得固定版本的 StarCore 战争服务，再对由正常宣战流程创建的战争调用该版本 `WarServiceImpl.startWar(warId, aggressorNationId)`，让 StarCore 自己执行生命周期并发布战争事件。此内部实现入口只是测试夹具，不能作为 StarSupply 生产依赖；生产插件不包含该辅助插件，也不绕过 StarCore 战争状态。

## 军粮到前线

下方先保留旧版四类资源 Paper 实测流程和结果，再记录当前六类版本的实测。当前实现增加 ENGINEERING、STRATEGIC 两类，并将六类都计入军队补给；旧版“四类齐备即 100%”只适用于当时的四类版本。

| 操作者 | 操作 | 预期结果 |
| --- | --- | --- |
| 军需官 | 在 Alpha 已占领地内选两个位置。在仓库位置执行 `/supply depot alpha-depot`，到前线位置执行 `/supply front alpha-front <军队UUID>`。 | GUI 可见两个地点，前线绑定同一支 StarCore 军队。 |
| 军需官 | 执行 `/supply route alpha-road alpha-depot alpha-front 1.0`。 | 路线菜单显示可通行道路。两个地点需要在同一世界，并尽量隔开几百格。 |
| 厨师 | 用 Cookery 蒸锅配方制作馒头 `kaleidoscopecookery:mantou`。核对 CraftEngine 物品 ID 与 Cookery 品质。可先用 8 个原版面包验证基础功能。 | 厨师背包确实持有 8 件匹配 `FOOD` 的物品。隔离服也可由管理员执行 `/ce item give <厨师名> kaleidoscopecookery:mantou 8` 准备真实 Cookery 物品；这只是验收准备方式。 |
| 军需官 | 分别靠近仓库执行 `/supply buy alpha-depot food 8 40`、`/supply buy alpha-depot medical 8 40`、`/supply buy alpha-depot equipment 8 40`、`/supply buy alpha-depot materials 8 40`，记录采购 UUID。 | `/supply orders` 显示待售订单。每个 40 是采购总价，确认国家国库余额足够。 |
| 厨师 | 到仓库执行 `/supply sell <采购UUID>`，或点击订单对应动作。 | 8 件物品从背包交接至国家仓库。结算完成后，厨师余额增加 40，国库扣除 40，仓库显示实际补给单位。 |
| 军需官 | 每张已付款的采购单执行 `/supply ship <采购UUID> alpha-front 15`，记录运输 UUID。 | 原仓库分配该批实物给运输订单，库存由可用转为预留。 |
| 运输者 | 到仓库执行 `/supply accept <运输UUID>`，按指引到前线，等待运输计时完成，执行 `/supply deliver <运输UUID>`。 | 交付成功后运输者得 15，国库扣 15，原仓库预留清除，前线收到同一批物资。 |
| 军需官 | 这项历史验收只送达四种原始资源：FOOD、MEDICAL、EQUIPMENT、MATERIALS。用 `/supply status <军队UUID>` 与军队菜单查看补给，并检查 `supply.db` 中 `item_lot`、`shipment`、`settlement` 和 `consumption`。 | 该次测试中的 10 人军队每周期对这四类各需求 1 单位；8 个馒头折算 16 FOOD 单位，其余三类各有至少 8 单位。ACTIVE 战争中显示 100% 覆盖和 ×1.050。对应 PURCHASE 与 TRANSPORT 结算为 `PAID`，运输为 `DELIVERED`。 |
| 军需官 | （旧版四类验收）等到下一个配置周期边界（测试建议 300 秒），再次查看状态和数据库。 | 旧版记录中 `consumption` 为该军队和周期记录四种资源各消耗 1 单位，前线可用批次数量相应减少；由于当时的四类需求库存仍足，覆盖与 ×1.050 上限维持不变。当前六类版本的需求与覆盖须按六类复测。若清空库存，补给系数应回到下限 ×0.850，不会叠加或永久惩罚。 |

当前实现另外支持 `ENGINEERING` 和 `STRATEGIC` 两类；默认原版映射分别为 `scaffolding → ENGINEERING` 和 `firework_rocket → STRATEGIC`。这两类已纳入下方六类版本记录。原四类映射为 `bread → FOOD`、`honey_bottle → MEDICAL`、`iron_ingot → EQUIPMENT`、`cobblestone → MATERIALS`。Cookery 增加的单位数与品质规则以 `plugins/StarSupply/config.yml` 的 `resource-mappings` 为准。对比交接前后的品种和品质，退款时确认原物品组件完整恢复。补给按完整实物逐件消耗；单件折算单位高于剩余需求时会消耗整件，作战覆盖只计入需求上限。生产映射可选择更小的单位数，避免出现明显的剩余浪费。

### 当前六类版本的完整验收操作

在上面的历史四类操作后，继续分别为 `engineering`、`strategic` 发布 8 件、总价 40 的采购单。使用默认映射时，工程物资由 `scaffolding` 供货，战略物资由 `firework_rocket` 供货；各完成一次出售、运输和交付，并等待一个消耗周期。10 人军队六类需求各为每周期 1 单位；库存齐全时覆盖应达到 100%、战力应达到 ×1.050。上方六类版本实测已按此闭环完成。

## 异常与复测

1. **并发。** 两个厨师同时提交同一采购 UUID，再由两个运输者同时认领同一运输 UUID。每次只有一方成功，失败方没有库存变化。两人分别记录真实余额。
2. **重复交付。** 用同一运输 UUID 重发交付命令。确认余额、库存和结算数量只变化一次。重启服务器后再重复一次。
3. **断线。** 认领后退出游戏，再上线完成运输。同一个 UUID 应保留认领状态和计时。补测物资交接期间断线，重登后使用 `/supply recover [交接UUID]`，核对背包和银行余额仅有一次变化。强制停止进程后如果交接收据缺失，应进入待核验状态。
4. **资金不足。** 使用余额不足的测试国库提交一笔采购。确认被扣押物品可以用 `/supply recover` 完整取回，重复恢复不会多领。用满背包重试一次，再腾出空间确认能够恢复。
5. **取消与过期。** 用 `/supply cancel shipment <运输UUID>` 取消未结算的运输。测试另一单超时。两次都应解除起点库存预留，且不发运输款。另测 `/supply cancel purchase <采购UUID>` 撤销尚未预订的采购。
6. **道路。** 认领后执行 `/supply block alpha-road true`。无替代道路时无法沿封锁线路交付。若已有替代道路，应采用新路线和相应计时。执行 `/supply block alpha-road false` 后可再规划。绑定 StarCore 供给线的测试可用 `/supply route <路线ID> <起点> <终点> <效率> <供给线UUID>`，并通过 StarCore 改变该线状态。
7. **权限与位置。** 无权限玩家尝试建仓，其他玩家尝试交付自己的订单，运输者在错误地点和过早时间尝试交付。核对失败不会产生结算。打开菜单后测试背包快捷键、拖放和 Shift 点击，界面物品应留在菜单中。
8. **重启核验。** 至少在采购结算完成后、运输进行中和交付后分别执行正常停服再启动。现场记录订单状态、玩家及国库金额、仓库可用与预留数。在隔离副本测试进程中断时，也应核对 `item_transfer` 与 `settlement` 的状态及结算 UUID。使用只读 SQLite 查询核对。
9. **跨国协定。** 在 Beta 作为出口国、Alpha 作为进口国的配置中，由 Beta 有权成员执行 `/resource trade agreements create Alpha wheat 16 2.50 30`。Alpha 在仓库发布两张各 8 个原版面包的 `food` 采购单、每张总价 20；Beta 成员到 Alpha 仓库售出。默认面包每件 1 单位，两个合同累计 16 单位恰好用完额度。StarCore 禁运、协定取消/到期、金额不等于协定单价乘实际映射单位或再次超额时都拒绝。查看 `item_transfer.trade_agreement`、`agreement_limit` 与 `trade_units`，并验证重启后额度还在。Cookery 馒头每件 2 单位，不能把其件数和面包的协定单位混用。
10. **区域市场。** 在两个采购仓执行 `/supply market <仓库ID> food`。同一仓库发布一笔 `food` 采购后再次查询，报价应上升；另一仓库报价只受自己的库存与订单影响。用 `/supply buy <仓库ID> food 8 market` 创建采购，核对 `purchase.price` 等于创建时参考价乘 8。随后取消合同，参考价按剩余库存与需求重新计算；已创建合同金额保持锁定。
11. **商队护送和风险结算。** 创建绑定敌区路线的运输单；国家成员在起点使用 `/supply escort <运输UUID>`（最多 4 名）加入，再由承运者领取。核对 GUI 和 SQLite 持久化护送者、路线风险与承运状态，重启后使用同一路线确认没有重新抽风险。每名护送者最多减少 10% 路线风险与 5% 运送时间，总量分别不超过 40% 和 20%。护送者没有独立奖金；成功运输费仍支付给承运者。对路线风险 0 的运输，交付应正常结算运费；高风险失败时货物回到起点、运输为 `FAILED`、不产生运费结算。v8 应另外保存 `failure_reason=RISK` 与原始 `failed_at`，超时保存 `EXPIRED`；保险按第 15 项独立验收。

12. **关税与迁移恢复。** 将隔离数据库从已备份的 v5/v6 副本升级，确认当前 `user_version=8` 且历史订单可读；历史 v6 包的迁移结果应单独标注为 v6。通过 StarCore 税务配置启用进口国的非零 `TARIFF`，创建有效方向贸易协定和跨国实物采购。检查买方国库按毛额扣款、卖家收到净额、进口国收到税款，且 `taxAssessment`/`settlement_tax` 保存同一结算 UUID、税种、毛额、税额及阶段；重启后再次确认不重复付款。用故障注入在 StarCore 税款入账调用边界中断，验证交易进入 `REVIEW` 且货物未开放领取；根据 StarCore 独立税收记录分别测试已缴的 `funded` 确认与确未缴的同报价补缴路径。测试应确认重复命令、重启和并发操作都不会再次给卖家付款或重复交付物资。

13. **冬季与围城。** 通过 StarCore 正常季节服务把前线世界设为冬季，下一消费周期应按 `campaign` 增加粮食/医疗整数需求；在周期中途切回春季，已保存需求不能改写或重复扣物，下个周期恢复普通需求。通过 StarCore 创建真实敌方攻城器械与本国城墙并开始围攻，匹配前线半径后状态展示持续秒数，额外需求逐步升高且不超过配置上限。重启、解除、停战、过期采样应分别验证短时连续性或正常恢复。隔离服可缩短爬升时间到允许的 60 秒，不能直接写 StarCore 数据库模拟攻城。

14. **酒馆与情报。** 军需官在本国领土登记集合点，侦察兵在道路 32 格内取得报告，返回酒馆 8 格内发布，另一位本国玩家用 `/supply board` 查看；远处侦察、异国读取、异国发布与失守集合点均拒绝。重启后报告保持观察时间，重复发布及删除后换点发布不延长到期时间；过期报告消失。开启真实 Tavern 验证后，普通物品与熟成不足酒品不得登记，符合公共目录的真实酒品可以登记且不被消耗。

15. **承运收入险。** 本国承运者在出发前查询报价并投保，检查国库真实预留固定赔额、玩家真实支付保费、国库真实收到保费，全部确认后保单才为 `ACTIVE`。零风险、异国承运者、已失败批次重复投保均拒绝。成功运输或取消时只释放原准备金，生效保费保留；投保 5 分钟未领取也释放准备金。实际遭袭仅产生不超过 80% 运费的独立赔款，实物仍按原规则退回起点，超时不赔。重启后用原始 RISK 时间恢复理赔；在保费或赔款外部调用边界注入故障，验证 `REVIEW` 冻结、无重复扣费/赔付，并用独立账务证据执行 `/supply reconcile-insurance <保单UUID> <经济阶段> <applied|untouched>`。低风险投保后改走高风险道路必须拒绝保险交付，先退保才能继续。

```sql
SELECT id, kind, state, amount FROM settlement ORDER BY updated;
SELECT purchase, state, player, return_id, note FROM item_transfer ORDER BY created;
SELECT id, state, origin, target, ready, expires FROM shipment ORDER BY created;
SELECT id, failure_reason, failed_at FROM shipment WHERE state='FAILED';
SELECT army, slot, resource, demand, units FROM consumption ORDER BY rowid DESC LIMIT 32;
```

`consumption.slot` 按配置周期切分 Unix 时间；若验收期间重启时更改过周期，不能直接把不同长度周期的 slot 数值当作时间排序。按插入顺序读取最近记录，或按当前配置周期过滤。

## 旧版四类资源的 Paper 实测记录

2026-11-20 在 Paper 1.21.11、固定 StarCore 1.0.0 和 `supply-interval-seconds: 300` 的隔离服务器中完成闭环。Alpha 对 Gamma 由 StarCore 战争生命周期进入 `ACTIVE`。厨师提交 Cookery 馒头，另三种原版资源分别使用蜂蜜瓶、铁锭和圆石；每种物资均经历国家采购、库存预留、承运交付。4 笔 `PURCHASE` 与 4 笔 `TRANSPORT` 均由 StarCore 结算为 `PAID`，运输状态为 `DELIVERED`。军队状态显示“战时 / 补给 100% / 作战 ×1.050”。SQLite 后续周期记录 FOOD、MEDICAL、EQUIPMENT、MATERIALS 各消耗 1 单位，剩余前线库存仍足以维持上限。

## 六类资源最新 Paper 实测记录

2026-11-20 在 Paper 1.21.11、固定 StarCore 1.0.0 隔离服验收。六类版本首次部署时，现存 user_version 2 数据库成功迁移至 v5；当时确认并移除了重复的旧版嵌套 JAR，确保加载六类构建。路线风险修复后，最终 JAR（SHA-256 `2C1BB879B7CDAF6C93BFA01C5190669E8BB656DE6D9EF7D45FEF5E7F10C00B6D`）再次启动通过，并以此包重跑了下方六类闭环。GUI 能显示仓库、订单和运输状态。

Alpha 对 Gamma 的战争已处于 `ACTIVE`。为 10 人军队绑定前线站后，使用真实 Cookery 馒头、蜂蜜瓶、铁锭、圆石、脚手架和烟花火箭，各完成一次国家采购、玩家实物托管、承运领取、前线交付及 StarCore 结算；6 笔 `PURCHASE` 和 6 笔 `TRANSPORT` 均为 `PAID`，全部运输为 `DELIVERED`。军队状态显示“战时 / 补给 100% / 作战 ×1.050”。SQLite 记录六类资源每周期需求 1 单位且本周期六项均消耗 1 单位；交付后的剩余前线库存每类均可继续满足下一周期。该记录验证了六类物资映射、真实物品与经济闭环以及 GUI 入口；同一隔离服数据库另外实测由 v2 迁移至 v5。该次 v5 验收尚未覆盖动态市场、跨国协定和护送；后续 v6 复验单独记录如下。

StarCore 跨国协定准入、价格和额度已有自动化测试覆盖。后续 v8 记录验证了有效方向协定下的跨国采购与非零关税；第 9 项的额度耗尽、到期、取消与禁运拒绝分支仍需单独现场复测。

路线风险区块完整扫描与 4096/4097 区块边界已有自动化测试。StarCore 科技路线修正和国策军需/战备修正已在 Paper 实测：给 Alpha 解锁 `railways` 后路线 GUI 显示道路效率 1.50；激活 `military_drill` 后 10 人军队状态由 ×1.050 提高到 ×1.130。旧版四类与当前六类的实时验收均已分别记录，不应混用各自的补给需求结果。

## v6 迁移、市场与护送复验

2026-11-20 在同一隔离 Paper 服备份后部署 SHA-256 `FE9F44FEDB50B6358B7D760DF45F0545F84F02963ED7FBBBF5FAAA0BCA458532` 的 v6 构建。启动成功，主数据库 `user_version` 从 5 迁移为 6。再次以六种真实物品（含 Cookery 馒头）完整执行采购、托管、运输和交付；新增 6 笔 `PURCHASE` 与 6 笔 `TRANSPORT` 为 `PAID`，10 人军队状态为补给 100%、作战 ×1.130。

同次市场复验：FOOD 参考价由 2.50 随 8 件开放需求升至 3.33；按市场建单的总价锁为 20.00，供货后仓库库存 8 件，参考价降至 1.67，历史合同总额保持 20.00。一名国家成员加入护送后数量为 1/4，Beta 承运者完成交付，重复交付仍返回 `DELIVERED`，没有第二笔支付；订单 GUI 可正常渲染。该结果只证明已执行的市场、护送与重复交付路径，高风险失败、非零关税和后续 v8 扩展需各自记录。

## v8 关税、酒馆和冬季复验

2026-11-20 完整 `mvn verify` 通过 61 项测试，0 失败、0 错误，生成历史构建 SHA-256 `B380D7688DDB8E3ED472A4485D5B63FAAAB5A9CC163A7EF078586D3C41478D9F`。该包在上述隔离服成功把主库 v6 迁移至 v8，并创建独立的 `tavern.db` 与 `insurance.db`。为缩短验收等待，仅在隔离服临时设置补给周期 30 秒、围城爬升 60 秒；结束后恢复原 300 秒周期与默认战役配置。历史四类、v5、v6 和本次 v8 的需求记录须按各自周期解释。

通过公共 `TaxationService.setTaxConfig` 启用 Alpha 的 10% `TARIFF`，再由公共 `ResourceTradeService.createTradeAgreement` 创建 Beta 出口、Alpha 进口的 `wheat` 协定。Beta 玩家真实交付 8 个面包，合同总价 20.00；卖家余额由 4837.86 增至 4855.86，Alpha 国库由 4015.00 变为 3997.00，符合毛额支出 20.00、税款回流 2.00、卖家净收 18.00。公共税收历史记录 2.00，StarSupply 对应采购为 `BOUGHT`、库存为 `STORED`、结算为 `PAID`、税款阶段为 `FUNDED`，`SupplyApi.taxAssessment` 返回相同报价。重复售货被拒绝，未再次支付。测试只调用公共服务设置隔离世界和经济条件，没有直接修改 StarCore 数据库。

Alpha 元首在本国领土登记 `alpha-inn`，于道路 32 格内生成侦察报告并返回集合点发布。情报板 GUI 显示观察时道路开放、科技修正后效率 1.50、风险 0%，到期时间保持观察时起 15 分钟；地点与报告均写入 `tavern.db`。重启后地点仍可打开，原报告到期后自动隐藏。本次未安装可选 Tavern 插件，因此只验收通用集合点与情报玩法，真实森罗酒品认证仍待补测。

将世界时间调至第 21 游戏日，并通过 StarCore 季节命令设为冬季；状态在下一次采样后显示“冬季”，后续周期中 10 人军队的 `FOOD`、`MEDICAL` 整数需求分别为 2，其他四类为 1。恢复第 7 日与夏季后，下个周期恢复六类各 1，并移除冬季标记。库存耗尽时战力仍为有界的 ×0.930（包含该国 8% 战备加成），没有永久衰减。现场尝试通过公共 `SiegeService.createSiege` 创建真实敌方攻城器械，但固定 StarCore 抛出 `SiegeServiceImpl.treasuryService == null` 的异常，故围城爬升与解除尚未获得现场证据；不使用反射、可变单位注入或直接数据库写入绕过它。

## v8 保险、基础闭环与重启复验

B380 历史包发现普通未投保运输误调用保险出发超时检查，导致普通领取被拒绝。修复保护条件后，于 2026-11-20 12:03 执行 `mvn -DskipTests package` 成功，生成历史验收包 SHA-256 `465003F6E8E6F9B1F3F6984DF3292CF34D417876C740D009473275DE9B30A05E`。此命令只重新编译打包；61 项测试是前一次完整验证结果，不能称为这次修复后的重新测试。此后源码的其他修复仍须重新完整构建并按新产物复验。

隔离服使用公共领土服务建立经过四个区块、其中一个由交战敌国控制的道路，风险为 25%。本国承运者为已有真实面包批次投保：运费 15.00、保费 4.20、赔额 12.00；国库预留、玩家保费扣款及国库保费入账均确认为 `APPLIED` 后保单进入 `ACTIVE`。重复投保返回同一保单。领取并等待 5 秒以上后在目的地交付，持久批次风险值 0.11522079981485067 触发遭袭；运输保存 `FAILED`、`failure_reason=RISK` 和原始 `failed_at`，实物退回起点、不生成运费支付，保单成为 `CLAIMED` 且赔款阶段 `APPLIED`。重复交付被拒绝。

同一修复包再次完成六类真实物品采购与普通未投保运输闭环：新增 6 笔 `PURCHASE`、6 笔 `TRANSPORT` 均为 `PAID`，全部运输 `DELIVERED`，前线军队补给 100%、作战 ×1.130。这验证了普通领取保护条件的现场修复，也涵盖 Cookery 馒头与五种原版映射。

恢复原配置后正常停服重启，保险仍为 `CLAIMED`，原经济阶段 UUID 和最后更新时间未改变；税款仍为 `FUNDED`。再次投保、交付失败运输和重复售货均被拒绝。Alpha/Beta 国库与出口玩家余额在重启前后保持 2659.20/9000.00/4945.86。验收服最终正常停止，测试探针移出 `plugins`。StarCore 的公共税收历史在重启后为空，不能将该内存历史作为重启后唯一核账来源；需事先保留独立经济凭证。

## 前线控制、承运护送与周期事件回归

2026-11-20 后续完整 `mvn verify` 通过 69 项测试，0 失败、0 错误，生成 SHA-256 `939C804E2EEE24ECB8BB546D316493150F1D291F1EEB739BA7051FA03B7BCE78`。隔离服备份后成功启动该包，保险库从独立 schema v1 升级为 v2，原已赔付保单的 ID、状态、经济阶段及时间戳保留；新增 `activated` 字段存在。再次完成六类真实物资闭环，新增 6 笔采购、6 笔运费均为 `PAID`，前线补给 100%、作战 ×1.130。

同一国家玩家先加入护送再领取自己的运输：领取成功后 SQL 删除其本人的护送记录，护送者列表为空，运输按普通承运完成。前线半径回归通过公共 `ArmyService.moveArmy` 分段移动军队至前线 256 格以外，状态为 `ARMY_OUT_OF_RANGE`、补给覆盖 0；跨越一个 30 秒测试周期后，该军队消耗行数与前线真实物资单位均未变化。通过公共 `NationService.unclaimCurrentChunk` 临时失去前线区块时，状态为 `CONTROL_LOST`，同样没有新消耗或库存扣减。恢复原军队位置和本国领土后状态回到 `CONNECTED`，后续周期重新消费并恢复库存允许的覆盖。测试最后还原领土、军队与原 300 秒配置并正常停服。

测试探针订阅 `SupplyConsumptionEvent`，该次运行观察到 38 个不同的结算 UUID，每个只发布一次，事件包含军队、周期、需求和实际消耗；后台每秒轮询未重复发布。该现场结果覆盖本次进程内的周期去重，重启后同一周期不再扣物的持久幂等另由生命周期测试覆盖。脱敏回归记录见 [939C 运行证据](verification/2026-11-20-paper-939c-regression.json)。

## 经济调用故障与真实酒品复验

后续隔离服安装独立故障探针，只代理公开的 StarCore 服务调用；原始返回值与前后国库余额写入强制刷盘的独立 JSONL 证据，不写入业务数据库。`tax-after` 在真实 `fundTax` 返回 2.00 后丢弃返回值，采购、交接、支付与税款冻结为 `REVIEW`；核对独立证据后执行 `reconcile-tax funded`，最终分别为 `BOUGHT`、`STORED`、`PAID`、`FUNDED`，重复核对被拒绝。`tax-before` 在委派前中断，证据为 `delegate_called=false`；执行 `unfunded` 后按原报价补缴成功，卖家净额只发一次。

保险 `premium-after` 在国库真实收到 4.20、`tryDeposit` 返回 true 后丢弃返回值，`PREMIUM_CREDIT` 进入 `REVIEW`，运输领取与取消均拒绝。核验独立证据后执行 `reconcile-insurance ... premium_credit applied`，保单成为 `ACTIVE`，记录实际生效时间；重复核对拒绝，正常退保后只释放原准备金。`premium-before` 则在调用前中断；根据未调用证据执行 `untouched` 后，保单关闭、已扣玩家保费退还一次、准备金释放一次。

精确固定提交 `7a676978f9c4a825275a9f905c3f860ac05a6827` 的 Tavern JAR 实测时，临时要求真实酒品且熟成等级至少 1。空手和普通 CraftEngine brandy（等级 0）登记被拒绝；通过 Tavern 公共 `catalog`、`BrewBatch`、`bottle` 生成的等级 2 真实酒品登记成功，持久 `verified=1`。同次注册前后手持品序列化 SHA-256、等级 2 与数量 1 均相同，物品未被消耗或改写。依赖哈希、四笔故障恢复和酒品证据见 [故障与酒品记录](verification/2026-11-20-paper-faults-tavern.json)。

本轮测试 StarCore 修正依赖后可创建攻城单位，但 `startSiege` 仍因未部署返回 `OUT_OF_RANGE`；公共 `deploySiege` 进入 `DEPLOYING` 后，固定源码缺少完成转为 `READY` 的调用。因此围城消耗爬升尚未通过，不以可变单位或直接数据库注入绕过。另一次等待重启的税款探针在创建采购前即停服，没有生成测试交易，不计为跨重启待核验通过。战时出口控制测试亦因 Beta 仓库选址不在本国领土而未执行到控制分支。

隔离服于 13:36:25 正常停止，配置还原为测试前 300 秒补给周期且无临时 campaign 覆盖。上述证据仍只覆盖实际列出的路径；待完成范围包括围城、待核验期间重启恢复、插件关闭/世界卸载、贸易拒绝分支及后续源码修复后的最终构建，不代表完整开发目标完成。

## 上游部署修复后的围城与真实战斗

后续在独立 StarCore 检出中修复围城模块财政依赖及正常部署完成路径；原分钟更新会把仍存活、有部署位置的 `DEPLOYING` 单位转为 `READY`，不通过反射或直接状态注入。组合补丁的 20 项针对性测试通过，隔离服安装 SHA-256 `676CF4E4D6AE52183AA80DB13A6E4A2E486A1AF93AE965A0176B08108C42AFC3`，StarSupply 仍为历史 939C 包。通过公共服务创建并部署敌方弩炮，等待原生更新进入 `READY`，再用公共 `startSiege` 进入 `BESIEGING`；军队状态显示持续围城。临时 60 秒爬升配置下，64 秒时额外需求达到 50% 上限，粮食、建材、工程需求各为 2，其他三类为 1。公共撤退后围城条件清除，下一消费周期六类恢复为 1。该结果证明的是安装组合上游补丁后的行为，不能套用到原始 StarCore JAR。

真实战斗探针在战时、后勤覆盖 0、国策战备 8% 的健康军队上调用公开预测与实际 `ArmyService.attack`。移除后勤贡献者时基础伤害为 30.0，恢复贡献者后为 27.9，比值为 0.93，与后勤战力倍率一致；实际战斗返回 27.9，敌军生命从 1000.0 降至 972.1。双方均未阵亡，探针核对真实生命变化、持久化战斗，再通过公共快照恢复接口还原双方完整状态并持久化。GUI 数字并非本次战斗效果的唯一证据。探针原始 JSON 的静态源码说明写有其编译时 344031 兼容包哈希；本次实际运行 JAR 已独立核对为上方 676CF4 组合包，脱敏证据明确区分两者。

战时出口控制另行完成现场拒绝分支：Alpha 正与 Gamma 作战，已存在 Alpha 向中立 Beta 出口 weapons 的有效协定；Beta 在本国领土发布 8 件装备采购后，Alpha 玩家携带真实铁锭供货，交易因战时装备出口管制被拒绝。采购保持 `OPEN`，对应库存交接数量为 0，未托管物品或生成支付。证据见 [围城与战斗记录](verification/2026-11-20-paper-siege-combat.json)。

## 最终全局线程检查与待核验重启恢复

2026-11-20 14:29 的完整构建通过 69 项测试，0 失败、0 错误，最终 StarSupply SHA-256 为 `870759C7FE4DA4A128C2BAA60444B7D55EC89B9292CA6CF653C13CCC90F44670`。这次仅将战役采样的线程断言改为原生 `Bukkit.isGlobalTickThread()`；最终包在 Paper 正常启动并产生新的全局季节/围城采样及一次唯一军事周期事件。此前 939C 的玩法记录为历史行为证据，最终包新线程边界则由本次启动和采样直接验证。

重新执行真实跨国采购并在实际税款 2.00 已入国库后丢弃返回值。该笔 `5c76c5d9-1409-4cf9-b673-39235c1a668e` 已在独立探针中保存原始成功回执后，采购、交接、税款与支付均确认冻结在 `REVIEW`，再正常停服。下一次使用最终包启动前移除故障探针、Tavern 和战斗探针 JAR，恢复原 300 秒配置，仅留下用于公共查询和管理员命令的验收探针。重启后四项仍为 `REVIEW`，原更新时间未变化；不存在故障插件，公共税收历史为空，系统没有自行补缴或再次付款。

核对停服前已强制刷盘的真实税款返回值后，管理员执行 `reconcile-tax ... funded`，物资变为 `STORED`、采购 `BOUGHT`、支付 `PAID`、税款 `FUNDED`；重复核对拒绝。公共国库与卖家余额在核对前后完全相同：Alpha 2144.40、Beta 9000.00、卖家 4994.07。由此验证待核验记录可跨正常重启保留，且依赖独立凭证恢复时不会再收税或再给卖家发款。详见 [最终重启与清理记录](verification/2026-11-20-paper-final-restart.json)。

最终正常停服后，确认隔离服进程退出、游戏及 RCON 端口均不监听；所有验收/战斗/故障探针及临时 Tavern JAR 已移出 `plugins`，原 StarCore JAR 与原 StarSupply 配置的哈希均恢复为备份值。最终 StarSupply JAR 保留在隔离服，便于运营者按发布说明选择原始依赖或附带围城兼容补丁。上述清理不把尚未覆盖的完整 Folia 组合、插件关闭/世界卸载和其余贸易拒绝分支视为已验收。

对待核验的结算，具备 `starsupply.admin` 的管理员可以在核对 StarCore 结算记录后执行 `/supply reconcile <结算UUID> <paid|unpaid>`。库存交接则用 `/supply reconcile-inventory <库存收据UUID> <applied|untouched>`，选择依据为已核对的玩家存档与交接日志。缺少证据时保持待核验状态。

StarSupply 使用 Folia 的实体调度器执行玩家与仓库界面交互。整个组合的 Folia 验收还依赖 StarCore 及启用的选装插件在该服务器版本上可用。当前参考版 StarCore 的插件声明与军队调度实现限制了 Folia 整服验收，以上流程使用 Paper。
