# StarSupply 公开 API

调用插件在 `plugin.yml` 中依赖 StarSupply，并将其 JAR 加到编译时类路径。通过 Bukkit 服务管理器获取接口。

```java
SupplyApi supply = Bukkit.getServicesManager().load(SupplyApi.class);
if (supply != null) {
    supply.routeNetwork(nationId).thenAccept(network -> {
        // Immutable map data. Schedule any game world changes separately.
    });
}
```

| API | 用途 |
| --- | --- |
| `purchase(id)`、`shipment(id)`、`taxAssessment(settlementId)` | 读取持久化订单、运输及跨境关税报价快照。 |
| `stock(warehouseId)`、`armySupply(armyId)` | 读取实物库存的补给单位和最新的军队需求、覆盖率及战力系数。 |
| `SupplyStatus.campaign()` | 当前冬季、围城持续时间、额外需求与采样时间；已消费周期仍沿用 SQL 固定需求。 |
| `SupplyStatus.frontAccess()` | `CONNECTED`、`CONTROL_LOST` 或 `ARMY_OUT_OF_RANGE`。前线失守、跨世界或距离超过配置服务半径时覆盖率为 0，并暂停实际消耗。 |
| `marketQuote(depotId, resource)`、`createMarketPurchase(...)` | 读取仓库区域市场参考价，或按该报价快照创建采购单。后续市场变化不改动已创建合同金额。 |
| `routeNetwork(nationId)`、`route(nationId, origin, target)` | 获取地图节点与道路状态，或取得计算后的最低成本路径。 |
| `mapVanilla(...)`、`mapCustom(...)` | 注册在当前会话生效的物品映射。持久映射使用配置文件。 |
| `createPurchase(...)`、`createMarketPurchase(...)`、`sell(...)`、`createTransport(...)`、`acceptTransport(...)`、`joinEscort(...)`、`deliverTransport(...)`、`cancelOrder(...)` | 使用与游戏命令相同的权限、状态及位置检查。 |

`ResourceType` 包含六类：`FOOD`、`MEDICAL`、`EQUIPMENT`、`MATERIALS`、`ENGINEERING`、`STRATEGIC`。向 StarCore 申请跨国贸易协定时，对应的协定资源键依次为 `wheat`、`medical_supplies`、`weapons`、`steel`、`explosives`、`ammunition`。默认原版物品映射为面包、蜂蜜瓶、铁锭、圆石、脚手架和烟花火箭，每件各折算 1 单位；配置和会话映射可调整映射规则。StarCore 活跃国策与已解锁科技的物流键映射见 `config.yml`：政策可影响军队周期需求与战备系数，运输科技可增加道路效率，均有硬上限。

带 Player 的方法必须在该实体所属线程调用。查询和结算返回的 CompletionStage 可能在后台线程完成。调用插件执行世界或界面操作前应重新进入对应的实体或区域线程。提交出售或交付后，可用订单 UUID 轮询最终状态。

`SupplyOperationEvent` 在玩家线程发布，可以取消。创建的订单尚无 UUID 时该字段可为空。`SupplySettlementEvent` 在 SQL 提交后异步发布，携带唯一结算 UUID、订单、国家、玩家、类型、毛额与结果。监听器可以用结算 UUID 去重并通过查询接口补读结果。地图接口返回不可变的观察数据，渲染器可按自己的刷新策略取样。

`SupplyConsumptionEvent` 是军事周期消耗提交后的异步通知，`getConsumption()` 返回不可变 `SupplyConsumption`，包含唯一 `settlementId`、军队、国家、前线、周期、需求和实际消耗。ID 由军队 UUID 与周期确定；同一周期重复刷新不会再扣库存或再次正常发布。事件不提供跨进程可靠投递保证：提交后、通知前的崩溃可能丢失通知，监听器不能通过重放事件自行扣物资。读取 `armySupply` 等权威状态后再更新界面，世界操作应回到所属调度线程。

同一个 Bukkit 服务管理器还注册以下独立接口。带 `Player` 的方法同样必须在该玩家实体调度线程调用；结果是不可变快照，可能在后台线程完成。

| 服务 | 方法与边界 |
| --- | --- |
| `TavernApi` | `registerHub(Player,id)`、`removeHub(Player,id)`、`scoutRoute(Player,roadId)`、`publishIntelligence(Player,reportId,hubId)`、`tavernBoard(Player,hubId)`、`tavernHubs(Player)`。登记检查管理身份与本国领土，侦察要求亲临道路，读板与发布要求亲临本国仍受控的酒馆。 |
| `InsuranceApi` | `insuranceQuote(shipmentId)`、`insuranceQuote(Player,shipmentId)`、`insure(Player,shipmentId)`、`insurance(shipmentId)`。带玩家的报价排除该玩家自己的护送登记，适合向承运者展示实际保费；无玩家报价只提供当前队伍参考。投保仅允许本国承运者在起点、出发前操作，并经过可取消事件。保单状态和每笔经济阶段通过 `Policy.money()` 可审计，保险不创建货物。 |

`TavernModels.Board` 包含该集合点、本国未过期报告与本国开放合同。`ScoutReport` 的观察、到期及首次发布时间不因重复发布延长，报告不泄露敌方库存。`InsuranceApi.Policy` 的身份、固定保费、赔额和风险上限在创建时锁定；`blocksTransport()` 表示经济操作待处理或待核验，外部调用方不能绕过该状态继续领取或交付。独立数据库与流程分别见 [酒馆情报](TAVERNS.md) 和 [承运收入险](INSURANCE.md)。

卖家必须属于 StarCore 国家。玩家属于其他国家时，`sell` 还要求 StarCore `ResourceTradeService` 存在未过期、未取消的同方向贸易协定，资源 ID 匹配且未被 StarCore 禁运。映射单位总数和采购总价必须符合协定额度与单价。没有 StarCore 贸易服务时只允许本国成员向本国采购单出售；本国采购使用玩家物品映射单位与实际库存验证。中立玩家仍可按普通运输规则承运。

`sell` 同时执行 `wartime-export-controls`：默认 `EQUIPMENT` 和 `STRATEGIC` 在出口国参与 StarCore 可战斗战争时禁止跨国供货，包括卖给中立伙伴。本国供货以及仅买方参战不会被该项规则单独拒绝。配置使用六类 `ResourceType` 名称，`[]` 关闭规则，缺省采用默认集合，非法名称使启动失败。该检查在真实库存交接前重新执行，不自行改变 StarCore 战争或禁运状态。

有效跨境采购在背包物品交接前取得并保存 StarCore `TARIFF` 报价；买方国库按毛额支出，卖家收净额，关税通过 StarCore 进入进口国库。`/supply reconcile-tax <结算UUID> <funded|unfunded>` 仅供管理员使用，必须先独立核验 StarCore 账目。StarCore 当前没有按税收 UUID 查询入账回执的服务，因此证据不足时交易保持冻结。

`taxAssessment(settlementId)` 返回的 `TaxAssessment` 是不可变快照，包含结算 UUID、税种、进口国 UUID、卖家 UUID、采购毛额及税额。它用于展示、审计或对账；外部调用方不得据此自行再次收税或改写结算。StarSupply 将关税阶段持久化为 `QUOTED`、`FUNDING`、`FUNDED` 或 `REVIEW`。StarCore 无法确认税款结果时，状态与实物交接保持待核验；只有从 StarCore 独立记录核实结果后，管理员才可以使用相应 `reconcile-tax` 选项恢复。

区域市场以仓库和资源分类隔离，参考价从该仓库当前未预留的真实物品件数及开放采购需求计算，并限制在配置基础价的 0.5–2 倍范围。需求字段按合同物品件数计量，Cookery 自定义物品仍以每件合同数量统计，补给换算单位显示在批次与军队状态中。该价格是已锁定总价的新订单报价，不覆盖明确填写总价的手工采购，也不直接写 StarCore 抽象资源价格。报价是当前库存与未履约采购数量的快照；跨国订单仍以 StarCore 协定的映射单位额度与单价为准。

运输订单处于 `OPEN` 时，最多四名发单国家成员可在订单起点调用 `joinEscort` 加入护送队。方法返回加入后的护送人数。护送者随后领取同一运输时，会在同一事务中移除自己的护送记录，计时和风险只计算其他成员。护送成员存于 SQL 的独立记录中，不包含在 `Shipment` 记录里；`Shipment.routeRisk` 提供当前路线风险快照。每名护送者最多降低路线风险 10% 并缩短运输时间 5%，四人上限分别为 40% 和 20%。护送者无单独奖金，运费仍给承运者。实物交接时生成并持久化批次遭袭判定值，同批物资后续重运沿用该值，避免借取消反复抽取；运输路线风险则随建单、领取或绕行更新。遭袭失败时货批会安全回到起点，承运费未被结算。
