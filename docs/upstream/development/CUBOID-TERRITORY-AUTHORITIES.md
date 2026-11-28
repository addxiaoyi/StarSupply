# Cuboid 三方真实权威联合协议 T12

日期：2026-11-28，Asia/Shanghai。

全局 manifest 现在支持 cuboid 与 diplomacy 的精确双接收器分配。割让使用完整原 cuboid UUID，支持纯割让和割让＋已知政治条款；稀疏条款索引、完整战争范围和各自计划哈希必须一致，跨条款重复目标、非规范UUID、chunk字符串和未支持的混合条款拒绝。

War 准入和结束检查双方能力与 BOUND 证明，并复读完整证明向量及服务实例；缺失、停用、替换或第二遍读取改变都会拒绝。只有双方完整且一致的终态才释放战争范围。cuboid 所有权效果仍由原领土权威提交，外交否决、取消及取代保持原所有者。

新增联合测试使用真实 War/Diplomacy/Cuboid 文件及 SQLite 权威，覆盖双方预留、双方绑定、War准入、结束、镜像、双方应用与最终释放十处丢回复。重启后继续原编号、原计划及原binding；纯割让也完成战争关系结束。Bukkit host/scheduler 为测试替身，故障通过丢回复注入，没有启动游戏服务器或杀死真实进程。

最终四批 **1063项 / 93个suite** 全通过：territory 692，region 51，persistence 31，protocol 289；0失败、错误、跳过。3个生产源空目录编译为12个class，55个测试源编译为111个class；5个Java格式检查通过，API基线未更新。所有JVM串行，96 MiB heap、SerialGC、单处理器。初次SQLite专项的嵌套Mockito stubbing已修复，失败结果和后续重复专项不计入最终总数。详见[机器证据](../../verification/workspace-cuboid-territory-authorities-development-20261128.json)。

累计补丁 `cuboid-territory-authorities-20261128.patch`：**4,712,283字节 / 412个文件diff**，SHA256：`63e0d010197bd5ad4739fbb19bce0c4f9913656dbf2f2f1367ca1c6ecbfcb517`。仅用于基点 `9e380d62fd94cdc54f6f703ea09e14f575660f18`，替代全部旧累计补丁，不能叠加。两个独立索引正反实际应用后，3,884个src及API/pom规范字节全部一致。

**全部阶段尚未完成。** 联合测试直接驱动原生协议步骤，生产 PeaceTreatyBatch 编排及模块注册尚未接入，玩家和约入口仍不接受割让。下一步保存生产领土计划引用及终态、接通取消恢复、完整向量和host启停后开放入口。军事＋领土、foundation chunk和攻城接收器、其余资金/玩法/模块及归档继续开放。未执行全仓clean build、生成新JAR/review ZIP或启动实服，未修改世界或玩家存档。
