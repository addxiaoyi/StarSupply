# 军事条约真实权威联合协议 A12 开发检查点

日期：2026-11-27，Asia/Shanghai。

本阶段将 A11 军事接收器与真实 War、Diplomacy 服务连接，支持原生政治＋军事 manifest 的准入、结束、来源决定和完整向量释放。测试直接驱动原生协议步骤，三类权威均使用真实文件或持久 SQLite；生产 `PeaceTreatyService` 的批次编排和模块注册仍待接线，玩家接受入口继续拒绝军事条款。

## manifest 与来源路由

新增 `diplomacyAndMilitary` manifest 构造器。外交只接收政治条款索引，Army 接收全部军事条款索引，保留完整原始 envelope；两个 receiver 均使用 v2 且 scope 必须与全局 scope 完全相同。仅军事条约仍保留外交 receiver，以结束战争关系；其政治索引为空。未知条款、未知 receiver、错误版本、错分索引、缩小 scope 或没有军事条款却增加 military receiver 均拒绝。

Diplomacy 按 receiver ID 选取自己的 entry，不依赖列表首项。War 分别持有外交和可选军事证明读取器，旧单参数绑定保留纯外交行为；绑定后的读取器集合不可替换。新通用读取器接口默认拒绝声明持久化能力，具体实现必须提供权威能力检查。

## 准入、结束与释放

新 War 准入和结束要求 manifest 内每个接收器都提供完全相同 binding 的 BOUND 证明，并逐一检查持久化能力。未注册、未绑定、不可用或生命周期改变的 military 不能被跳过；每次外部回调后复查服务身份和运行状态。多个接收器的证明向量再读一次并比较，避免后一个回调改变已读取的外交状态后继续使用旧结果。

War 结束后先由外交读取原结束回执、完成战争镜像和最终关系决定，随后军事接收器依 A11 规则核对原 War 与外交证据并提交。外交 APPLIED 才裁军；外交事件否决、取消或 SUPERSEDED 均不裁军。即使外交已经终态，只要 Army 仍为 BOUND，War 释放仍失败且保留 scope。最终释放永久保存按 manifest 排序、完整且 phase 一致的两项证明，已有原生文件容量预算也覆盖这两个证明。

## 验证与交付边界

真实三权威测试在外交预留、Army 预留、外交绑定、Army 绑定、War 准入、War 结束、战争镜像、外交决定、Army 决定、War 释放十处提交后丢回复并重启。验证原 envelope/计划/binding 不变，战争只结束一次、军队只裁撤一次、双证明释放重放一致。另覆盖仅军事缴械、稀疏混合索引、外交否决/取消/取代、Army 未完成时拒绝释放，以及来源读取期间的服务替换、关闭和外交变化。

这组测试没有通过生产接受入口编排混合和约。已有纯政治和约、原有 receiver、Army、外交镜像、国家关闭及 War 生命周期通过相关分批回归；最终清单、XML/日志去重、空目录增量编译和独立补丁重放详见[机器证据](../../verification/workspace-army-military-treaty-authorities-development-20261127.json)。所有本任务 JVM 使用 96 MiB heap、SerialGC 和单处理器串行运行。

最终六批共 **607 项 / 36 个 suite**，失败、错误和跳过均为零：native 132、treaty 138、compatibility 148、diplomacy 84、army 48、lifecycle 57。6 个主源码编译为 28 个 class，2 个测试源码编译为 6 个 class；全部 8 个变更 Java 源通过 Spotless。

累计补丁含 **345 个文件差异 / 3,857,933 bytes**，SHA-256 为 `db346ffc362d0e301766b7db21cd8d443c1ab4ee31adfd3b5c7f863dc86a0bf2`。独立正向、反向、diff 检查及 3,836 个源码文件与 API/POM 清单一致性通过。

`army-military-treaty-authorities-20261127.patch` 基于固定第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，包含 A11 及此前检查点，替代旧累计开发补丁，不能叠加。生产 Army 后端仍固定文件；SQLite 由注入验证。未生成新 JAR/review ZIP，尚非全仓干净构建；未启动服务器或修改世界、玩家存档。

下一步持久化生产批次内的军事计划与终态证明，补齐未绑定阶段取消和绑定后恢复，接通 Army/War 模块启停，再启用军事与政治＋军事接受入口。旧内存军限迁移与解除策略、领土两套权威、其他资金及模块生命周期等剩余项继续见[代码清单](../../CODE-CLOSURE.md)。全部阶段尚未完成。
