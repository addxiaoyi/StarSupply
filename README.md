# StarSupply

StarSupply 第一阶段是面向 Paper 1.21.11 的国战后勤插件。军需官发布采购和运输合同，玩家用背包里的真实物品供货，并把国家库存从仓库运到前线。已绑定的 StarCore 军队在战时按周期消耗物资，补给变化直接进入 StarCore 的军队战力计算。

原始需求、实现位置和验收边界见 [需求对应表](docs/REQUIREMENTS.md)。

## 安装

使用 Java 21 和 Paper 1.21.11。将 StarCore 1.0.0 和 `target/starsupply-0.1.0.jar` 放入 `plugins/`。在 StarCore 中选择内部经济 `economy.provider: internal`，并启用国家、官员、军队、战争与战场服务。兼容的 CraftEngine 和森罗物语 Cookery 可以提供品质军粮。默认配置也支持纯原版物品。

StarSupply 通过实体调度器操作背包和 GUI，路线计算与 SQL 使用独立后台线程。当前钉住的 StarCore 版本使用旧版 Bukkit 调度器，参考组合的运行目标是 Paper。

## 开始游戏

| 身份 | 操作 |
| --- | --- |
| 国家元首或司库 | 在本国已占领的领土上用 `/supply depot <仓库名>` 登记国家仓库，再到前线用 `/supply front <前线名> <军队UUID或前缀>` 绑定军队。 |
| 随军军需官 | 军队推进后，在新的本国已占领位置用 `/supply front <新前线名> <同一军队UUID或前缀>` 转移军队绑定；旧站库存和运输留在原处，另行建立道路和补给。 |
| 军需官 | 用 `/supply route <道路名> <仓库> <前线> 1.0` 建立有向补给线路。 |
| 军需官 | 用 `/supply buy <仓库> food 8 40` 发布一张 8 件军粮、总价 40 的采购单。 |
| 厨师 | 用原版面包或 Cookery 馒头备货，到仓库点击 `/supply orders` 的采购单，或执行 `/supply sell <采购UUID>`。 |
| 军需官 | 采购状态变为 `BOUGHT` 后，用 `/supply ship <采购UUID> <前线> 15` 发布总运费 15 的合同。 |
| 承运者 | 在起点执行 `/supply accept <运输UUID>`，到前线、运输计时结束后执行 `/supply deliver <运输UUID>`。 |
| 本国承运者 | 出发前在起点用 `/supply insurance <运输UUID>` 查阅收入险报价，用 `/supply insure <运输UUID>` 支付保费投保；保险仅补偿遭袭失去的部分运费。 |
| 军需官与侦察兵 | 用 `/supply tavern <集合点ID>` 登记本国酒馆；到道路旁 `/supply scout <道路ID>`，回酒馆 `/supply intel <报告UUID> <集合点ID>` 发布情报，再用 `/supply board <集合点ID>` 查看。 |
| 军需官 | 使用 `/supply` 查看仓库、合同、路线与前线补给，用 `/supply status [军队UUID]` 查看战力系数。 |

在采购仓库可用 `/supply market <仓库> <物资>` 查看该地区每件物品的动态参考价与库存/需求；`/supply buy <仓库> <物资> <件数> market` 会按当时参考价锁定合同总价。手工填写总价的采购仍保留议价空间。默认参考价和缓冲件数见 `market-base-prices` 与 `market-buffer-items` 配置。

市场定价口径、限幅公式、重启行为和跨国协定价格约束见 [区域物资市场](docs/MARKET.md)。

仓库默认收购面包、蜂蜜瓶、铁锭、圆石、脚手架和烟花火箭，分别对应军粮、医疗、装备、建材、工程物资、特殊战略资源。Cookery 馒头保留 CraftEngine 身份和原始品质，映射示例见 [森罗物语接入](docs/SENLUO.md)。路线与 StarCore 领土和原生 `SupplyLine` 的关系见 [路线规则](docs/ROUTING.md)。

`starsupply.use` 控制游戏内使用。国家管理命令同时检查 `starsupply.manage` 和 StarCore 的国家元首或司库身份。采购款和运费在对应交付确认后结算。资金不足时，卖家可用 `/supply recover [交接UUID]` 取回原物，运输库存则回到起点。操作细节见 [Paper 验收规程](docs/PAPER-ACCEPTANCE.md) 和 [事务恢复](docs/TRANSACTIONS.md)。

仓库和前线失去本国领土控制后暂停相关交易与道路通行，保留实物和合同待恢复。军队必须与绑定前线处于同一世界并在默认 256 格服务半径内；超出范围或前线失守时不再消费该站物资，补给覆盖为 0。军队返回或领土收复后恢复，已消费周期不会再次扣物资。服务半径由 `army-frontline-radius` 调整。

## 构建与扩展

使用 Java 21 和 Maven 3.9.x。先构建并安装 [StarCore](https://github.com/addxiaoyi/starcore) 的 `f769e62799e769a18fe51305b545869a17f80cd1` 版本到本地 Maven 仓库，再在 StarSupply 执行 `mvn verify`。发布包在 `target/starsupply-0.1.0.jar`，包含 SQLite JDBC。服务器另外安装 StarCore。首次启动生成 `plugins/StarSupply/config.yml` 和独立的 SQLite 数据库。

Bukkit 插件可以从服务管理器获取 `dev.starsupply.api.SupplyApi`，订阅操作与结算事件，或读取供地图渲染使用的线路数据。见 [公开 API](docs/API.md) 与 [StarCore 接入](docs/STARCORE.md)。

资源模型覆盖粮食、医疗、装备、建材、工程和特殊战略资源六类。区域报价取自真实仓库库存与开放采购需求，跨国实物贸易复用 StarCore 协定、禁运和进口关税；采购及运费仍由 StarCore 国库与玩家内部经济结算。护送队、批次风险值和运输状态持久化，遭袭后货物退回起点，同批次重运沿用原风险值。护送者当前没有独立报酬。

战时出口管制默认限制 `EQUIPMENT` 和 `STRATEGIC`：出口国正在参与 StarCore 可战斗战争时，暂停向其他国家出售这两类物资，即使买方是中立贸易伙伴。本国采购以及仅进口国参战的合规进口不因此禁止，原有协定、禁运和敌对校验仍生效。可在 `wartime-export-controls` 中选择六类资源，设为 `[]` 关闭这项出口管制；更改后重启，非法资源名会阻止插件启用。当前运行验收范围仍以验收文档为准。

护送者可以改为领取同一运输，但领取事务会移除其护送身份，自身不提供减险或加速。收入险按具体承运者排除自身后的风险报价，准备金与保费全部确认后才激活；未起运保险从实际激活时间计算五分钟等待期。保险不允许在货物已经结算、失败或关闭后通过退保改变已经产生的理赔资格。

StarCore 的活跃国策和已解锁科技影响补给需求、道路效率和战备战力。新增的冬季远征与持续围城压力读取 StarCore 当前季节、战争、攻城器械和城墙，按上限增加需求，和平或条件解除后恢复；已结算周期的需求保持原快照。详见 [战役补给](docs/CAMPAIGNS.md)。酒馆集合点、实地侦察与有时效的本国情报板见 [酒馆与情报](docs/TAVERNS.md)，国家承保的承运收入险见 [保险结算](docs/INSURANCE.md)。这些扩展的代码已接入，运行验收结果按 [Paper 验收规程](docs/PAPER-ACCEPTANCE.md) 中的独立记录判定。

首次启动还会生成独立的 `tavern.db` 与 `insurance.db`。备份时应与 `supply.db`、StarCore 数据和玩家存档一并保存。地图 API 提供不可变路线数据供第三方渲染；当前没有插件内地图渲染器或 NPC 商队表现。完整组合继续以 Paper 验收，Folia 整服兼容取决于 StarCore 和选装插件的支持。
