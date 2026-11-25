# StarSupply 开发进度与换设备交接

更新日期：2026-11-25。接续分支：`codex/strategic-logistics`；仓库：<https://github.com/addxiaoyi/StarSupply>。

当前是可继续开发的阶段检查点，**全部阶段尚未完成**。下表按需求分组汇报，验收口径以 [REQUIREMENTS](REQUIREMENTS.md)、[Paper 记录](PAPER-ACCEPTANCE.md) 和 [Folia 记录](FOLIA.md) 为准。不要把旧构建的运行记录套用于新构建，也不要把管理员准备库存的消费验收当成生存生产验收。

本次续接已同步检查 GitHub，恢复构建并修复生存验收脚本的熔炉断点和方块放置确认。124 项 Java、47 项 Node、7 项 PowerShell 恢复检查全部通过；生产 JAR 与下方哈希相同，玩家存档也与交接哈希一致。本机初查约 325 MiB 可用物理内存，用户明确选择“先完成能运行的开发和测试，保留实服待验项”，因此本次没有启动测试服。新代码的实服结果仍待验证，见 [续接验证记录](verification/workspace-survival-resume-20261125.json)。

## 阶段进度

| 阶段 | 当前结果 | 后续工作 |
| --- | --- | --- |
| 核心后勤与经济 | 六类资源、国家仓库/前线、采购托管、运输护送、道路风险、区域市场、协定/禁运/关税、战时出口控制、保险、GUI/API 已实现；有 Paper 和指定 Folia 构建的真实闭环记录 | 新设备恢复构建，按需求表回归当前组合 |
| 战役与原生补给 | 冬季/围城需求、临时战力/士气、实际 FOOD 消费结转 StarCore 耐久回执、重启重放已实现；既有 Folia 记录覆盖六个消费周期和士气恢复 | 继续以六类生存自产物资完成持续作战验收 |
| 一致性与中断恢复 | 主库 v11、酒馆 v1、保险 v2；库存指纹兼容、SQL 不确定提交隔离、停用/重启屏障、实体回调退役、保险结果写回中断、调用方取消 future 的库存锁清理均已加固；124 项 Java 测试通过 | 保留回执与复核语义；无外部幂等凭证的金额不能自动重付 |
| Folia 区域恢复 | 当前构建实际验证实体 scheduler 退役、未执行回调清理、区块卸载/区域消失、重连新区域；未扣物撤销和已保存回执恰好一次结算均通过 | 夹具固定了故障边界，尚不代表整个售货入口随机中断、世界整体卸载 API 或完整插件组合 |
| 六类生存生产 | 六阶段脚本入口已实现；实际存档有 6 煤、6 粗铁、146 圆石、铁镐，生命/饥饿各 20；之前真实 Cookery 军粮生产只验证 1 FOOD/1 周期 | 完成烧炼/瓶子、地表工具、种植面包、采蜜、脚手架、烟花；再采购、运输、持续消费。六阶段目前未通过整体验收 |
| 完整组合与交付 | 旧九插件 Folia 组合已启动，Music 键盘/MIDI/停止/断线清理有记录；可移植 StarCore 补丁、构建和验收工具在仓库 | 当前完整可选插件组合、相关 Core 路径的生产 Folia 认证、图形客户端资源包渲染仍未完成；生产 `plugin.yml` 仍不声明 Folia 支持 |

## 最新构建与验证

- Java 21；Maven Wrapper 3.9.16；Paper API 1.21.11。当前生产 JAR 大小 14,678,765 字节，SHA-256 `87487098E790404108C9010EAE2186B1FFECAD1CF0A99119363A93D37BD18980`。
- `mvnw.cmd -B -ntp -DforkCount=0 verify`：124 项测试，0 失败/错误/跳过。生产源码在该构建后未改；后续修改是验收脚本和文档。见 [库存取消修复记录](verification/workspace-inventory-cancellation-20261125.json)。
- 验收器：当前 47 项 Node 测试、7 项 PowerShell 恢复检查通过。新增 8 项服务器放置确认和 9 项熔炉断点测试；21 个 Node 脚本语法检查通过。旧版恢复脚本在 PowerShell 7 和 Windows PowerShell 5.1 验证；本次恢复脚本未改。历史实服范围见 [六类续接记录](verification/folia-survival-six-resource-progress-20261125.json)，本次自动化范围见 [续接验证记录](verification/workspace-survival-resume-20261125.json)。
- 当前构建的独立 Folia 区域恢复：两种库存边界、实际退役异常栈、唯一付款、重复恢复不变、停服玩家 NBT 和六库完整性均通过。见 [区域恢复记录](verification/folia-region-retirement-20261125.json)。
- 最后实服运行 `2026-11-25T07-52-02-144Z`：Core/Supply/服务器均启用；Mineflayer 进程因内存分配失败退出 134，阶段未通过。服务器随后经 RCON 正常保存退出，两个端口关闭，全部插件按哈希恢复。玩家存档仍保留前述材料。

本机可用物理内存和 Windows 提交余量波动较大，曾在启动前预检通过后又耗尽原生内存。`vanilla` 默认堆 384 MiB，`full` 默认 1024 MiB，另保留 512 MiB 给客户端和原生分配。新设备应留足余量，勿通过降低验收标准绕过 OOM。

## 新设备获取与构建

```powershell
git clone --branch codex/strategic-logistics https://github.com/addxiaoyi/StarSupply.git
Set-Location StarSupply
```

已克隆的设备先提交或妥善保存自身改动，再执行 `git fetch origin`、切换该分支并 `git pull --ff-only`。

编译依赖使用原始 StarCore 固定提交 `f769e62799e769a18fe51305b545869a17f80cd1`。先单独检出并安装此版本，再构建 StarSupply：

```powershell
git clone https://github.com/addxiaoyi/starcore.git ..\starsupply-starcore-api
git -C ..\starsupply-starcore-api checkout --detach f769e62799e769a18fe51305b545869a17f80cd1
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\tools\build.ps1 -StarCoreRoot ..\starsupply-starcore-api -JavaHome $env:JAVA_HOME
```

运行时则需要兼容 Core。另开原始固定提交的干净工作树，按 [上游补丁说明](upstream/README.md) 应用 `starcore-folia-logistics.patch`；这个组合补丁已包含攻城补丁，不能再重复叠加旧攻城补丁。生产仍以 Paper 为目标，实验 Folia 使用后勤 profile；描述符覆盖只用于隔离测试。旧设备的兼容 Core 成品 SHA-256 为 `6BB059707AEDCFE3E7D65BAD768329E891BA7144C6DA6B1B9A1C30790E8A4B0A`，不同构建需登记自己的哈希。

依赖已安装时，低内存构建命令为：

```powershell
$env:MAVEN_OPTS = '-Xms32m -Xmx256m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
.\mvnw.cmd -B -ntp '-DforkCount=0' verify
$tests = Get-ChildItem tools/acceptance/survival-progression -Filter '*.test.cjs'
node --test @($tests.FullName)
.\tools\acceptance\survival-progression\restore-stage.test.ps1
```

仓库不包含 `target`、依赖缓存、验收插件编译输出、崩溃转储或测试世界。需要 JAR 时在新设备构建；审查 ZIP 可用 `tools/package-review.ps1 -StarCoreJar <兼容Core.jar>` 生成。

## 接续生存流程

下一步是 **`mining`：6 粗铁烧炼为 6 铁锭，再回地表采沙、烧玻璃、合成 3 玻璃瓶**。随后依次 `surface`、`farming`、`honey`、`scaffolding`、`fireworks`。入口与每阶段存档条件见 [生存运行说明](../tools/acceptance/survival-progression/README.md)。

需要重点继续实测的代码是 `six-resources.cjs` 的工作台/熔炉放置、垫方块上升、地表分段寻路和天然地牢取火药。旧路径会从 y=49 直接找沙而失败，现已改为先烧炼再上升；改后尚未成功跑通。服务器挖掘确认包装必须在 Mineflayer 登录后安装，避免被其异步插件覆盖；这项最终时点修正只有单元测试，尚无成功的后续实服确认。饲养/种植和正常掉落不能用免费物品代替。

最新续接将放置操作也接入单方块/批量方块协议确认，不再仅凭客户端缓存判断成功。熔炉续跑会先计入炉内原料、成品和打开窗口中的玩家库存，再补足材料；途中燃料耗尽时从真实煤/木炭补充，异类物品不改动，缺料和超时会停止。新增测试覆盖了这些边界，但仍需从 `mining` 开始现场验证。

运行器会记录脚本哈希，在正常停服后保存失败阶段的权威背包检查点，但只有目标产物在生存模式 NBT 中持久存在才允许阶段通过。已完成的物品可以断点续用；纸、瓶子、煤和工作台也会被后续配方消耗，需检查真实背包。现有世界为 peaceful，烟花脚本计划从自然生成地牢箱取得火药；这是待验收路线。

## 旧设备的本地状态

GitHub 提供源码和验证摘要，**不会同步世界、玩家存档或运行数据库**。要接着同一个玩家的进度跑，应在停服状态下私下整体复制下面的测试服目录；必须一起迁移世界/玩家、StarSupply 与 StarCore 数据库、配置和匹配的插件，不能只搬单个 `.dat` 或某一个数据库。

| 用途 | 旧设备路径 |
| --- | --- |
| 当前生存隔离服（首要迁移） | `C:\tmp\starsupply-folia-sqlite-retry-runtime-20261122` |
| 当前构建区域恢复隔离服 | `C:\tmp\starsupply-folia-region-retirement-20261125` |
| 九插件历史完整组合 | `C:\tmp\starsupply-folia-music-full-stack-20261122` |
| 兼容 Core 源码/成品 | `C:\Users\Administrator\Documents\starsupply-deps\starcore-folia` |
| Mineflayer 依赖 | `C:\Users\Administrator\Documents\starsupply-deps\minecraft-client-1.21.11\node_modules\mineflayer` |
| Pathfinder 依赖 | `C:\tmp\starsupply-survival-progression-client\node_modules\mineflayer-pathfinder` |

当前玩家 `SupplyCook`，离线 UUID `904364cc-bf39-35a0-b688-6cc8a6d846c8`，位置 `[5.499105686866461,49,58.4309376655394]`。保存文件 `starsupply-stage1-world/playerdata/<UUID>.dat` 的 SHA-256 为 `747194DCF7BBF6FAA52996FA9DA2A59939227354612FC69A0F05AC92D0BD24D0`。测试游戏/RCON 端口为 `25729/25739`，仅绑定 loopback；在新设备保持隔离。没有迁移世界时，可继续代码开发和测试，但不能宣称沿用这个生存检查点。

原始日志、测试 JAR 和插件恢复清单在各测试服 `folia-proof/`。结构化证据已加入 `docs/verification/`。旧设备退出前确认无测试 Folia JVM、两个端口关闭、无 pending 插件恢复清单。

## 给下一次 Codex 的接续说明

> 读取 docs/HANDOFF.md、docs/REQUIREMENTS.md、docs/FOLIA.md 和最新 verification JSON，继续完成全部剩余阶段。先核对新设备依赖与已迁移的隔离服；完成 six-resources.cjs 的 mining 及后续五阶段实服存档验收，再完成六类自产物资的采购、运输、持续消耗；最后验证当前完整 Folia 组合、生产支持边界和图形资源包。保留现有改动与历史证据，按具体构建哈希记录新结果，不把未完成项标成通过。
