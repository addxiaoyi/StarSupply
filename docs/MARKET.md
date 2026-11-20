# 区域物资市场

StarSupply 在每个采购仓库为六类物资分别生成本地参考价。它只读取该仓库尚未预留运输的实际物品件数，以及尚未履约采购单的件数总和；不同仓库的库存和订单互不混算。市场价是每件物品的参考价，不是每个军事补给单位的价格。Cookery 馒头等多单位映射物仍按物品件数报价，军队补给覆盖按真实批次换算单位计算。

令 `stock` 为未预留库存件数，`demand` 为 `OPEN/RESERVED/SETTLING/REVIEW` 采购单的件数总和，`buffer` 为 `market-buffer-items`：

```text
压力 = (demand - stock) / (demand + stock + buffer)
参考单价 = 基础单价 × clamp(1 + 压力, 0.50, 2.00)
```

结果以两位小数四舍五入。压力只在参考价计算时生成，不修改库存或建立额外价格账本；基础价来自 `market-base-prices`，当前物品件数和待采购数量在 SQLite 里持久化，因此重启后同一库存状态会得到同一报价。开放采购会增加需求，取消或结算会按新状态重算价格。已发布采购单的总价锁定，后续市场变化不会重写订单。

玩家可以用 `/supply market <仓库ID> <物资类型>` 查看库存、需求和每件参考价；军需官使用 `/supply buy <仓库ID> <物资类型> <件数> market` 按创建时的参考价发单，也可以继续直接输入议定总价。该报价只作 StarSupply 物品贸易参考，不使用 StarCore 抽象资源市场价格。跨国供货还必须遵守 StarCore 协定单价乘实际映射单位数，因此市场订单总价与协定不匹配时应使用手动总价创建符合协定的订单。

跨国物资供货另受战时出口管制：`wartime-export-controls` 默认包含 `EQUIPMENT` 和 `STRATEGIC`。卖方国家正在参与 StarCore 允许战斗的战争时，这些资源不能向其他国家出售，包括出口到与卖方没有交战的贸易伙伴。本国国家采购不受此出口规则影响；只有买方参战、卖方和平时，也不会仅因进口国参战而拒绝，但仍须满足有效协定、额度、价格、禁运与双方敌对校验。管制在接收真实物资前重新检查，停止新供货，不借此撤销已经确认的库存或付款。

配置示例为 `wartime-export-controls: [EQUIPMENT, STRATEGIC]`。可选值为六类 `ResourceType` 名称，`[]` 关闭该项规则；修改后重启。未配置时采用默认两类，无效的资源名会阻止插件启动。这项配置读取 StarCore 战争权威状态，没有独立战争存档或自动颁布 StarCore 禁运。该规则的现场验收结果需独立记录。

基础价示例：FOOD 2.50、MEDICAL 4.00、EQUIPMENT 5.00、MATERIALS 0.50、ENGINEERING 3.00、STRATEGIC 8.00。服务器可以编辑 `plugins/StarSupply/config.yml` 中的 `market-base-prices` 和 `market-buffer-items`，改动重启后生效。所有价格均受 `max-order-payment` 限制；实际创建的订单仍走原有国库和物品事务结算。
