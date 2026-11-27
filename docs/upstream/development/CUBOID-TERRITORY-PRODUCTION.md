# Cuboid 生产工厂与写入入口 T7 开发检查点

日期：2026-11-28，Asia/Shanghai。

生产源码现在通过 `TerritoryStorageFactory` 创建稳定的 cuboid host，父领土、子区域、权限检查和模板共享持久权威。首次初始化失败保留同一服务实例，管理员可以在控制台或游戏内查询状态并恢复。该检查点已完成源码和定向验证，尚未构建新部署 JAR。

## 配置、恢复与生命周期

`config.yml` 增加 `cuboid-territory.backend` 与 `cuboid-territory.legacy-source`，合法值都是 `files` 或 `sqlite`；默认 `unselected`。首次导入前须停止旧插件及外部旧 writer，并核实有效来源。旧 SQL 空表/异常不会触发文件回退或合并。新安装也必须明确选择。旧 SQLite 出生点世界缺失时，通过 `cuboid-territory.spawn-worlds` 的领土 UUID/世界名映射补全。

`/territory status` 和 `/territory recover` 要求 `starcore.territory.admin`，支持控制台。recover 重新读取配置；尚无 owner 时重新尝试首次安装，已有 owner 时只刷新原权威，不切换后端或重新导入。状态为 ready、recovery-required、recovering 或 closed。恢复错误写入日志并可由管理员查看。路径、来源、目标与 SQLite 身份绑定沿用 T6。

父子服务只发布确认状态。关闭时不重新从显示对象重建存档；已写入的命令无需额外 flush，不可用 host 也可关闭。旧 storage 保存入口全部拒绝。安装 runtime 的 owner 只移交一次，关闭 donor 不关闭接收方，初始化/关闭回调重入受限。

## 命令、GUI 与授权

成员、权限、父子删除、子区域创建、模板都通过服务提交读取时的完整快照；管理员重叠绕过在创建提交内检查。模板应用命令补上管理权限检查，OWNER 不可作为普通成员级别。

GUI 每次重建重新读取领土，点击和最终确认重新鉴权。页面携带原快照，权限撤销、领土变更或混合父子快照会拒绝操作，不会用新快照替旧确认擅自执行。服务返回前未确认的操作不会显示成功。

临时授权仍在内存中，绑定 authority UUID 与父领土记录 generation。父领土转让后转回、成员或权限变更都会使旧授权失效；服务不可用时拒绝授权，恢复原状态后仍可使用未过期授权。命令携带授权前快照，超大时长溢出拒绝。子区域变更不会使父记录 generation 自动增长，这项策略仍需结合游戏事件保护继续评估。

租赁未获得资金协议回执，也未向旧租赁服务注入经济提供者；付款会失败而不会模拟成功。不能将生产工厂切换表述为租赁资金闭环。

## 验证与交付

最终两批 **630 项 / 59 个 suite** 全通过，0 失败/错误/跳过：领土 599 项，持久化与公共 API 31 项。新增 host/命令/GUI 专项 15 项计入总数。11 个生产源空目录编译为 23 个 class，全部 34 个领土测试源编译为 57 个 class；13 个 Java 文件 Spotless 通过；API 基线未更新。最终验证严格串行，96 MiB heap、SerialGC、单处理器。

初次专项存在 mock 嵌套配置错误和旧 UUID 授权接口回归，已修复并由最终测试覆盖；一次测试编译发生原生内存不足，该失败不计入通过结果。源码、最终 class、测试日志/XML 和累计补丁一致性见[机器证据](../../verification/workspace-cuboid-territory-production-development-20261128.json)。

累计补丁 `cuboid-territory-production-20261128.patch`：**4,464,849 字节 / 395 个文件 diff**；SHA256：`48bddf282345d6f90fedd1bfccb6cbfe0531370d807e7589759315a8edfb23ba`。只用于第二十份固定基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代所有旧累计补丁，不能叠加。独立 Git 索引实际正向/反向应用，全部 3,872 个 src 文件及 API/pom 规范字节一致。

**全部阶段尚未完成。** 重要下一项是实际 cuboid 游戏事件保护：现有 `TerritoryProtectionListener` 使用另一套 chunk 服务，不能视为新 PermissionChecker 已用于游戏事件。区域显示仍需一致父子快照与不可用状态处理。之后继续两套领土 receiver、租赁资金、军限旧数据和权限化解除，以及赔款/贡纳/贷款、海军运输/港口、伤兵/学说、预算/归档。未完成全仓干净构建、新 JAR、review ZIP、SQL 多进程或 MySQL 验证；未启动 Paper/Folia，未修改世界或玩家存档。
