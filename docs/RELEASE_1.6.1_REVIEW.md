# 1.6.1 演示沙盒修复复核

本轮是修订版：只修复演示 Sample 的 `SurfaceSandboxActivity` 启动方式，并新增一条设备测试。九个库模块（`lumen-*`）的源码与 1.6.0 相同。

## 起因

1.6.0 把原生 backdrop 演示（请求原生、折射关闭、内容持续动画、玻璃边距 32dp）无条件写进了 `SurfaceSandboxActivity.onCreate`。34 个仪器化测试类直接启动这个 Activity，依赖它是 1.5.6 的静止页面，1.6.0 的两次 Demo APK 运行（push 与 release）都失败了：

- API 27：`SurfaceSourceInvalidationTest` 在测试把 `content` 换成自己的视图后，动画监听器强转 `PatternView` 抛 `ClassCastException`；`LocalSurfaceIntegrationTest` 的「静止页面停止采样」断言在持续动画下不成立。
- API 31 / 33 / 34：整套测试分别在 `EnhancedSurfaceIntegrationTest`、`EngineWorkRegressionTest`、`CustomSkinBackdropContinuityTest` 停滞到 900 秒超时，其后的必测用例缺失。这一条的原因是推断（见「边界」）。

因为 Demo 冒烟没有通过，1.6.0 的 Release 没有任何附件（库 AAR、Maven 压缩包、演示 APK 都没有）。仓库里也没有 `docs/RELEASE_1.6.0_REVIEW.md`，而 `release-assets.yml` 的打包步骤要求它，所以库附件流水线没有执行。1.6.0 的 tag 推送后不可移动（`VERSIONING.md` §5），所以修复以 1.6.1 发出。

## 修复

- 原生 backdrop 演示只在演示入口（「局部视效调节实验室」）经 `EXTRA_NATIVE_BACKDROP_DEMO` 显式开启；直接启动与 1.5.6 一致：静止、折射默认开启、玻璃边距 16dp。
- 动画监听器对被替换的 `content` 不再强转（`as?`）。
- 新增 `stopContentAnimation()`，「内容动画」开关复用它。
- 新增设备测试 `SurfaceSandboxLaunchModeTest`：直接启动保持静止页面且不请求原生 backdrop；演示入口请求原生、动画在跑、边距 32dp、内容被替换后动画不崩溃。演示入口那条用 `ActivityMonitor` 取得 Activity，只用 `runOnMainSync`，收尾前先停动画：第一版用 `ActivityScenario` 时在 API 34 的软件渲染 CI 上停滞到整轮超时，持续出帧的页面不适合用会等主线程空闲的启动方式。

## 范围核对

`git diff 1.6.0..` 在发布准备之前只涉及 `sample/`（`SurfaceSandboxActivity.kt`、`SampleLayouts.kt`、新测试）与 `CHANGELOG.md`；`lumen-*` 下没有任何改动。因此 1.6.1 相对 1.6.0 的公开 ABI、API、契约版本 1、`SkinId` 与持久化协议、minSdk 27 都没有变化。

## 验证

- 云端（PR #13，最终提交 `57b452f`）：`gate` 通过；API 27 / 31 / 33 / 34 全量冒烟全部通过，必测缺失 0、失败或错误 0（API 27：197 条；API 31：212 条；API 33 与 34：各 228 条）。同一 PR 的第一版（`3380645`）在 API 34 上卡在上述演示用例，已按上文改写。
- 本机（emulator-5554，API 34）：`SurfaceSandboxLaunchModeTest` 直接启动用例通过；`SurfaceSourceInvalidationTest` 3/3、`LocalSurfaceIntegrationTest` 9/9 通过（1.6.0 上同样的 `ClassCastException` 与静止页面断言失败已复现）。
- 本机空闲出帧：直接启动的沙盒空闲 5 秒，1.6.0 为 315 帧，本修复与 1.5.6 均为 0 帧；演示入口约 310 帧。
- 本机 JVM 门禁（`testDebugUnitTest`、`:sample:testReleaseUnitTest`、`lintDebug`、`:sample:minifyReleaseWithR8`）通过，1149 个测试 0 失败。
- 1.6.1 的发布准备提交本身（版本号、文档）只在云端 CI 验证，没有重复本机门禁。远端全量矩阵结果以发布流水线生成的 `VERIFICATION_1.6.1.json` 与 `SHA256SUMS.txt` 为准。

## 边界

- 1.6.0 在 API 31 / 33 / 34 上停滞的原因是推断：直接启动的沙盒在空闲时持续出帧（本机实测 5 秒 315 帧），软件渲染下等待主线程空闲的调用等不到。停滞本身没有在本机复现：本机的软件渲染（`lavapipe`）模拟器运行到一半 qemu 进程崩溃，该次不计结果。佐证是修复后同样的 CI 环境下整套全部通过。
- 本机（主机 GPU 模拟器）上 `PanelInlineScrollTest.idleAndFinishingInlineViewportsPreserveAnActiveOuterRebound` 会无限停滞，1.5.6 与本修复都一样，CI 上通过；属于本机环境，未深究，也与本版改动无关。
- `VERSIONING.md` 在 1.6.0 条目里记着「未与 1.5.6 发布 AAR 重新做逐类描述符比对，发布前应补做」。本版相对 1.6.0 没有库模块改动，但 1.5.6 → 1.6.x 的逐类比对仍然没有做，这里不宣称它已完成。
- 没有真机验证；本版没有测得 FPS、RSS 或功耗变化，也不宣称有改善。
- 演示 APK 的构建来源是不可变的 1.6.1 tag；四个系统使用同一对 APK，全部通过才附到 Release。九模块 JitPack 公开 POM 与提交一致性、全部附件摘要由 `release_assets.py finalize` 核对。本审阅不预先宣称远端发布已完成。

CONTRACT_VERSION=1、minSdk=27、存储名、schema 与既有默认选项保持。
