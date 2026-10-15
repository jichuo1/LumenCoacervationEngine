# 云母与构建门禁审阅记录（2026-10-15）

审阅对象为 `d79fc1d6df3c38d29c03d293c175f17bbb911fb2` 之上的本地云母改动及本次门禁／工作流优化。改动尚未提交或推送。原有 Compose 方案草稿未修改。

## 1. 审阅修复与优化

| 问题 | 处理 |
|---|---|
| 云母 Demo 的后台预处理异常未隔离，旧请求仅阻止回调但仍执行 | 用 Future 取消过期请求，保留代次检查；中断、内存与运行时异常各自处理，失败局部回退；销毁先取消任务再关 executor |
| 仅修改云母回退色也重建活动位图 | 活动材质缓存仅由背景、色调和亮度输入失效，回退底色独立更新 |
| 源码契约测试直接读取的文件没有显式作为 Test 输入 | 在根构建脚本声明本模块及现有跨模块源码输入，防止字节码不变时复用旧测试结果 |
| Demo 构建任务产出 APK，四个模拟器仍各自重新编译，发布产物与设备安装字节缺少闭合 | 构建一次；revision、包名和 SHA-256 清单绑定 APK 对；四版本及发布读取相同 artifact；SDK 固定 JDK 17 |
| CI 重复配置／构建发布包 | 既有构建、测试、Lint、AAR／源码包与 R8 合为一个任务图，许可证直接检查其输出 |
| 报告重复身份、声明数量不符等边界未检查 | XML 流式处理，增加身份／数量完整性与非法计数检查；设备协议要求开始／结束配对、连续序号、稳定总数与成功终止码；异常保持非零退出 |

没有增加生产依赖、取消现有必测项、放宽像素阈值或自动重放失败测试。配置缓存严格失败，Windows 保留 `--no-daemon`。接入与失败语义见 `BUILD_WORKFLOW.md`。

## 2. 性能证据

- 同一源码、同一完整 CI 任务图、预热构建缓存、Windows `--no-daemon` 的单次对比：关闭配置缓存 **50.19 秒**，重用配置缓存 **29.44 秒**，减少约 **41.3%**。不是 hosted 或全量构建加速承诺。
- 20,096 条合成通过用例，5 次中位数：旧校验 **28.4ms**，新摘要模式 **26.9ms**；Python 跟踪峰值分配 **12.35MB → 7.49MB**，减少约 **39.4%**；成功日志 **720,681 → 37 字符**。详细输出模式仍保留，完整性检查有额外开销。
- 每轮 Demo 的 Sample 编译由 **5 处 → 1 处**，四版本设备测试保留；模拟器任务不再持有 Gradle 编译进程。
- 临时追加纯注释的受控探测：class/JAR 内容摘要不变，`sourceContractInputs` 仍令测试失效并重跑成功。探测后核对并恢复原始文件字节。

## 3. 已通过验证

- 完整工程门禁、Sample R8、AAR／源码包及本地 Maven 发布任务，在严格配置缓存下通过。
- JVM **918 项**：0 失败、0 错误、0 跳过；全部主机脚本回归 **45 项**通过。
- 七组发布包许可证检查通过；Actionlint 1.7.12、Shell 语法及 `git diff --check` 通过。
- 新预构建执行链在 PMA110／API 37 上完整执行 **142 项**，无协议错误或跳过。云母 **8 项全部通过**，Fluent 与滚动采样相关用例通过。

## 4. 保留的失败与验收边界

全量设备结果是 **140 通过、2 失败**，门禁保持非零退出，不作全量通过结论：

| 用例 | 候选结果 | 未修改 HEAD 的独立基线对照 |
|---|---|---|
| `LocalSurfaceIntegrationTest.linearGpuFadeHasUniformPixelCoverage` | 1/10 位置红通道期望 237.5521，实际 255，断言位于第 284 行 | 相同数值和断言位置失败 |
| `SurfacePixelContractTest.paddedExperimentCanReadTheOutsideMarkerAndReportsActualFiveBounds` | 第 152 行像素断言失败 | 相同断言位置失败 |

基线从 Git archive 导出到工程 `build` 内，使用独立包名 `com.lumen.coacervation.sample.baselineverify`；候选为 `com.lumen.coacervation.sample.micaverify`，没有覆盖原有 Demo。基线仅定向运行上述两项，不能表述为基线完整设备矩阵已验收。两项均在改动前可复现，未为本次性能优化扩大到原有 GPU 渲染管线修复，也未放宽其门禁。

GitHub API 27／31／33／34 矩阵、hosted 实际加速与发布仍未运行。不能将静态工作流校验、本地构建或 API 37 的云母通过替代它们。

本地证据保存在 `build/gate-build-benchmark.json`、`build/gate-verifier-benchmark.json`、`build/contract-cache-invalidation.json`、`build/gate-final-verification.log`、`build/gate-device-full/` 和 `build/gate-baseline-compare/`。这些是本地验收文件，未作为远端发布证据。
