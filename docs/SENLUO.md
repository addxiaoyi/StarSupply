# 森罗物语联动

第一阶段的完整 StarCore 组合使用 Java 21 与 Paper 1.21.11；StarCore 当前版本不支持整服 Folia 验收。森罗物语的参考版本为提交 `7a676978f9c4a825275a9f905c3f860ac05a6827`。它的构建配置锁定 CraftEngine 26.9.1。开启森罗联动时同时安装 CraftEngine、KaleidoscopeCookeryPlugin 和相应资源包。若需要酒馆内容，另安装 KaleidoscopeTavernPlugin 和它的资源包。[上游源码](https://github.com/addxiaoyi/SenLuo_Plugin/tree/7a676978f9c4a825275a9f905c3f860ac05a6827)

## 从小麦到军粮

1. 在 `/kcrecipe` 中查阅面团、馒头或蒸包的实际食谱。种植小麦，按食谱制备原料，再用森罗厨具完成加工。馒头的蒸笼配方使用 `kaleidoscopecookery:raw_dough`。[上游配方回归](https://github.com/addxiaoyi/SenLuo_Plugin/blob/7a676978f9c4a825275a9f905c3f860ac05a6827/tools/test_cookery_recipes.py#L282)
2. 把 [Cookery 映射示例](../examples/cookery-mapping.yml) 中的条目并入 `plugins/StarSupply/config.yml` 的 `resource-mappings` 列表，重启服务器。示例将 `kaleidoscopecookery:mantou`（馒头）和 `kaleidoscopecookery:baozi`（蒸包）登记为军粮，单件分别折算 2 和 3 单位。原版面包每件折算 1 单位。
3. 让国家军需官发布军粮采购订单。厨师带成品到采购仓库并执行交货。系统扣除符合映射的物品并按折算单位结算，库存里的物品可以继续安排运输。
4. 从采购仓库发布运输订单。商队按线路到前线仓库交付。保留未消费的原始物品批次，军队按下一结算周期使用已抵达的补给。

StarSupply 通过公共 `CraftEngineItems.getCustomItemId(ItemStack)` 确认真实物品 ID。对于 Cookery，`CookeryFoodItems.read(ItemStack)` 返回带 `itemId()` 与 `quality()` 的 `BakedFood`。收购校验两个物品 ID 一致，记录品质名和倍率。品质档包括 `SUPERB`（1.2）、`EXCELLENT`（0.9）、`STANDARD`（0.6）、`POOR`（0.3）及基础食品的 `BASE`（1.0）。`minimum-quality` 是单件准入门槛，折算仍按配置的固定单位数。普通原版映射只接受组件与普通同类物品一致的堆叠。[Cookery 食品读写](https://github.com/addxiaoyi/SenLuo_Plugin/blob/7a676978f9c4a825275a9f905c3f860ac05a6827/src/main/java/net/kaleidoscope/cookery/item/CookeryFoodItems.java)

每个批次保存 Paper 序列化的完整 `ItemStack`，包括原始份数、PDC、自定义组件和 Cookery 品质。原物品退还先模拟槽位合并和放置，空间不足时整个批次继续托管。原始物品的反序列化遵循 Paper 的数据版本规则，测试过存档备份再升级服务器，切勿用旧版本加载新版物品数据库。

## 酒馆

军需官可以在本国酒馆用 `/supply tavern <集合点ID>` 登记商人集合点。侦察兵实际抵达道路后用 `/supply scout <道路ID>` 保存道路状态，再回到酒馆用 `/supply intel <报告UUID> <集合点ID>` 发布；附近本国玩家用 `/supply board <集合点ID>` 查看有时效的情报及本国开放采购、运输合同。报告和枢纽保存在独立 `tavern.db`，实体卸载不影响状态。

配置 `tavern.require-authentic-beverage: true` 时，登记必须手持森罗酒馆真实酒品。适配器验证 CraftEngine 身份是否为 Tavern 公共 `catalog().barrel()` 的配方输出，并调用 `TavernPlugin.brewLevel(ItemStack)` 检查最低熟成等级；酒品保留原数量与品质。默认关闭强制验证，以保留纯原版集合点玩法。Tavern 的 `DrinkCreatedEvent` 可供其他插件订阅出品，但不含玩家或家具位置，StarSupply 不据此猜测酒馆坐标或自动创建情报。当前没有公共 Tavern 家具注册表供自动发现，位置由有权限的国家玩家登记。[酒馆操作与隔离规则](TAVERNS.md) · [Tavern 酒品事件](https://github.com/addxiaoyi/SenLuo_Plugin/blob/7a676978f9c4a825275a9f905c3f860ac05a6827/series/tavern/src/main/java/net/kaleidoscope/tavern/api/DrinkCreatedEvent.java)

## 玩家物品存档边界

库存计划在玩家所属线程读取快照。应用前检查完整背包指纹，随后把实际槽位和唯一操作回执保存进同一个玩家存档。Paper 的 `Player.saveData()` 不返回写入结果，因此库存适配器额外从主世界 `playerdata/<UUID>.dat` 的 `BukkitValues` 验证该回执，并对文件执行同步。此检查只在物品托管、退回或恢复时执行。StarSupply 的 SQLite 状态必须读取并验证相同回执后才能继续物品结算。玩家数据保存停用、权限错误、存档缺失或无法解析时，当前操作保留以便恢复。不要独立回滚同一个玩家存档或 StarSupply 数据库，备份和还原时一并保存玩家存档、StarSupply 和 StarCore 数据。[Paper 存储补丁](https://github.com/PaperMC/Paper/blob/c5eb0790f199da6c38d0a650e1e5cd5415b28185/paper-server/patches/sources/net/minecraft/world/level/storage/PlayerDataStorage.java.patch)
