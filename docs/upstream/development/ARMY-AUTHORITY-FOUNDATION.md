# Army 耐久权威基础层 A1

本阶段增加独立、不可变的军队 authority 模型、严格 codec/state validator、文件与 SQLite adapter，以及保全原始证据的旧格式导入器。它尚未接入 `ArmyService` 或 `CommanderService`，不能据此认定运行中的可变对象、直接 writer、军事条约限制或付费创建/补给已经修复。运行时接线须在后续检查点同时迁移所有直接 writer。

本检查点的最终三个批次通过 604 项测试、79 个套件：A1 专项 81、既有 Army 回归 462、持久化与 API 等定向回归 61；失败、错误、跳过均为 0。13 个生产源、7 个测试源分别在空目录编译为 26、9 个 class。全部 20 个改变的 Java 源通过定向格式检查，API 基线未更新。详细批次、源码/字节码哈希及累计重放见[机器证据](../../verification/workspace-army-authority-foundation-development-20261127.json)。

[累计补丁](army-authority-foundation-20261127.patch)含 264 个文件差异、2,706,742 字节，SHA-256 为 `d09aa51efab76a8c5f1008e7a8d02890ef133aaaa40529510ad63ae6a3da1880`。正向/反向/差异检查通过，独立重放的全部 3,776 个 `src` 文件、API 与 `pom.xml` 在 LF 规范化后与验证源码一致。

广泛 Army 回归曾发现两处沿用旧海军实现的生命周期夹具错误；仅更新 fixture，使其使用现有的耐久 spending/persistence、唯一维护任务及真实 service registry，并保留重复 shutdown 仅取消一次和停用后注销断言。原失败日志保留，失败尝试与较早重复专项不计入最终通过数。没有为测试弱化生产检查。

## 身份与永久历史

每支军队由 army UUID、incarnation UUID 和正 generation 共同标识。快照完整保存国家、类型、兵力、百分比生命/士气、补给、分钟消费余量、位置与创建/更新时间；时间保留纳秒，位置不依赖 Bukkit 世界查找。合法历史数据不新增“更新时间必须晚于创建时间”或当前兵力上限等迁移条件。

删除推进 token 并留下永久 tombstone。已退休身份不能被新建、恢复、清理或旧快照重新开放。物流 receipt 永久保存请求身份、请求/实际点数、目标军队与国家；新 receipt 还绑定 incarnation。旧 orphan receipt 保留为历史证据，并永久阻止复用其目标 army UUID。

严格提交与重载观察使用不同规则：真实提交要求 revision 和改变的 generation 各推进一步；重载可以观察其他 writer 已经完成的多个步骤，但仍拒绝历史证据、静态身份和终态倒退。新增物流 receipt 与精确的封顶补给变化属于同一次提交，不能顺带改变其他军队或其他业务字段。

## 固定后端与完整前像 CAS

adapter 比较完整 `actual.equals(expected)`，而非只比较 revision；调用方提供未增 revision 的业务 draft，adapter 以 checked arithmetic 产生下一个 revision，经过严格校验后调用共享 store 的完整前像 CAS。同步返回值取自确认后的 durable snapshot。冲突不自动重放旧 draft，读回失败不切换另一份 authority。

文件与 SQL 后端在构造时固定。A1 SQL 仅支持由真实连接元数据及数据库 URL 确认并固定的 SQLite；其他数据库不宣称已支持。完整 Properties envelope 在保存前执行真实序列化容量预检，涵盖迁移原文、tombstone 和永久 receipt；生产上限为 64 MiB。测试使用小预算走实际初始化和提交入口，避免为验证边界分配上限大小的数据。

SQLite 的 product/URL 前缀不足以证明持久性。每次连接在共享 store 建表或发布 authority 前执行 `PRAGMA database_list`，要求唯一、非空的持久 `main` 文件，验证其真实路径为 regular file，同时固定 URL 与真实路径。内存库、空临时 URL、resource URL、temp/attached schema 及后续身份变化均拒绝并关闭连接；真实 JDBC 反例与独立实例重启正例通过。不宣称防御文件系统层的恶意同路径替换。

## 显式、整体成功的旧数据导入

未初始化与已初始化空状态明确区分。只有显式 `initializeFromLegacy` 可从未初始化状态提交一次 READY baseline；普通 load 不调用 permissive legacy supplier，也不把缺失或损坏的新 authority 当作空数据重新导入。

导入器只接受接线层交付的 exact source bytes 与来源描述，不自行读取或恢复文件。任一坏 army、receipt、不明 ownership 或 opaque 记录都会使整份结果为 NOT_READY，不发布合法子集。原始字节、SHA-256、全部 raw records 和推断报告保留；源文件不被导入器重写。源字节上限为 8 MiB，在 Base64 解码前检查 canonical 编码与预测长度。

合法 destroyed 旧军队转为 generation 1 tombstone。缺失更新时间以 createdAt 推断；缺失 remainder/yaw/pitch 以有记录的零值推断；已知类型/状态别名可以接受，但必须记录 raw 值到 canonical key 的映射。未知 enum、越界和不可恢复的身份错误不能被默认值掩盖。

后续运行时迁移还须核对真实 `PersistenceService` 路径与恢复行为：现有 `loadProperties` 会调用恢复并可能改变源文件，不能把它的结果直接当作已经保全的原始字节证据。

## 后续接线边界

后续 A2 必须先完成严格迁移和 authority 初始化，再发布服务和调度任务；普通更新、战斗幸存者、restore、补给以及全部 Commander 技能必须统一经过 token/CAS。只替换 getter 或只迁移 `ArmyService` 会留下旁路写入。付费创建/补给仍需独立的原请求、国库回执与终态恢复协议；军事 receiver、真实增长限制和两套领土 authority 另行实现。

本轮未重跑会另外启动多个 JVM 的既有文件进程探针，保持单个 160 MiB JVM 的验证预算；已覆盖同进程独立 file/SQLite storage 实例。全仓干净构建、新 JAR/review ZIP、SQL 多进程/MySQL 和实服/客户端验收仍待完成，未启动服务器或修改世界、玩家存档。累计开发补丁只应用于第二十份基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代全部旧开发补丁，不能叠加；成品安装序列仍第二十份。
