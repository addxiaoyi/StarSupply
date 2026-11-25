# 税款回执与自动恢复

`starcore-tax-funding-receipts.patch` 在后勤补丁、玩家资金补丁之后应用。StarSupply 仍针对原固定 API 编译，通过公开方法绑定新增 `TaxFundingService`；税务接口缺失时保留旧的保守路径。

## 金额与权威

`fundTaxWithReceipt(TaxQuote)` 只处理已经从卖家毛额中扣留的正额 TARIFF / BUSINESS_TAX，不再向玩家收费。它锁定原报价的交易 UUID、国家、税种、玩家、毛额和税额；已扣留报价的恢复不会重新按当前税率计算。`taxFundingReceipt(UUID)` 返回包含完整原报价及毫秒级入账时间的不可变结果。

`treasury_funded_taxes` 增加 `funding_protocol`，旧行迁移为 `LEGACY`，新协议为 `RECEIPT_V1`。这张表保存意图，不证明钱已到账。完整税务身份另保存在固定国库键 `starcore:tax-funding:v1:<UUID>` 的 `NationCreditService` 回执说明中，和国库余额同一快照提交；每次恢复都核对全部身份字段。认领后中断可以继续，认领失败不调用国库，国库回滚或响应丢失均重试原请求。旧认领不会转成新支付，新协议不能使用旧 `reverseFundedTax` 释放或复用 UUID。

国库写入结果不明时，余额、回执及后续写入先恢复权威快照。恢复读取失败会继续拒绝操作，关闭刷盘也不能把旧内存覆盖回存储。新写出的国库快照带格式版本并要求完整规范解码，损坏的金额、时间、计数或缺失字段不能被静默丢弃后当作“没有回执”。旧无版本快照保留原读取兼容规则。

普通税务事件仍为提交后的尽力通知，不是可靠消息队列；事件失败不能撤销已有资金和回执。已有 `getTaxHistory` 是内存历史，新接口查询/恢复回执时补入该实例历史；持久核验应使用按 UUID 查询的权威回执。

## StarSupply 阶段

新 `starsupply:tax:<结算UUID>` 意图先在 TransactionFabric 保存 `taxFundingProtocol=1`，再把本地 SQL 从 QUOTED 推到 FUNDING。只有回执完整匹配后才能确认税额并开放实物批次。国库、事务记录或 SQL 结果写回中断时，主结算保持 PENDING 并按原编号重试，卖家净额不重复支付。

旧 FUNDING 没有协议标记时继续 REVIEW；已有 REVIEW 不自动解冻。已标记交易缺少接口、字段不符或终态回执消失时继续冻结，不降级。管理员的 funded / unfunded 对新协议都必须服从 Core 回执，不能制造到账事实或要求再付一遍。老协议仍按独立证据执行原核对流程。

## 复现与验证范围

在原始 StarCore 提交 `f769e62799e769a18fe51305b545869a17f80cd1` 的独立检出中依次应用：

```powershell
git apply C:\path\to\StarSupply\docs\upstream\starcore-folia-logistics.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-player-money-receipts.patch
git apply --check C:\path\to\StarSupply\docs\upstream\starcore-tax-funding-receipts.patch
git apply C:\path\to\StarSupply\docs\upstream\starcore-tax-funding-receipts.patch
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MAVEN_OPTS = '-Xms32m -Xmx384m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Djdk.attach.allowAttachSelf=true'
.\mvnw.cmd -B -ntp '-Dspotless.ratchetFrom=HEAD' '-DforkCount=0' `
  '-Dtest=dev.starcore.starcore.module.treasury.*Test,FundedTaxMigrationTest,PublicApiCompatibilityTest' package
```

本机采用低内存增量编译，在此前已编译全部生产源码的基线上编译五个改动生产源和 27 个财政测试源，再运行财政定向回归。该记录不是 Core 全仓 Maven verify。打包仍需 POM 的两个 shade 阶段，独立 javac 后不能只执行默认 shade：

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.jar.forceCreation=true' jar:jar `
  shade:shade@minimize shade:shade@restore-reflective-runtime
```

StarSupply 先执行普通 `mvnw verify`，再运行 `tools/test-player-money.ps1 -StarCoreJar <新兼容包> -JavaHome <JDK21>`。该脚本现在验证玩家与税款两种接口，并重跑完整测试套件；原 Maven Core 依赖不被替换。结果、源码/产物哈希、补丁复现、故障路径和环境限制见[验证记录](../verification/workspace-tax-funding-receipts-20261126.json)。

本轮没有启动 Paper/Folia，没有配置 MySQL 集成测试。税务意图、国库回执、玩家资金、TransactionFabric、StarSupply 数据库和玩家存档须成组备份；不应让不认识新协议的旧 Core 写入新数据。完整 Folia 范围、当前组合、六类生存流程和图形资源包仍待独立验收。
