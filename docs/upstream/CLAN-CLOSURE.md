# StarCore Clan 功能、资金与线程闭环

`starcore-clan-closure.patch` 是第六份顺序补丁，接在后勤、玩家资金回执、税款回执、公共反馈、HUD 设置五份补丁之后。它修复 Clan 的实际命令行为，并将 Clan 接入实验后勤 profile。Religion 和其他白名单外模块仍受限制；生产描述符没有新增 `folia-supported`。

## 行为变化

- `/clan deposit` 先持久保存请求，再通过 `PlayerMoneyService` 扣玩家资金，确认权威回执后增加 Clan 余额。经济服务不可用时不再免费增加 Clan 余额。
- `/clan withdraw` 先持久预留 Clan 余额，再向玩家实际付款；拒绝付款会释放预留。原实现只减少 Clan 余额，没有向玩家入账。
- 使用实际选中的经济提供方；该服务必须同时实现 `PlayerMoneyService`。当前内部经济支持这一协议，普通 Vault 提供方不支持时拒绝启用 Clan，不会暗中切到另一套内部钱包。
- `/clan chat` 与聊天监听器共用同一状态。异步聊天取消公开发送后，回到发送者实体线程格式化，再分别进入接收者实体线程；投递前重新检查氏族成员关系。退出登录会清理聊天模式。
- 修复满员/过期邀请、并发创建与加入、族长被降职、自身结盟、成员越权结盟、`/clan ally add <标签>` 解析和失败后仍显示成功的问题。设置参数必须明确为 `on` 或 `off`。
- 据点位置使用副本，保留未加载世界的名称与坐标；传送完成消息回到玩家实体线程。PvP 规则覆盖直接攻击及伤害来源明确指向玩家的投射物；死亡和击杀统计会标记待保存。
- Clan 消息工具使用同一实体调度和成员复核路径。Clan 服务缺失时不再将私有广播发给全服。

## 保存与恢复

`plugins/StarCore/clan_data.json` 保存成员、职位、关系、设置、统计、时间与据点，使用 UTF-8、原子文件替换和修订号。写盘期间的新修改不会被误清为已保存；失败保留待保存状态。后台周期为 60 秒，正常停用会保存；不涉及资金的最近社交修改在异常进程退出时仍可能丢失一个周期。

加载先验证整个快照，再同时发布 Clan、标签和成员索引。损坏、缺字段、重复身份、未知版本，以及当前文件缺失但仍有备份/临时文件/银行账本时，会停止写入。不会自动用空库或过期备份覆盖当前状态。管理员应在停服后核对成组备份，修复后正常重启。

新增 `clan-bank.json` 是银行权威，使用十进制余额和完整业务 UUID，余额预留与流水在同一次原子替换中保存。每个 Clan 最多有一笔未确认交易，后台串行队列最多等待 128 项。玩家资金余额与回执由前置的 `PlayerMoneyService` 补丁提交；Clan 结果写回失败或响应丢失时，按原请求查回执并恢复，不能换编号再次付款。调用方取消等待不会取消已受理交易。未确认、正在提交或权威不可读时，拒绝解散 Clan。

第一次迁移只从已加载的有效旧 Clan 余额创建银行账本，然后在氏族快照写入 `bankProtocol` 标记。标记保存成功前不能交易；新建氏族也必须先落盘，才能进入资金操作。已有标记却丢失银行账本时拒绝重新导入旧余额。旧余额的小数精度或上限不合法时拒绝猜测或舍入。

旧版本已经产生的免费存款、未付给玩家的提现不具备这些回执；迁移保留旧余额，不代表已核实历史资金来源，也不能据此自动补付旧款。`Clan.deposit/withdraw` 和 `ClanManager.deposit/withdraw` 旧同步入口保留给未绑定模型；绑定运行银行后拒绝直接改余额。运行集成应使用 `ClanManager.transferMoney(UUID, BigDecimal, boolean)` 并处理 `APPLIED`、`REJECTED`、`PENDING`、`BUSY` 或异常结果；氏族/标签/成员结构变更应通过 `ClanManager`。

启动先加载氏族、初始化银行、保存迁移标记，再恢复待办并注册监听与定时任务；Clan 不可用会保留原文件并禁用其命令功能。服务注册可供权限工具读取。停用先关闭 Clan 入口与实体回调，给银行最多 5 秒排空，然后保存氏族，再关闭玩家经济服务；超时会留下耐久请求供重启恢复，不能把未确认交易当成成功。

## 构建和验证

在原始 Core 固定提交 `f769e62799e769a18fe51305b545869a17f80cd1` 的独立干净检出按顺序应用六份补丁，不要重复叠加独立攻城补丁。前五份顺序见 [HUD 设置](HUD-SETTINGS.md)，最后执行：

```powershell
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-clan-closure.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-clan-closure.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xms32m -Xmx384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
.\mvnw.cmd -B -ntp '-Dspotless.skip=true' '-DforkCount=0' `
  '-Dtest=dev.starcore.starcore.clan.*Test,ClanCommandRoleMatrixTest,MessageUtilFormatTest,StarCorePlaceholderThreadingTest,dev.starcore.starcore.foundation.animation.*Test,dev.starcore.starcore.foundation.sound.*Test,dev.starcore.starcore.foundation.hud.*Test,CompatSchedulerTest,ConfigurationServiceFoliaProfileTest,StarCorePluginRegistrationTest,StarCorePluginDependencyFallbackContractTest,PublicApiCompatibilityTest' package
```

本轮定向 370 项 Core 测试通过，其中新增 48 项 Clan 测试。资金测试使用真实 `InternalEconomyService`、真实属性文件持久层与原子文件替换；Folia 归属测试使用真实 `CompatScheduler` 适配器和可控原生调度接口，并断言每次玩家消息、位置读取与传送所在的实体线程。它们不是实服运行。

验证覆盖双侧重启、丢失玩家回执响应、准备/结果文件替换前后故障、权威损坏、回执冲突、余额不足、极端指数金额、调用方取消、迁移标记、新氏族落盘屏障、银行待办阻止解散、并发成员上限、保存期间的新修改，以及聊天/传送/停用回调。详细产物哈希、370 项定向结果、实际兼容 Core 下的 StarSupply 回归与补丁复现范围记录在 [本轮验证](../verification/workspace-clan-closure-20261126.json)。

本轮没有运行全量 Core `verify`，没有启动 Paper/Folia、修改测试世界或迁移实服数据。Clan 的现场迁移、跨区域玩家操作、重启和客户端效果仍待验；MySQL 与大规模历史流水也未在本轮认证。
