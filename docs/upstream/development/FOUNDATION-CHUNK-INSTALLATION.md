# Foundation 持久安装绑定 T19

日期：2026-11-29，Asia/Shanghai。

Foundation 区块服务现在持久记录原存储目录、文件/SQL选择、实际数据库身份摘要和原权威 UUID，并在每次读写成功返回前保存最后确认的 ledger revision 与完整内容摘要。重启后，保留该独立见证文件时，恢复旧版本、替换完整权威、同版本改写内容或切换后端均会拒绝；已绑定数据缺失不能重新初始化或再次导入旧文件。生产 host 和公开持久化构造器使用同一安装绑定。

`ChunkTerritoryInstallationRecord` 使用 `DurablePropertiesStore` 保存选择及确认见证；完整底层 load/save、读回与确认共同持有 `FileAuthorityLock`。SQL 绑定先核对必需表，已绑定存储不执行建表 DDL；缺表、缺身份或版本行不会补写修复。首次旧文件/SQL接管保留原回执及实际发布的 UUID，底层初始化成功而见证确认失败时，恢复会继续同一身份。待决预留的未来确认版本空间在实际提交前检查。

`FoundationTerritoryDatabase` 在实际使用的每个连接上核对持久 SQLite main 规范路径，或 MySQL metadata URL/catalog 身份摘要，并固定本次运行的 DataSource 对象。连接获取、初始化及最终确认检查数据库生命周期和来源；验证失败会关闭连接，诊断不传播可能含 JDBC 凭据的原始异常文本。MySQL 仅使用 metadata 替身测试，尚未经过真实 MySQL 服务验证。

**检测边界：** 见证在底层提交和精确读回后、成功返回前写入，目前没有 write-ahead pending candidate。底层已提交但见证尚未确认的间隔内发生外部回滚，不能保证全部检测；权威与全部见证文件同时恢复旧版本也无法检测，需要独立外部证据。本阶段只对保留下来的最后确认见证负责，不构成任意整库回滚防护。

最终六批 **945项 / 71个suite** 全部通过：installation 51，foundation 301，nation 357，production 77，mixed 128，persistence 31；0失败、错误、跳过。8个生产源空目录编译为15个class，22个测试源编译为54个class；11个Java格式通过，API基线未更新。96 MiB heap、SerialGC、单处理器，编译与测试严格串行。见[机器证据](../../verification/workspace-foundation-chunk-installation-development-20261129.json)；每批日志与XML一致，最终报告已独立归档，重复或失败尝试不计入总数。

首次安装专项51项有2处测试清理错误：Mockito使用 `when(getConnection())` 重设行为时执行了旧的真实SQLite回答，丢弃连接导致Windows临时目录删除失败。已改用 `doReturn/doThrow`，完整专项重跑；该失败尝试及原XML独立保存，不计入最终通过总数。


专项覆盖真实文件/SQLite重启、首次接管、完整旧库恢复、权威替换、同版本自洽改写、后端/数据库切换、缺失文件/表/身份行、见证失败及已发布后丢回复、初始化中断后的原身份恢复、共享安装的两个owner、旧SQL升级、版本容量耗尽和提交期间来源变化。既有Foundation、Nation创建/领土/分裂/解散、实际纯Foundation和混合和约及持久化/API回归一并通过。Bukkit与调度器仍为替身；未进行实际服务器进程崩溃或SQL多进程验收。

累计补丁 `foundation-chunk-installation-20261129.patch`：**5,163,287字节 / 443个文件diff**，SHA256：`11f8882cfe773166e681891a2342801603fa01ec7683ca933e5c2e7f892f25c8`。固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`；替代旧累计补丁，不能叠加。两个独立索引正反应用后，3,908个src及API/pom规范字节一致。

**全部阶段尚未完成。** 攻城来源与接收器、旧军限迁移/权限化解除、租赁/赔款/贡纳/贷款资金、海军运输/港口、伤兵回补/学说、预算取消/结转、其他模块权限/线程/生命周期及归档继续开发。全仓clean build、新JAR/review ZIP、最终Core＋Supply、SQL多进程/MySQL与用户推迟的实服/客户端验收仍开放。未启动服务器或修改世界、玩家存档。
