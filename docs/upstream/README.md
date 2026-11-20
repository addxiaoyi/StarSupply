# StarCore 攻城财政依赖与部署生命周期修复

补丁 [starcore-siege-treasury.patch](starcore-siege-treasury.patch) 适用于公开仓库 `addxiaoyi/starcore` 的固定提交 `f769e62799e769a18fe51305b545869a17f80cd1`。这是单独、可审查的上游源码修复，不包含编译产物，也未推送到远端。StarSupply 的编译 API 仍以该固定提交为基础。

固定版本的 `SiegeModule` 只声明 `nation` 和 `economy` 依赖，但在创建器械时必须通过 `TreasuryService` 查询、扣除国家资金。模块管理器可能先启用 `siege`、再启用 `treasury`；原来的可选查询把 null 永久传入攻城服务，后续创建器械便会失败。StarSupply 读取真实围城状态的功能需要服务器上的权威攻城服务正常运行。

隔离服随后还发现，固定版本的公开 `deploySiege` 只把器械改为 `DEPLOYING`，既有分钟更新任务从不调用 `completeDeployment`。因此正常服务调用不能到达 `READY`，部署后的器械一直不能开始攻城。组合补丁保留原来的更新周期和存档格式，在下一次既有更新中完成仍处于部署状态、存活且有部署坐标的器械。

补丁改变以下业务约束：

- 将 `treasury` 加入 `SiegeModule` 显式依赖，保证财政服务先启用。
- 使用 `serviceRegistry.require(TreasuryService.class)`，服务缺失时在创建定时攻城服务之前拒绝启动。
- 在既有一分钟攻城更新中把有效 `DEPLOYING` 器械转为 `READY` 并沿原路径持久化。撤退、解散、失去生命/船员或缺少部署位置的器械不会提前就绪。重新部署冷却仍保持原值；没有新增独立计时器，也没有修改编解码器。

新增 `SiegeModuleTreasuryLifecycleTest` 包含三项运行行为测试：真实 `ModuleManager` 在反向注册顺序下仍先注册财政服务，再创建攻城服务；创建真实器械调用所注册的财政权威；财政模块缺失或直接启用时未注册财政服务，都不会创建攻城定时任务。测试还检查正常关闭释放服务、取消两项定时任务。

新增 `SiegeDeploymentLifecycleTest` 覆盖三条部署路径：公开创建和部署、捕获的原有周期任务使其就绪、公开开始攻击真实城墙；经过原有编解码与持久化加载后首次更新恢复待完成部署；撤退、解散以及无效存活/坐标条件不会完成部署。

## 应用与验证

在独立干净检出上操作，保留原服务器和原 StarCore 检出：

```powershell
git worktree add --detach C:\path\to\starcore-siege-fix f769e62799e769a18fe51305b545869a17f80cd1
Set-Location C:\path\to\starcore-siege-fix
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-siege-treasury.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-siege-treasury.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1'
.\mvnw.cmd '-Dspotless.skip=true' '-Dtest=SiegeModuleTreasuryLifecycleTest,SiegeDeploymentLifecycleTest,ModuleManagerTest,ArmyExtensionLifecycleTest' test
```

2026-11-20 第一版财政修复已在该独立检出用 Java 21 执行定向测试：`SiegeModuleTreasuryLifecycleTest` 3 项、`ModuleManagerTest` 8 项、`ArmyExtensionLifecycleTest` 6 项，共 17 项全部通过，0 失败、0 错误、0 跳过。Maven 从源码重新编译了 2233 个生产文件和 1008 个测试文件；日志位于独立检出的 `target-siege-tests.log`。该记录早于部署生命周期修复，当前组合补丁的新增部署测试和新产物仍待重新构建验证。`git diff --check` 与对原始固定版本的 `git apply --check` 对当前组合补丁均通过。选定生命周期测试不能替代上游全量测试或 Paper 运行验收。

在该独立目录执行 `.\mvnw.cmd '-DskipTests' '-Dspotless.skip=true' package` 构建供隔离服务器验收的 StarCore 包；不执行 `install`，保留固定版本的本地 Maven 编译依赖。正式服部署应等待相应运行验收。

2026-11-20 第一版财政修复的独立 `package` 已成功，产物为该检出中的 `target/starcore-1.0.0.jar`，大小 19,507,084 字节，SHA-256 为 `3440310F482196EB322CC6EE792A94477C98EE91ABE63106F2AB1BF69109F8EA`；构建日志为 `target-siege-package.log`。此旧产物尚不包含部署生命周期修复，不应作为当前组合补丁的运行验证依据。产物没有安装到本地 Maven 仓库或覆盖原固定依赖。

2026-11-20 组合补丁已重新完整编译生产源码与测试源码，并通过四个选定套件：财政生命周期 3 项、部署生命周期 3 项、模块管理 8 项、军队扩展生命周期 6 项，共 20 项，0 失败、0 错误、0 跳过。构建完成于 13:54:02，用时 3 分 37 秒，日志为 `target-siege-tests-combined.log`。组合包另行构建，Paper 围城和部署验收仍以主验收记录为准；该结果不替代上游全部测试。

组合补丁独立 `package` 于 14:00:32 成功，用时 2 分 59 秒；产物仍为 `target/starcore-1.0.0.jar`，SHA-256 `676CF4E4D6AE52183AA80DB13A6E4A2E486A1AF93AE965A0176B08108C42AFC3`。日志为 `target-siege-package-combined.log`。该包包含财政与部署两项修复，未执行 `install`。

隔离 Paper 已实际验证该组合包：公开创建和部署器械后，等待正常分钟更新进入 `READY`，再公开开始真实围城；StarSupply 压力达到配置上限，公开撤退后下周期恢复普通需求。真实军队战斗也证明后勤倍率进入实际伤害。详细运行环境、哈希和证据见 [Paper 验收记录](../PAPER-ACCEPTANCE.md)；验收后隔离服恢复原 StarCore JAR，原上游与正式服未被替换。

复现工作目录为 `C:\Users\Administrator\Documents\starsupply-deps\starcore-siege-fix`，其 HEAD 保持上述固定提交，改动只包含此补丁的生产文件和新增测试。原检出 `starsupply-deps\starcore` 未被修改。

## 实验 Folia 后勤 profile 与构建边界

另一个独立检出 `starcore-folia` 正在迁移 StarSupply 所需的上游模块。实验开关为 `platform.folia-logistics-profile`，默认 `false`；启用后，模块范围限制为 `nation`、`economy`、`government`、`resolution`、`treasury`、`diplomacy`、`resource`、`technology`、`policy`、`officer`、`army`、`war`、`siege`、`taxation`、`event`。Treasury 仍注册 loan、budget、bankruptcy 权威服务。实验源码已导出为 [完整 Folia 后勤补丁](starcore-folia-logistics.patch)，包含上方攻城修复，应直接应用于固定原始提交，不要叠加旧攻城补丁。

实验迁移使用显式全局、实体和区域调度，保留服务对权威数据的串行访问。覆盖范围包括军队与攻城周期、战争通知与动画、国家异步传送及退款台账、财政与政策科技周期，以及补给线路和战争模型的并发快照。玩家操作在实体所有权下执行，固定位置效果使用区域任务；关键确认写入与失败补偿仍保留原有持久确认语义，不将这些路径描述为全部异步。

2026-11-20 17:21:56 的历史 `package` 使用 Java 21 和 Maven 3.9.11，重新编译 2234 个生产文件、1030 个测试文件。选中 971 项测试，其中 **967 项实际执行并通过，4 项跳过，0 失败、0 错误**。两项 MySQL 集成测试因未设置 `starcore.mysql.it` 而跳过；另外两项旧 `WarModuleAuthorityTest` 已因引用移除的方法而标记废弃。选定范围、具体用例名与原始跳过理由见 [上游定向测试记录](../verification/folia-upstream-targeted-tests-20261120.json)，不能把四项跳过计为执行通过。

该批次全部 106 个改动或新增 Java 文件通过定向 Spotless apply/check；浅历史检出显式使用 `ratchetFrom=HEAD`。构建前后登记的 107 项改动或新增 `src` 文件，内容哈希及路径集合均无变化。这是该批改动的冻结核验，不是整个上游仓库所有源文件的哈希清单。历史 JAR 大小 19,546,152 字节，SHA-256 `5643DBBBA0E61F7B267491716FCDA561468E68C82816B5B2EA20ABA7A0D82F60`；未执行 `install`。后续可选命令与 profile 注册修订需另记新构建和新哈希，不能沿用这份历史产物的验证结论。

17:26:48 的后续构建只追加 `StarCorePlugin` 与 `EconomyCommand` 两类源改动，处理缩减 profile 下未初始化的 HUD/旧压测命令注册，以及排行榜数据读取与玩家回调边界；34 项针对性测试实际执行通过，0 失败、0 错误、0 跳过。该新源包 SHA-256 为 `CDD264D1D5A4F4836D91CC645EB3DDD1A14342FF69F81F7146040CD3E4A40C74`。前述 971 项没有在这个新包上全量重跑，两次测试结果不能当作一个未发生的运行。

为后续隔离 Folia 运行测试，单独测试副本只覆盖插件描述符的 `folia-supported: true`，使运行副本的哈希区别于源构建：StarCore 为 `0C0D340C40E540674F9DE7E9BB03216894D26C78C51D4350DD331C07C807D509`，StarSupply 为 `16A1ED43CB3AC67F7992BA6C6DF79B78683983B8EE4105B0B6AB6309978A867B`。这些副本描述符不改变主发布声明；本节记录的是测试准备与构建证据，没有 Folia 现场通过结论。

这 971 项记录不包含 Folia 启动、多区域玩家、真实运输、重启或停服现场验收。当前主发布插件仍未声明 `folia-supported`，完整组合支持尚未完成；上述定向测试也不替代上游全量测试。后续运行验收应明确关联实际部署的 JAR 哈希、启用的模块和选装插件版本。

补丁对原始固定检出的 `git apply --check` 已通过。可在独立目录复现，不覆盖原始 Maven 依赖：

```powershell
git worktree add --detach C:\path\to\starcore-folia f769e62799e769a18fe51305b545869a17f80cd1
Set-Location C:\path\to\starcore-folia
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
# 完整定向 selector 见上方 JSON；浅历史使用 HEAD，避免不存在的 HEAD^。
.\mvnw.cmd '-Dspotless.ratchetFrom=HEAD' '-Dtest=CompatSchedulerTest,*Folia*Test,ModuleManagerTest,ArmyService*Test,*Siege*Test,War*Test,SupplyLine*Test,Battlefield*Test' package
```

该源码补丁保留生产描述符的保守状态。隔离运行夹具只对测试 JAR 增加 Folia 加载标识，并分别记录修改前后哈希；这允许实际检查线程归属，不构成发布支持声明。Profile 下的可选现代 HUD 和压力测试命令不注册，国家及 StarSupply 常规 GUI 继续提供。Folia 下停用 StarCore 时，其他实体线程上的瞬态科技属性随玩家重登或服务器重启清理；不支持通过热重载验证正式服生命周期。
