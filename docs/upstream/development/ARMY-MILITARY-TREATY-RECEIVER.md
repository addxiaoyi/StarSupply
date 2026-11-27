# Army 军事条约接收器 A11 开发检查点

日期：2026-11-27，Asia/Shanghai。

本阶段为 A10 军事权威增加完整条约来源、不可变绑定和终态证明。接收器重新读取 War 结束或取消回执，以及外交最终证明，核对原 binding 后才提交裁军或取消。生产和约尚未接入本接收器，继续拒绝军事及混合条款；本阶段测试使用真实 Army 文件/SQLite，War 与外交来源是可控接口测试替身。

## 完整原条款与绑定

Army 永久保存完整 `TreatyEffectManifest.Envelope`。所有军事条款都参与计算：兵数上限取最小值、缴械标志取 OR，同时保留每条原描述与索引。只接受政治和军事两种原条款，至少有一项军事条款；manifest 必须恰好包含 diplomacy 与 military 两个 v2 receiver，条款分配完整且两者 scope 均等于全局 scope。

本地计划哈希固定包含完整原 envelope、原军事请求和冻结军队，排除后续 binding、决定和 Lift。预留时即检查计划哈希可编码，避免原记录能保存、后续绑定或释放却因容量不足失败。binding 必须独立落盘，之后不得替换，也不能以旧布尔决定入口绕过来源证明。

## 来源核对与恢复

接收器核对 War end/cancel 互斥、请求与原 binding 完全一致，以及 diplomacy 的 receiver、entry、binding 和终态。END_APPLIED / END_REJECTED 必须对应真实读取的原 War end；CANCEL_SEAL 必须对应原取消回执；LOCAL_SUPERSEDED 也必须已有 War end 或取消。提交前再次读取两类来源并确认未改变，服务替换、关闭、重入或回执变化都保留冻结状态。

外交 APPLIED 才允许 Army APPLIED；外交 CANCELLED/SUPERSEDED 均取消本地军事效果，导出的 effect proof 保留外交来源 phase 和完整证据。原外交证据、全部裁军墓碑与本地终态同 authority 提交。终态重试直接返回原结果，不要求来源服务继续在线。未绑定预留可以永久 abandon；绑定后必须按来源终态完成，不能直接释放。

本地决定时间不早于原接受时间、来源证明及 War 结束时间，并纳入冻结期间其他军限解除的后续时间。时间推进不会改写原来源证明。多个限制、同号重放和未来 Lift 仍遵守 A10 的永久身份与全部 writer 约束。

## 格式、容量和验证边界

新增 schema9 / row13 保存完整 envelope 与可选绑定/终态证明；旧 standalone 军限仍使用 row12，保留 schema1–8 读取。存储禁止丢失或改写原条款、绑定和来源终态，不能在首次预留中塞入绑定，也不能跳过绑定提交直接决定。未绑定操作保留三次未来 revision，已绑定保留两次；模拟未来完整证明、裁军和解除，以预留可收尾的序列化容量。

文件与持久 SQLite 的专项测试覆盖四种来源终态、重启、预留/绑定/决定各提交前后丢确认、错误来源与绑定、生命周期变化、不可变历史、完整原条款、时间顺序和精确容量。格式化、空目录增量编译、最终分批回归与独立补丁重放结果见[机器证据](../../verification/workspace-army-military-treaty-receiver-development-20261127.json)。验证采用 96 MiB heap、SerialGC、单处理器，构建与测试串行运行。

最终五批共 **1,514 项 / 115 个 suite**，失败、错误和跳过均为零：focused 848、army 538、persistence 61、treasury 25、disband 42。7 个主源码编译为 17 个 class，6 个测试源码（含共享夹具）编译为 7 个 class；全部 13 个变更 Java 源通过 Spotless。

累计补丁含 **344 个文件差异 / 3,833,334 bytes**，SHA-256 为 `846c235737a246afe8a2269427c2bc32ebbebed9955bcb398a26a9f88f84114b`。独立正向、反向、diff 检查及 3,835 个源码文件与 API/POM 清单一致性通过。

`army-military-treaty-receiver-20261127.patch` 基于固定第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，包含 A10 及此前检查点，替代旧累计开发补丁，不能叠加。生产 Army 后端仍固定文件；SQLite 由注入验证。未生成新 JAR/review ZIP，尚非全仓干净构建；未启动服务器或修改世界、玩家存档。

下一步在真实 PeaceTreatyBatch、Diplomacy、War 和 Army 运行时接通完整混合计划、逐 receiver 终态及释放向量，并验证联合故障恢复后启用军事条款。旧内存军限迁移、基于来源证明的解除策略及权限、领土两套权威、其他资金与模块生命周期等剩余项继续见[代码清单](../../CODE-CLOSURE.md)。全部阶段尚未完成。
