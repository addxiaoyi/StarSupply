# StarCore HUD 设置与菜单行为

`starcore-hud-settings.patch` 是第五份顺序补丁，应用在后勤、玩家资金回执、税款回执、公共反馈补丁之后。它补齐此前 HUD 设置命令的实际行为。生产描述符仍未声明 Folia 支持，白名单外 Core 模块仍受限制。

## 玩家操作

- `/hud`、`/hud menu`：按个人设置打开主菜单。
- `/hud settings`：查看当前有效设置，并通过聊天中的按钮选择样式、切换效果或恢复默认。
- `/hud style <名称>`、`/hud particle <类型>`：保存个人样式或粒子类型。
- `/hud particles`、`/hud breathing`：切换个人粒子或呼吸动画。
- `/hud reset`：删除个人覆盖值，重新继承服务器默认值。
- `/hud preview <样式>`：临时预览，不保存选择，也不显示粒子或呼吸动画。
- `/hud reload`：玩家管理员重新加载配置；需要 `starcore.hud.reload`，默认 op，包含在 `starcore.admin` 中。

选择在文件保存成功后才确认。当前仍打开的原菜单会更新装饰和效果，不重建窗口；菜单已关闭时不会因为保存完成而重新打开。重新登录或重启后仍读取已保存设置。粒子操作只改变执行者，原有全局粒子 API 仍可作为全服开关。

## 配置

第一次启用生成 `plugins/StarCore/hud.yml`：

```yaml
version: 1
defaults:
  style: NIGHTMARE
  particles: true
  particle-type: SPARKLE
  breathing: true
```

个人覆盖保存在同目录的 `hud-players.yml`，按 UUID 分组。没有主动修改的字段继续继承默认配置；修改服务器默认值并重载，不会覆盖个人已选字段。`reset` 可恢复继承。

设置写入由独立单线程、最多 128 个等待任务的队列串行处理，使用已有 `AtomicFilePersistence` 临时文件刷盘和替换。快速连续切换按提交顺序执行，不会覆盖其他玩家的改动。调用方取消等待不会取消已受理的写入。

重载先校验两个文件再发布完整快照。未知版本、选项、枚举、错误布尔值、损坏 YAML 或 UUID 都会拒绝加载，保留原内存配置。加载失败后拒绝继续保存，避免旧内存覆盖损坏的个人文件；修复文件并成功重载后恢复写入。停用先关闭界面回调入口，再给已受理写入最多 5 秒排空；超时会记录警告，未确认结果不能当作保存成功。

## 菜单修复

背景动画随玩家实体任务推进，只重绘仍由菜单管理的装饰槽，保留按钮和子类直接写入的内容。关闭呼吸动画会恢复主题底色；彩色主题按 RGB 匹配染色玻璃，不再统一转换成灰度。9 槽菜单不再越界填充内边框。

主菜单的 HUD 设置入口进入 `/hud settings`；物品仓库进入实际 `/warehouse open`，政策进入实际 `/sc policy status`。受配置或后勤 profile 禁用的模块显示不可用状态，并移除原点击处理器。预览、移动事件和定时粒子都尊重个人菜单开关。

## 复现与证据

从 Core 原始提交 `f769e62799e769a18fe51305b545869a17f80cd1` 的干净检出按顺序应用：

```powershell
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-player-money-receipts.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-tax-funding-receipts.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-feedback.patch
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-hud-settings.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-hud-settings.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xms32m -Xmx384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
.\mvnw.cmd -B -ntp '-Dspotless.skip=true' '-DforkCount=0' `
  '-Dtest=dev.starcore.starcore.foundation.animation.*Test,dev.starcore.starcore.foundation.sound.*Test,dev.starcore.starcore.foundation.hud.*Test,CompatSchedulerTest,ConfigurationServiceFoliaProfileTest,StarCorePluginRegistrationTest,StarCorePluginDependencyFallbackContractTest,PublicApiCompatibilityTest' package
```

本机使用 192 MiB 堆，在已有全量生产编译基线上增量编译 6 个生产源和 4 个测试源；10 个 Java 文件格式检查通过。Core 定向 154 项通过，其中新增 22 项 HUD 回归。补丁 11 个文件反向撤销、检查与重新应用通过；重现后的全部 3,535 个 `src` 文件与当前兼容检出一致（归一化换行）。

实际新 Core 成品下的 StarSupply 配套测试、成品与日志哈希见[本轮记录](../verification/workspace-hud-settings-20261126.json)。这不是 Core 全仓 verify。没有启动 Paper/Folia，没有图形客户端验收；实服菜单、跨区域玩家操作与声音视觉仍待验证。完整 Folia 模块、六类生存生产链、MySQL 和历史回执规模仍未完成整体验收。
