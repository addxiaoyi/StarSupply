# 玩家资金耐久回执

`starcore-player-money-receipts.patch` 在 StarCore 权威余额存储中增加业务 UUID 回执，供 StarSupply 的采购款、运费、理赔、保费扣款及返还使用。它是现有 `starcore-folia-logistics.patch` 的后续补丁：先应用后勤补丁，再应用本补丁。它不声明完整 Folia 支持。

## 接口与持久化

公开 `PlayerMoneyService.applyMoney(Request)` 和 `moneyReceipt(UUID)` 返回不可变回执。请求绑定操作 UUID、玩家 UUID、CREDIT/DEBIT、两位小数金额和业务来源；相同 UUID 不能换输入。APPLIED、INSUFFICIENT_FUNDS、EXTENSION_REJECTED、BALANCE_LIMIT 都是耐久终态。异常不是拒付证明；业务方只能重试同一请求或继续冻结。旧 `EconomyService` 布尔接口保持原签名。

SQL 在同一事务中写余额与 `starcore_player_money_receipts`；已存在的回执不能改写，缺少已存回执的旧快照也不能覆盖余额。SQLite 旧 `DECIMAL` 列迁移为 TEXT，避免大金额经 REAL 转换丢失分位；迁移保留当前存储值、索引及触发器，不补算过去已经损失的精度。MySQL 保持 DECIMAL(19,2)，此次没有 MySQL 实例验收。

文件模式将回执与余额写进同一 `economy/player-balances.properties` 原子快照，使用 `money-receipt:<UUID>` 键；文件到 SQL 的切换一并迁移。新回执操作先排空并保存先前异步余额快照，再提交新余额和回执。结果不明时先从权威存储恢复；恢复失败会阻止继续读回执或写旧余额，连关闭刷盘也不能覆盖不明状态。原有玩家经济事件仍在成功提交后发布，事件不是保证重发的消息队列。

同一服务器实例拥有余额存储。备份/还原须同时处理 Core 余额、国库、TransactionFabric、StarSupply 数据库与玩家存档；已经产生回执的数据不应交给不识别回执的旧 Core 写入。

## 应用与构建

在独立 StarCore 检出中执行，保持 StarSupply 编译依赖仍为原固定 API：

```powershell
git checkout --detach f769e62799e769a18fe51305b545869a17f80cd1
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-player-money-receipts.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-player-money-receipts.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-tax-funding-receipts.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xms32m -Xmx384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Djdk.attach.allowAttachSelf=true'
.\mvnw.cmd -B -ntp '-Dspotless.ratchetFrom=HEAD' '-DforkCount=0' `
  '-Dtest=PlayerMoneyReceiptTest,InternalEconomy*Test,SqlBalanceStorageTest,DatabaseAwareBalanceStorageTest,PublicApiCompatibilityTest' package
```

该选择器只运行相关回归，不是 Core 全仓测试。机器内存不足时，本轮先前 Maven 重编译因原生内存耗尽失败；随后用独立 256 MiB `javac` 编译全部 2,238 个生产源文件，并用 192 MiB 编译新测试，再运行 `surefire:test`。77 项相关测试全部通过，其中 17 项为新增回执用例。六个改动 Java 文件通过定向 Spotless 检查。低内存编译与日志范围记录在 [验证 JSON](../verification/workspace-player-money-receipts-20261125.json)，不能表述成 Maven 全量 verify。

独立编译完成后，打包必须使用 POM 的两个 shade 执行阶段，保留 JDBC SPI 和 Adventure 反射运行库：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.jar.forceCreation=true' jar:jar `
  shade:shade@minimize shade:shade@restore-reflective-runtime
```

StarSupply 仍使用原固定依赖执行 `mvnw verify`。然后可单独用新兼容包重跑已编译测试，完整替换测试类路径中的 Core，且不改写 Maven 仓库：

```powershell
.\tools\test-player-money.ps1 -JavaHome C:\path\to\jdk-21 `
  -StarCoreJar C:\path\to\starcore-folia\target\starcore-1.0.0.jar
```

StarSupply 通过公开方法反射桥读取新增接口；没有新接口时保留旧保守路径。新交易持久保存 `playerMoneyProtocol=1`，标记后的操作不能降级到普通余额接口。旧无标记的不明交易仍须独立核对，已有 REVIEW 也不自动解冻。人工“未到账”不能覆盖已到账回执。后续[税款补丁](TAX-FUNDING.md)已补齐新税款恢复；旧无标记税款继续核验。复现脚本现在同时运行玩家与税款用例，须使用三份补丁构建的兼容包。

本轮没有启动 Paper/Folia；完整组合、图形资源包和六类生存流程的现场验证仍待完成。
