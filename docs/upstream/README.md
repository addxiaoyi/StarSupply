# StarCore 攻城财政依赖修复

补丁 [starcore-siege-treasury.patch](starcore-siege-treasury.patch) 适用于公开仓库 `addxiaoyi/starcore` 的固定提交 `f769e62799e769a18fe51305b545869a17f80cd1`。这是单独、可审查的上游源码修复，不包含编译产物，也未推送到远端。StarSupply 的编译 API 仍以该固定提交为基础。

固定版本的 `SiegeModule` 只声明 `nation` 和 `economy` 依赖，但在创建器械时必须通过 `TreasuryService` 查询、扣除国家资金。模块管理器可能先启用 `siege`、再启用 `treasury`；原来的可选查询把 null 永久传入攻城服务，后续创建器械便会失败。StarSupply 读取真实围城状态的功能需要服务器上的权威攻城服务正常运行。

补丁只改变两项业务约束：

- 将 `treasury` 加入 `SiegeModule` 显式依赖，保证财政服务先启用。
- 使用 `serviceRegistry.require(TreasuryService.class)`，服务缺失时在创建定时攻城服务之前拒绝启动。

新增 `SiegeModuleTreasuryLifecycleTest` 包含三项运行行为测试：真实 `ModuleManager` 在反向注册顺序下仍先注册财政服务，再创建攻城服务；创建真实器械调用所注册的财政权威；财政模块缺失或直接启用时未注册财政服务，都不会创建攻城定时任务。测试还检查正常关闭释放服务、取消两项定时任务。

## 应用与验证

在独立干净检出上操作，保留原服务器和原 StarCore 检出：

```powershell
git worktree add --detach C:\path\to\starcore-siege-fix f769e62799e769a18fe51305b545869a17f80cd1
Set-Location C:\path\to\starcore-siege-fix
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-siege-treasury.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-siege-treasury.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1'
.\mvnw.cmd '-Dspotless.skip=true' '-Dtest=SiegeModuleTreasuryLifecycleTest,ModuleManagerTest,ArmyExtensionLifecycleTest' test
```

2026-11-20 已在该独立检出用 Java 21 执行上述定向测试：`SiegeModuleTreasuryLifecycleTest` 3 项、`ModuleManagerTest` 8 项、`ArmyExtensionLifecycleTest` 6 项，共 17 项全部通过，0 失败、0 错误、0 跳过。Maven 从源码重新编译了 2233 个生产文件和 1008 个测试文件；日志位于独立检出的 `target-siege-tests.log`。`git diff --check` 与对原始固定版本的 `git apply --check` 也均通过。该结果只覆盖选定生命周期测试，不代表上游全量测试或 Paper 运行验收已完成。

在该独立目录执行 `.\mvnw.cmd '-DskipTests' '-Dspotless.skip=true' package` 构建供隔离服务器验收的 StarCore 包；不执行 `install`，保留固定版本的本地 Maven 编译依赖。正式服部署应等待相应运行验收。

2026-11-20 的独立 `package` 已成功，产物为该检出中的 `target/starcore-1.0.0.jar`，大小 19,507,084 字节，SHA-256 为 `3440310F482196EB322CC6EE792A94477C98EE91ABE63106F2AB1BF69109F8EA`；构建日志为 `target-siege-package.log`。产物仅供后续隔离 Paper 验收使用，没有安装到本地 Maven 仓库或覆盖原固定依赖。

复现工作目录为 `C:\Users\Administrator\Documents\starsupply-deps\starcore-siege-fix`，其 HEAD 保持上述固定提交，改动只包含此补丁的生产文件和新增测试。原检出 `starsupply-deps\starcore` 未被修改。
