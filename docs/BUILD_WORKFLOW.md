# 构建与门禁工作流

通过准确声明输入、复用已验证产物加速构建。源码、JVM、APK、设备与发布分别验收；缓存命中和 Gradle 退出码不能替代设备结果。

## 1. 编译与缓存

- `gradle.properties` 启用构建缓存和严格配置缓存。不兼容即失败，不使用 `warn` 忽略问题。Windows 保持 `--no-daemon`；CI 在一次性 Linux job 中复用 daemon。
- CI 将原有 Debug 构建、JVM 测试、Sample release 单测、Lint、AAR／源码包和 Sample R8 放入同一个任务图。R8 仍显式设置 `-Plumen.verifySampleR8=true`；Demo 使用默认的可读 release 包。
- 许可证检查读取刚构建的七组 AAR／源码包，不再次调用 Gradle。
- `Test.sourceContractInputs` 包含本模块 `src/main`。core 的跨模块契约额外包含 controls，motion 额外包含 core。纯源码文本改变、字节码未变时，契约测试也必须失效重跑。
- `MotionSource` 在每次测试 JVM 内只建立一份有序源码快照、文件名索引和字符串/注释掩码；同名函数再次读取复用已验证声明。缺失、文件重名、函数重名和词法边界仍失败。缓存不跨测试进程或 Gradle 调用，源码/跨模块文本改变继续由上述输入触发新测试进程。
- Gradle User Home 缓存由既有 `setup-gradle` 管理，不叠加同目录缓存、不上传未加密的项目配置缓存。`--no-configuration-cache` 仅供问题定位；正式门禁保留严格失败行为。

## 2. 一次构建，四版本测试，相同字节发布

1. Demo `build` job 构建 release 与 androidTest APK。
2. `smoke_artifacts.py create` 读取 AGP 的 `release`／`releaseAndroidTest` 元数据，记录精确 Git revision、包名、固定 runner 和两份 APK 的 SHA-256。固定文件名为 `sample.apk`／`sample-tests.apk`。
3. 后续任务 checkout 构建 job 解析出的不可变 revision。API 27／31／33／34 的 `smoke` job 下载同一个 artifact，启动模拟器前核对 revision 与哈希，不再各自编译或启动 Gradle。SDK 工具仍使用固定 JDK 17。
4. `run_device_tests.py` 再次校验输入，检查实际设备 API，安装 APK 并确认 instrumentation 指向期望应用。CI 不传过滤条件，执行完整测试包。
5. 发布仍依赖全部四个 smoke job；再次校验清单后直接上传同一 artifact 内的 `sample.apk`。

每轮 Demo 编译由 5 处降为 1 处。四版本矩阵、像素断言、动画设置、图形后端、日志、基准数据及发布依赖保留。应用测试失败不自动重放；下载和模拟器安装沿用既有有界重试。

涉及真实窗口合成和 PixelCopy 的 API 31／33／34 使用 `google_apis` 标准图形镜像，API 27 使用 `default`。ATD 默认关闭硬件渲染，依赖硬件渲染的截图不在官方支持范围内，见 [Android 设备测试说明](https://developer.android.com/studio/test/managed-devices)。发布脚本的必需成功作业名必须与矩阵同步，不能用旧镜像的结果满足新门禁；本地额外整屏截图通过显式 instrumentation 参数启用。

## 3. 严格结果校验

执行方式依据 [Android 命令行测试文档](https://developer.android.com/studio/test/command-line)，协议常量核对 [AndroidX InstrumentationResultPrinter](https://github.com/android/android-test/blob/main/runner/android_junit_runner/java/androidx/test/internal/runner/listener/InstrumentationResultPrinter.java)。

- `adb shell am instrument -w -r` 的每条测试必须开始／结束身份一致、序号连续、总数稳定且完整；失败、错误、忽略与 assumption failure 保留各自语义。
- 必须且只能出现一次成功终止码 `-1`。单独的 `OK`、adb 退出码 0、截断、崩溃、硬超时、重复或错配结果均不能通过。
- 安装、设备 API 或 runner 失败也生成本轮失败 XML。完整原始输出保存在 `instrumentation.txt`；XML 继续经过 `verify-smoke-results.py` 的必测用例校验。
- 每轮使用新的结果目录，保留时间检查；增加重复身份、suite／aggregate 数量完整性和非法计数检查。必测用例缺失、失败或跳过均阻止通过。
- XML 逐条处理并释放用例正文，仅保留去重身份和必测通过集合。`--summary` 省略逐条成功日志，保留失败、跳过、缺失与合计；完整原始输出和 XML 仍可追溯。
- 未指定 `SMOKE_APK_BUNDLE` 时，`run-smoke.sh` 保留显式的 Gradle 本地执行路径；预构建测试失败不会自动切换路径重试。

## 4. 本地验证

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug --console=plain --no-daemon
# 完整 CI 任务图；PowerShell 下含点的 -P 参数整体加引号。
.\gradlew.bat assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug bundleReleaseAar sourceReleaseJar :sample:minifyReleaseWithR8 '-Plumen.verifySampleR8=true' --console=plain --no-daemon
python -m unittest discover -s tools -p 'test_*.py' -v

# 先构建 release 与 androidTest APK；实际 API 与设备一致。
python .github/scripts/smoke_artifacts.py create build/demo-apks --revision (git rev-parse HEAD)
python .github/scripts/run_device_tests.py build/demo-apks --revision (git rev-parse HEAD) --api 37 --output build/device-results
python .github/scripts/verify-smoke-results.py build/device-results --api 37 --summary
```

## 5. 性能与验收边界

2026-10-15，本地同一源码、相同完整 CI 任务图和已预热构建缓存的单次对比：关闭配置缓存约 50.2 秒，重用配置缓存约 29.4 秒。该结果衡量 Windows 无变更构建，不代表 hosted 或全量构建的固定加速比例。

20,096 条合成通过用例、5 次中位数：原校验约 28.4ms，新 CI 摘要模式约 26.9ms；Python 跟踪峰值分配约 12.35MB → 7.49MB，成功日志 720,681 → 37 字符。详细输出模式保留逐条日志，新增完整性检查有额外开销。算法基准不替代设备测试耗时。

工作流变更需通过完整本地门禁、R8、许可证、脚本回归、Actions／Shell 校验与真实设备执行。GitHub 四版本矩阵及 hosted 加速效果仍需远端运行验收。
