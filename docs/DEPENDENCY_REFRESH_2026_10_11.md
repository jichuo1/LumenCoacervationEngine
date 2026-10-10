# 现有依赖稳定版核验（2026-10-11）

只更新已有依赖和构建工具，不新增生产依赖、权限或更高的 `minSdk`。核心与 motion 的依赖边界继续不变；Android API 下限为 27，compile/target SDK 为 37，JVM 字节码为 17。下面版本同时核对官方发布说明与实际仓库元数据，排除 alpha、beta、RC、EAP 和 snapshot。

## 已升级

| 项目 | 原版 → 当前版 | 官方证据 |
|---|---|---|
| AGP | 9.4.0 → 9.4.1 | [Google Maven 元数据](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)；[9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin | 2.3.10 → 2.4.21 | [Kotlin 发布记录](https://kotlinlang.org/docs/releases.html)；[Maven 元数据](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml) |
| AndroidX Core | 1.19.0 → 1.19.1 | [Core 发布记录](https://developer.android.com/jetpack/androidx/releases/core)；[Google Maven 元数据](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml) |
| Gradle | 9.7.1 → 9.8.1 | [官方版本接口](https://services.gradle.org/versions/current)；[发行说明](https://docs.gradle.org/9.8.1/release-notes.html) |

Gradle 发行包 SHA-256 为 `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`；用已校验的发行版生成 wrapper，wrapper JAR 对照[官方 SHA-256](https://services.gradle.org/distributions/gradle-9.8.1-wrapper.jar.sha256)为 `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`。Windows 短临时目录启动脚本保留，不以通用生成脚本覆盖它。

SDK Build Tools 对照[Google 官方 SDK 元数据](https://dl.google.com/android/repository/repository2-3.xml)固定到最新稳定 `37.0.0`，九个 Android 模块统一读取 `lumen.buildTools`，避免 AGP 默认 `36.0.0` 与 CI 安装版本分叉。元数据同样列出 stable `platforms;android-37.2`，本轮保留 compile/target API 37 的现有基础平台语义，minor SDK API 迁移不混入控件修复；minSdk 27 不变。

Kotlin 继续只在根脚本声明插件版本供 AGP 内置编译器读取，各模块不 apply Kotlin Android 插件。AGP 9.5 alpha 与 Kotlin 2.5 beta 属预览版，本次不采用。

## 当前已经是最新稳定版

| 现有依赖 | 保留版本 | 实际官方元数据 |
|---|---|---|
| AppCompat | 1.8.0 | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/maven-metadata.xml) |
| RecyclerView | 1.4.0 | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/maven-metadata.xml) |
| JUnit | 4.13.2 | [Maven Central](https://repo.maven.apache.org/maven2/junit/junit/maven-metadata.xml) |
| AndroidX Test core | 1.7.0 | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/test/core/maven-metadata.xml) |
| AndroidX Test runner | 1.7.0 | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/test/runner/maven-metadata.xml) |
| AndroidX Test ext JUnit | 1.3.0 | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/test/ext/junit/maven-metadata.xml) |
| Lottie | 6.7.1 | [Maven Central](https://repo.maven.apache.org/maven2/com/airbnb/android/lottie/maven-metadata.xml) |
| PAG noffavc | 4.5.98-noffavc | [Maven Central](https://repo.maven.apache.org/maven2/com/tencent/tav/libpag/maven-metadata.xml) |

不把 JUnit 4 测试框架改成 JUnit 5，不替换 PAG 的 noffavc 变体，也不把 Rive View 适配改成 Compose 接口；这些属于接口或框架迁移，不是版本刷新。

## Actions

调用点固定到实际核对的版本：

- [checkout 7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1)
- [setup-java 6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1)
- [upload-artifact 7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2)
- [download-artifact 8.0.2](https://github.com/actions/download-artifact/releases/tag/v8.0.2)
- [Gradle actions 6.4.0](https://github.com/gradle/actions/releases/tag/v6.4.0)
- [Android emulator runner 2.38.0](https://github.com/ReactiveCircus/android-emulator-runner/releases/tag/v2.38.0)

实际 `action.yml` 均使用 Node 24，当前工作流使用 GitHub 托管 Ubuntu。artifact 继续按名称上传和下载，保留默认压缩与严格摘要校验，不允许 digest 不匹配。现有模拟器库安装、有限下载重试、API 27/31/33/34 和真实像素断言保留。

## 本地验证与精确的 R8 修复

依赖批次的 `assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug --no-daemon` 完成，793 条测试全部通过，零跳过；9 个模块 Lint 均为零错误，合计 58 条既有或新编译器提示，不把 SARIF 默认规则等级当成生效错误数。

Sample 发布 Demo 默认仍不混淆；显式 `-Plumen.verifySampleR8=true` 启用 R8 验证，CI 增加同样的门禁：

```powershell
.\gradlew.bat :sample:minifyReleaseWithR8 '-Plumen.verifySampleR8=true' --console=plain --no-daemon
```

R8 初次真实运行发现 Lottie 6.7.1 固定要求的 Okio 1.17.6 缺少可选 JSR-305 `javax.annotation.Nullable`。已读取 [Okio 1.17.6 官方 sources JAR](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/1.17.6/okio-1.17.6-sources.jar)：全部 25 处使用只有 import 与注解，没有运行时调用。仅在可选 Lottie 模块增加该注解的精确 `-dontwarn`，没有全包忽略、keep-all 或引入 JSR-305 新依赖。重新运行 R8 成功，六个约定携带许可证的库 AAR 和 sources 继续校验；Rive SDK 与样本都使用实际采用的 11.14.0 MIT 许可证。

本地门禁不等同于 Actions 已运行或所有系统版本实际播放验收；远端与设备结果须另行记录。

## 高密度设备的既有 P2 夹具

扩展实际设备验收时发现三条粒子/程序效果断言失败。升级前保留的同签名 Sample APK 对照也在相同三条失败；旧夹具使用 `MATCH_PARENT × 150dp`，在高密度设备上面积超过效果层默认 `262144` 物理像素预算，生产代码正确返回 BUDGET，而夹具假设必能发射和编译 shader。

仅将测试目标限制为最多 `480 × 320` 物理像素，等待真实重布局，并明确断言它位于原预算内。生产上限、渲染保护、粒子生命期、shader 构建次数、实际窗口像素差和关闭归还断言均保留；不把提高生产预算当成测试修复。

同一 Android SDK 37 设备上，旧 Sample + 原夹具的三项对照全部失败；旧 Sample + 有界夹具的全部 13 项 P2 通过（15.835 秒）。最终候选恢复安装后，用同一测试包连续运行 P2 13 项与新旧裁剪 13 项，26 项全部通过（32.825 秒），其中包含真实 Lottie/PAG/Rive 加载、Rive 状态输入、GPU 程序效果像素与 portal 最终窗口 PixelCopy。回拉候选 Sample 的 APK 字节与本地产物 SHA-256 一致。设备结果不代表 API 27/31/33/34 的新候选已在远端执行。

## Rive 的最低系统兼容例外

Rive 曾尝试升级到 11.14.1，但 API 27 云端实际生成和状态输入测试失败。检查[官方 11.14.0 AAR](https://repo.maven.apache.org/maven2/app/rive/rive-android/11.14.0/rive-android-11.14.0.aar)和[官方 11.14.1 AAR](https://repo.maven.apache.org/maven2/app/rive/rive-android/11.14.1/rive-android-11.14.1.aar)：两版均有 arm64-v8a、armeabi-v7a、x86、x86_64；11.14.1 四个 ABI 新增 ELF `.tdata`，32 位 ARM/x86 和 x86_64 的导入表增加 `__tls_get_addr` 或 `___tls_get_addr`。11.14.0 没有这些原生 TLS 节和导入。

[Android Bionic 官方符号表](https://github.com/aosp-mirror/platform_bionic/blob/main/libc/libc.map.txt)将这些符号放在 `LIBC_Q`（`introduced=29`），[官方 ELF TLS 说明](https://github.com/aosp-mirror/platform_bionic/blob/main/docs/elf-tls.md)也解释了动态加载器与 libc 的协作要求。11.14.1 AAR 清单仍声明 minSdk 21，不能用这条声明代替原生加载兼容性。arm64 也有原生 TLS 节，换架构不能消除产品最低系统退化。

因此 Rive 固定 **11.14.0，当前最新可保持本工程 minSdk 27 的已验证稳定版**，待上游以 emulated TLS 或等价兼容构建修复后再升级。其余已升级依赖保留，不提高 minSdk，不跳过 Rive 用例，生产 `UNSUPPORTED_ABI` 失败隔离保留。11.14.1 AAR SHA-256 为 `75edafd4eb49729988b62197411b5c1a3d2a6554901ab5b14248c72c09907333`，11.14.0 为 `08e44879e0acd5297bec6734383dba3a79852ae2070ac6221936ee31bf20afef`。

## 云端小窗口拖动夹具修订

云端模拟器日志明确为 `320 × 640`；旧 portal 夹具的面板宽 340、左 margin 80，右缘 420 超出真实窗口，引擎保留根裁剪而正确限制了右拖。夹具现按实际窗口宽度缩到最多 340，行/正文/视口同宽，margin 与右侧采样点一起调整，并断言整张面板位于窗口内。原左右位移超过 4px、完整条带、内部无重复叠色和所有裁剪断言保留；同一用例还在物理设备上明确覆盖 320px 内容场景。

事件时间取实际 uptime、长按下限与上一事件加 1 的最大值，保持同一流严格单调；缩宽后的夹具先等待尺寸、laidOut、layoutRequested 与窗口焦点确认，再发送 DOWN，不能仅凭消息队列 idle 推断首个 VSYNC 布局已经完成。高 DPI 的 320px 子场景触点位于行中心，避免系统边缘手势区。失败信息包含窗口/场景尺寸、位移、缩放、pivot、事件时间、CANCEL、overlay、焦点、位置与布局状态。没有改变生产手势时序、lease、回弹或根裁剪逻辑。

这次兼容候选在 PMA110/API37 用同一包执行 P2 13 项、原裁剪 6 项和 portal 7 项，26 项通过（35.093 秒）；包含 320px 与普通场景同一行先左拖再右拖、回抓/lease、硬件窗口 PixelCopy 和 Rive 原生加载/输入。回拉 APK SHA-256 为 `571e074360459217b8865fb619652692789a9687b25220ce9388240dc0dd7fe2`，与构建产物一致。Rive AAR/sources 许可证校验通过；API27原生加载恢复和四档云端回归仍以新的远端运行结果为准。
