# 2026-10-23 异步 Mica 预处理与源码门禁优化

实现基于远端主线 `8437bceadd51b4c0ccedf98a19531d0d57c9f578`。开工时本地为 `7fe96e1`，工作树干净；确认无分叉后正常快进了远端七个提交，包括 1.5.1 和 Mica 在 View 未挂载时的结果交付修复。没有重置、覆盖分支或移动标签。

本轮为未发布改动；`lumen.version` 仍为 1.5.1，`CONTRACT_VERSION`、持久化、材质默认值和生产依赖均未变。正式发布须按新增公开 API 的版本规则处理，不能把这些本地产物当成已发布的 1.5.1。

## 实现与资源归属

新增 `LumenMicaPreparer`、`LumenMicaImageSource`、`LumenMicaPreparationListener` 和类型化失败枚举。线程、所有权和接入样例见 [API](API.md#20-可选云母与内容分层host自-145) 与 [Mica 接入](MICA_MATERIAL.md#31-可选异步预处理未发布)。

- 一个 preparer 最多一个执行中操作、一个最新待处理来源和一个最新未交付结果；输入获取和结果通知都合并，主线程繁忙时不积压位图。
- 来源是惰性工厂；成功返回后转移独占软件 Bitmap 的所有权。预处理在 finally 中回收输入；工厂抛错前自行负责已获取的资源。同步 `LumenMicaImage.prepare()` 的借用语义保持原样。
- 结果使用主线程 Handler 交付，保留 View 未 attach 时也能收到的上游修复。supersede/cancel/close 清掉旧宿主回调，失效的后台完成结果不能复活页面。
- cancel 是逻辑取消，保留在途输入直到其操作返回；close 另外中断、关闭本实例 worker。宿主来源必须有界且响应中断，不能强停一个自行忽略中断的任意阻塞操作。
- 仅尚未交付的快照允许回收。listener 已取得的不可变快照可继续被 View/RenderThread 使用，关闭 preparer 不会回收它。
- 来源、预处理、执行器拒绝分别返回固定失败枚举，不暴露异常原文、URI 或文件路径。普通宿主 callback 异常不会阻塞后续请求。

内部 `LatestPreparation` 是可在 JVM 验证的有界状态机；内部工作 SAM 及预处理方法标注 WorkerThread，和主线程公共控制 API 的边界由 Lint 检查。没有 Suppress、dontwarn、新基线、共享全局线程池或反射。Demo 的 Mica 页面接入该公开接口，移除了自己的 Future/worker/generation 管线。

同步预处理配方源文件与本轮基线逐字核对一致：96px 上限、模糊半径、色彩、透明像素处理和工作集预算未改变。未调整材质、帧率、采样分辨率或运动效果。

## 门禁算法

`MotionSource` 原先每次函数查找都会重新遍历、读取并词法扫描整棵 Motion 源码树。现在每个测试 JVM 只建立一次有序文件索引，源码和词法掩码按需加载，已确认唯一的声明按函数名复用；core 的跨模块读取同样只索引、读取需要的文件。缺失、重名、注释/字符串和函数边界逻辑保留。

快照不跨 JVM/Gradle 调用；根构建脚本原有的本模块与跨模块 `sourceContractInputs` 和严格配置缓存均保留。未扩大 Lint 忽略项、缩减测试或修改 CI 设备矩阵。

## 验证结果

原始日志、冻结清单和产物在工程根 `build/engine-perf-20261023/`，不是公共发布资产。

| 层级 | 结果 |
| --- | --- |
| JVM | 1,012 项，0 failures/errors/skips；新增状态机回归 10 项，含 10,000 次替换、慢主线程、取消、关闭、拒绝、抛错与真实跨线程交错 |
| Python 门禁 | 51 项通过，包含新增设备必测清单和实际测试名称一致性 |
| 完整工程门禁 | Debug、所有 JVM、Sample release 单测、完整 Lint、所有 AAR/source jar、Sample Release R8 均通过；包含相应源码输入变化后的实际执行和后续复用，未把 UP-TO-DATE 当成新执行 |
| Lint | 十模块 HTML 与 XML 核对，0 个生效错误，71 warnings；线程边界修复后通过，未新增基线 |
| 发行归档 | CI 对应七组 AAR/source jar 的 notice 检查通过；新增公开类和同步旧类存在，ZIP 条目检查通过 |
| 平台类型 | 在实际 Debug APK 上执行 minSdk 27 类型审计；新增类没有高于 minSdk 的平台类型引用；既有未改类的发现保留在原始日志 |
| Demo 测试包 | 按正常非混淆 release 工作流构建应用和 instrumentation APK，通过 |
| PMA110 / API 37 | 独立包名执行新接口与既有 Mica 两类共 12 项，0 failures/errors/skips；实际输入回收、像素等价、关闭后已交付快照可用、失败重用、未挂载交付、Base/Alt、分层、内存压力、失焦和真实 GPU 输出通过 |

设备开始测试前检查 Awake、未锁定和并行占用。验证包为 `com.lumen.coacervation.sample.verifyasync20261023`，未覆盖原 Demo。安装后拉回 app/test 的实际 base.apk，与构建 bundle 分别逐字节哈希匹配；原 Demo、原 test 和 BIL 模块三包的路径及哈希前后不变。设备来源记录同时绑定基线 SHA、未提交源码清单和独立 applicationId 差异，没有把基线 SHA 冒充包含补丁的已发布提交。

三项新增 instrumentation 已登记在所有 smoke API 的必测集合；本次实际执行范围为 API 37 的定向 12 项。API 27/31/33/34 完整矩阵、跨设备长期运行和帧时间/RSS/功耗基准尚未执行。

## 缓存正反验证

在固定基线加本次补丁的独立副本中，用一个不产生 class 的纯注释 Kotlin fixture 验证：

1. 首次选择执行布局/抽离契约通过，相同输入的下一次 Test 为 UP-TO-DATE。
2. 只改 fixture 注释，所有生产 class 字节哈希相同，`sourceContractInputs` 变化仍使 Test 实际执行并通过。
3. 加入现有契约禁止的 `MAX_PAGES` 标记，class 字节仍相同，复用配置缓存时 Test 实际执行并在 `pagerHasNoPageCountCap` 失败。原始失败 XML 保留。
4. 恢复正确 fixture 字节后通过。没有让持久缓存、强制跳过或失败重试掩盖该违规。

五次探针的退出码为 `0 / 0 / 0 / 1 / 0`，预期失败准确发生。独立副本随后只改验证 applicationId 用于上述设备测试，主工作树没有加入测试标记。

## 性能记录和界限

源码索引优化阶段，同一 409 项 Motion 回归的 Gradle Test 任务实测由 2.571s 变为 2.021s。整次调用由 28.61s 变为 22.58s，但两次前置编译状态不同；该数据是一组本机测量，不推导稳定百分比或 CI 提速。

最终完整 CI 任务图无修改重复调用为 16.87s：730 tasks，10 executed、720 UP-TO-DATE，配置缓存明确复用。该结果反映重复调用，不能称为首次编译或首次 Lint 扫描耗时。新队列的任务/结果数量上限有行为证明，尚无引擎 FPS、帧时间或功耗改善结论。

## 保留的失败与修正

- 第一次额外把 `assembleReleaseAndroidTest` 和 `lumen.verifySampleR8=true` 放入同一任务图，测试 APK 也进入 R8，因 AndroidX 测试依赖缺少 `CanIgnoreReturnValue` / `MustBeClosed` 而失败。现行 CI 原本只验证 Sample app 的 R8，Demo 工作流使用非混淆 release 测试包；本轮按这两个原有流程分别完成验收，没有添加 dontwarn 或新依赖。没有将这一扩展组合故障声称为已修复。
- 首次 Lint 检出三个 ThreadConstraint 错误：主线程 facade 与后台函数参数之间缺乏静态可见的异步线程边界。补充带 WorkerThread 的内部工作 SAM 和私有预处理方法注解后，完整 Lint 通过；未关闭规则。

源代码未提交、未推送、未发布；BIL 的既有工作区改动和正式 1.5.1 依赖均保留。
