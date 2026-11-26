# 原生输入与外交派生事件开发检查点

日期：2026-11-27。此为源码开发检查点，**全部阶段尚未完成**。发布 JAR、安装序列和 review ZIP 仍为第二十份补丁，不包含本页新增实现。

## 重放方式

累计补丁：[native-input-outbox-20261127.patch](native-input-outbox-20261127.patch)。只应用于第二十份固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，包含此前战争、赔款、共享解散、文件锁、宣战费用恢复和战争—外交镜像开发成果，替代所有旧开发补丁；不要叠加旧开发补丁，也不要加入当前二十份成品安装序列。

- 补丁大小：1,520,178 bytes，179 个文件差异。
- SHA-256：`f509b15ae762e1e91770af986b3a53a2aa5ebde6e9390427b15ff4be08eaba0b`。
- 独立工作树通过正向检查、实际应用、反向检查与 diff whitespace 检查；全部 3,718 个 src 文件、API 基线和 pom.xml 经 LF 规范化后与冻结源码一致。
- 机器证据：[workspace-native-input-outbox-development-20261127.json](../../verification/workspace-native-input-outbox-development-20261127.json)。证据中列明源码、编译参数、class、最终测试 XML 和日志的哈希；外部工作区日志路径仅是本机证据位置。

## 外交派生事件

联盟建立和解除事件与关系效果在同一外交权威快照提交，保存稳定 UUID、有序端点、原时间和解除原因。保存失败、无消费者或 ACK 失败保留原项；每批至多 100 项，启动后延迟 20 ticks、之后每 200 ticks 重试。重试及普通修改从最新权威合并，退役保留历史 pending。

EventModule 新增持久 inbox：事件日志与 payload-bound 永久 receipt 同次提交，两国日志 ID 确定生成；清日志保留 receipt，旧投递不能复活已清记录。文件使用 `events-authority.properties`，SQL 使用 `starcore_event_authority`；首次严格导入旧存储，固定后端并校验版本、校验和和读回结果，SQL 失败不能降级文件。普通日志写入和清理改为同步 copy-before-commit，防止旧异步快照覆盖 receipt。

producer 先取得 `recordAllianceOnce` 的耐久 ACK，再发布带编号 bus 通知，最后持久移除 pending。**ACK 只证明 EventModule 日志已接收，第三方 bus subscriber 仍可能收到重复通知**，不承诺所有订阅副作用 exactly-once。旧事件构造器保留，新事件增加编号与原时间；旧构造对象的对应 getter 返回 null。仅实现旧 EventService 的扩展默认不确认新协议。

外交和 EventModule 的启停、启用失败清理、旧回调、consumer 阻塞、重入、ACK 冲突、文件与 SQLite 恢复均有专项测试。producer 调用外部 consumer 时不持 module monitor，停用能够完成；未证明第三方 consumer 自身可中断或回滚。

## 公共原生输入

全部 15 个内部 `BookInputSupport.prepare` 入口迁移为 callback API，覆盖国库、外交、多国联盟、交易、区域、家、仓库、宠物、社交和仲裁；不再替换真实背包物品或伪造 PlayerEditBookEvent。使用 Paper 1.21.11 原生 dialog，绑定原连接，五分钟有效且仅消费一次；替换、断线、插件停用、过期和 owner guard 会使未准入回调失效，发布与响应走玩家实体调度。

保留三参数 `prepare` 的方法签名，但该旧入口现在抛 `UnsupportedOperationException`，外部调用者必须迁移到显式 callback；这不是行为完全兼容。`restore`/`clear` 只取消待处理输入，历史丢失物品不会自动恢复。构造中定时器初始化失败会停用部分对象、撤销监听器并重抛原异常。

本轮补充的业务保护包括：

- Treasury、Alliance 使用实际 `PermissionUtil` 权限路径，不依赖对所有本国成员返回 true 的旧 `Nation.hasPermission(String)`。输入和最终确认绑定原玩家、国家、操作权限；偿债同时复验原债务 ID 与所属国家，拒绝 A 国旧菜单在切换 B 国后扣 A 国资金。
- Treasury 输入与确认采用独立 listener 生命周期锁，启停使用 transition lock；停用先关闭 listener，再取 module monitor，避免与 LoanService 的 loan→module 锁序反转。提示窗口发布保持锁外。贷款本身的跨权威付款恢复不在本轮完成范围。
- Warehouse 和 PlayerTrade 在原生窗口关闭旧库存后重开并恢复 GUI 注册，保留点击路由和拖拽保护；Warehouse 停用永久拒绝迟到写入，即使服务停止抛错。
- Arbitration 绑定原案件和国家，Zone 绑定原国家/区域并重新检查归属与权限，Alliance 重新检查 officer/leader；相关 owner 在模块停用时关闭待处理回调。

这不代表 Home/Pet/Social 等模块的所有权限、线程和启停路径已审计完成。社交旧 future 调度、其他城市交互及同城公告内容版本仍保留在未闭环清单。

## 最终自动化证据

冻结源码通过两批 **1,162 项 / 197 套件**，0 失败、错误或跳过：GUI/国库/联盟/城市等 710 项，外交/事件与 API 452 项。按测试类和 case 名去重，并核对实际 XML 与日志总数；早期 75 项、451 项、基础输入 20 项及历史 986/2,210 项不重复累计。

39 个生产源生成 66 个 class、24 个测试源生成 34 个 class。各批在空输出目录编译，按最后覆盖结果核对与最终测试 classpath 逐字节一致，覆盖全部本轮改动 Java 源。API 基线检查通过，未使用更新标记。验证串行，JDK 21、Maven 3.9.16、单处理器、javac/test heap 160 MiB；本轮 native 测试编译曾发生 JVM 本地内存不足，恢复内存后换空目录重编成功，失败尝试及两个测试夹具错误均保留且不计入最终通过数。

这不是全仓干净构建。新成品及其 StarSupply 配套回归、完整解散进程强杀、SQL 多进程和最新 MySQL 仍待验证。未启动服务器或修改世界、玩家存档；原生窗口客户端体验、完整 Paper/Folia、插件组合与生存链实服按用户要求待验，profile 和生产 Folia 声明未扩大。

## 后续实现

普通双边结盟/解约仍需源 intent 与外交接收端同号 receipt，以及防止 WAR 或后续关系修改被旧 finalize 复活的 ownership/generation。和约政治/领土/军队接收端、圈地派生通知、旧账和其他付款取消、海军真实运兵/港口、伤兵生成与回补、学说实效及耐久费用、预算运维、失效奖励、归档容量和离线世界继续见[代码闭环清单](../../CODE-CLOSURE.md)。
