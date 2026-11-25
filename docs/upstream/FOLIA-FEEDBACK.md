# Core 音效、屏幕震动与 HUD 的 Folia 线程适配

`starcore-folia-feedback.patch` 在既有后勤、玩家资金、税款补丁之后应用。它把原先被后勤 profile 排除的屏幕震动、场景音效和现代 HUD 接回生命周期。模块白名单、Clan、Religion 和旧压测命令的限制仍在；生产插件没有增加 `folia-supported`。这是一份继续扩大适配范围的源码检查点，不是完整 Folia 发布认证。

屏幕震动原来通过同步传送改变玩家坐标，结束时传回最初位置。本补丁使用 Paper `sendHurtAnimation`，只发送客户端反馈，不造成伤害、传送或残留相机状态。原先基于反射、目标 API 中不存在的 Camera 路径已移除。客户端决定实际震动幅度；旧配置中的 `amplitude`、`cameraShake` 保留源码兼容，不再承诺任意相机位移。强度用于禁用和强弱替换，持续时间采用单调时钟，频率控制发送周期。

播放入口、ActionBar、距离判定、重复任务均进入对应实体线程；位置音效使用捕获的固定坐标进入区域线程。插件停用会等待正在执行的短反馈回调退出、取消已排队任务并拒绝新回调。任务替换、提交时立即退役和后续退役都按实例清理，旧任务不会删除新任务。用户停止震动与已排队的开始操作按同一实体队列处理；关闭偏好会阻止尚未执行的开始操作。

HUD 接通原来空置的粒子启动入口，处理实例身份、刷新时的旧库存关闭事件、重复关闭、断线与停用清理。按钮操作延迟到下次实体 tick，避免在 `InventoryClickEvent` 内直接开关库存；底部 Shift 点击和拖拽也不能向菜单搬入物品。关闭回调只运行一次。退役和停用清理不在外部线程调用玩家 GUI 回调。

场景音效的 `scene-sounds.yml` 现在独立读写；原实现错误地读写主 `config.yml`，且重载未影响播放服务。配置发布为不可变快照，坏 YAML 保留旧内存配置并报告错误。场景/分组开关、全局与个人分类音量进入实际播放。原来的音效解析把全部下划线替换成点，破坏 `note_block`、`experience_orb` 等注册键；现在按注册表映射旧大写名称，并保留显式命名空间。默认场景中的四个过时或不存在的音效名称已换为当前 Paper API 声明的名称。

## 复现

从原始 Core 提交 `f769e62799e769a18fe51305b545869a17f80cd1` 的独立检出开始，按顺序应用：

```powershell
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-player-money-receipts.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-tax-funding-receipts.patch
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-folia-feedback.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-feedback.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xms32m -Xmx384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
.\mvnw.cmd -B -ntp '-Dspotless.skip=true' '-DforkCount=0' `
  '-Dtest=dev.starcore.starcore.foundation.animation.*Test,dev.starcore.starcore.foundation.sound.*Test,ModernHudFoliaTest,CompatSchedulerTest,ConfigurationServiceFoliaProfileTest,StarCorePluginRegistrationTest,StarCorePluginDependencyFallbackContractTest,PublicApiCompatibilityTest' package
```

本机在已有全量生产编译的基线上，以 192 MiB 堆增量编译 11 个生产文件和 8 个测试文件。最终 Core 定向回归 132 项全部通过，包括新增 25 项行为回归；19 个 Java 文件通过定向格式检查。独立补丁反向撤销、重新检查与应用后，20 个文件逐一匹配。完整日志哈希、用例清单和新成品见[验证记录](../verification/workspace-folia-feedback-20261126.json)。这不是 Core 全仓 `verify`。

独立 javac 编译后打包必须执行两个 shade 阶段：

```powershell
.\mvnw.cmd -o -B -ntp '-Dmaven.jar.forceCreation=true' jar:jar `
  shade:shade@minimize shade:shade@restore-reflective-runtime
```

StarSupply 的固定编译依赖和生产源码未修改。新 Core 成品用 `tools/test-player-money.ps1 -StarCoreJar <兼容包> -JavaHome <JDK21> -Offline` 执行整个 StarSupply 套件，结果与成品哈希见验证记录。复现补丁无需修改本地 Maven 仓库中的固定 Core API。

## 仍待闭环

- 本轮使用真实兼容调度器封装及可控原生调度接口替身，没有启动 Paper/Folia，也没有客户端视觉或声音验收。
- 完整 Core 模块及可选插件组合尚未完成 Folia 适配。Clan、Religion 和其余未纳入白名单的模块仍不能据此启用。
- 现有 HUD 命令还存在独立功能缺口：`style` 解析后没有将所选样式用于普通主菜单，`breathing` 只显示消息，`reload` 没有实际加载 HUD 配置。本轮完成的是线程与会话生命周期适配，后续继续补齐这些公开命令。
- 六类可追溯生存生产、持续后勤消费、新资金/税款协议的实服重启、MySQL/历史回执规模与图形资源包仍保留待验。已有玩家存档未修改。
