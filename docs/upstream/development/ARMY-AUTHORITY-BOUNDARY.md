# Army Authority 运行边界开发检查点

日期：2026-11-27。此检查点记录 A2 的运行边界组件，尚未激活军队业务入口。

累计开发补丁：[army-authority-boundary-20261127.patch](army-authority-boundary-20261127.patch)。
只应用于固定 Core 基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代所有旧开发补丁，不能叠加。
JAR/ZIP 仍对应既有二十份成品安装序列；本开发补丁不进入该安装链。
机器证据：[workspace-army-authority-boundary-development-20261127.json](../../verification/workspace-army-authority-boundary-development-20261127.json)。

## 验证结果

最终串行回归共 **706 项测试、83 套件通过**，0 失败、0 错误、0 跳过。
分组为 focused 183 项、Army 回归 462 项、持久化回归 61 项。
本轮 3 个生产源生成 9 个 class，4 个测试源生成 7 个 class。
累计补丁含 271 项文件差异、2,811,534 bytes，SHA-256 为 `f4f38d86fb4fe075a7f455437519c6dc34a2cff5ef73a25fd1564a0a87ce6ff7`。
独立重放后，3,783 个 src 文件、API 基线与 pom 一致。
首轮 183 项曾有 4 个失败，原因是新增测试错误期待 `IllegalArgumentException`；已按既定 owner stale-token 合同修正 3 处断言为 `IllegalStateException`。
该失败轮次的日志与 XML 已保留，不计入最终 706 项通过记录；详细哈希和结果见上方机器证据。

## Authority owner

`ArmyAuthorityOwner` 的 readiness 默认关闭。
只有显式 `refresh` 取得完整、校验通过且确认有效的 authority 映像后，才允许后续操作。
提交使用完整映像 CAS，不把局部内存修改视为已持久化成功。
遇到未知写入结果立即关闭操作门；异常不代表未提交，也不能直接重做副作用。
恢复必须重新确认完整 authority 状态，不能凭调用方持有的旧对象继续写入。
`close` 是终态，关闭后不能通过刷新重新开放。

普通更新、成组更新和 tombstone 操作检查精确 token，防止旧代次或旧 incarnation 写入。
成组操作在同一 authority 边界内校验并提交，不能把部分成功当作整组完成。
重放先识别已有 receipt，再处理新 token 条件，避免成功提交后的 token 变化破坏幂等重试。
receipt 的请求身份仍必须精确匹配，不能借重放接受同 ID 的不同请求。

## 严格引导与 legacy 输入

Bootstrap 先检查真实 authority；若已初始化，不读取或触碰 legacy 输入。
未初始化时区分文件缺失、有效空文件和存在内容，不能把空文件等同于缺失。
输入上限为 8 MiB，保存实际读取的精确字节，并按 ISO-8859-1 `Properties` 语义解析。
来源描述与输入快照绑定；descriptor JSON 超过 2,048 限额直接拒绝，不截断后继续。

读取采用 `NOFOLLOW` 并核对文件属性，拒绝符号链接及可观察到的读取期间变化。
这些检查只能发现可观察竞态，不构成操作系统级原子文件快照，也不提供跨进程排他锁。
发现活动 `.tmp`、`.delete` 或 `.bak.tmp` 恢复工件时拒绝引导，不自行执行 recovery。
主文件缺失但存在 `.bak` 时拒绝，不能擅自把备份选为权威来源。
主文件存在时，仅对稳定 regular `.bak` 使用 8 KiB 缓冲流式 SHA 观察；不导入、不修改备份。
initialize 结果未知时不自动重试；先保留证据并重新确认真实 authority。

## 快照与 Bukkit 视图

`ArmySnapshotViews` 通过显式 `WorldResolver` 解析世界，不调用 Bukkit 全局查找或选择调度器。
调用方负责在正确线程执行；转换 helper 本身不提供线程调度保证。
快照有世界 UUID 时只按 UUID 解析并核对 UID，不退回名称；UID 相同允许世界改名。
只有显式启用 legacy exact-name 策略，才允许仅含名称的快照按完全相同名称解析。
世界无法解析或身份不符时明确抛出异常，保留原快照，不丢弃军队或构造无世界位置。

物化创建新的 `ArmyUnit` 和 `Location`，不共享活军对象或可变位置引用。
捕获前复制单位，保留完整 `Instant`、朝向和补给消费余量，并校验活军、token 和非空世界。
捕获会记录当前 World 的 UUID 与名称；它不是保持原快照世界元数据不变的通用更新方法。
普通字段更新应从原 `ArmySnapshot` 构建，避免经物化再捕获而暗中改写世界信息。

## 尚未激活的边界

本轮没有接线 `ArmyService` 或 `Commander`，也没有完成全部 Army writer 的统一切换。
paid、session、skill ledgers 以及真实 military limits 尚未完成，不能据此宣称 A2 已闭环。
本检查点不授权启动服务器；源码边界与自动化验证不能替代完整 Folia 运行验收。

## 下一步最小路径

复用已存在的 `NationDebitService`，将其作为 paid 操作的唯一扣款 receiver。
版本化的 paid/session/skill ledgers 应与 Army intent、reservation、effect proof 同 authority 提交。
先持久化并读回源意图，再调用 receiver；确认已扣款后通过同 authority CAS 提交效果与证明。
永久 negative/cancel 决定封闭无效果终态；未知结果保留恢复证据，不能换 ID 再扣款。
国家生命周期 admission 与 Budget 策略必须先明确，不能把普通 spend 视为等价恢复协议。
补齐真实后端故障恢复和重放测试后，统一切换所有 Army/Commander writer。
不能先只断开 getter 而保留旧 writer 旁路，再把局部迁移当作运行完成。
