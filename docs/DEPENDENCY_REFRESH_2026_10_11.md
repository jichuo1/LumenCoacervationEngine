# 现有依赖稳定版核验（2026-10-11）

只更新已有依赖和构建工具，不新增生产依赖、权限或更高的 `minSdk`。核心与 motion 的依赖边界继续不变；Android API 下限为 27，compile/target SDK 为 37，JVM 字节码为 17。下面版本同时核对官方发布说明与实际仓库元数据，排除 alpha、beta、RC、EAP 和 snapshot。

## 已升级

| 项目 | 原版 → 当前版 | 官方证据 |
|---|---|---|
| AGP | 9.4.0 → 9.4.1 | [Google Maven 元数据](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)；[9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin | 2.3.10 → 2.4.21 | [Kotlin 发布记录](https://kotlinlang.org/docs/releases.html)；[Maven 元数据](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml) |
| AndroidX Core | 1.19.0 → 1.19.1 | [Core 发布记录](https://developer.android.com/jetpack/androidx/releases/core)；[Google Maven 元数据](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml) |
| Rive Android | 11.14.0 → 11.14.1 | [官方 Release](https://github.com/rive-app/rive-android/releases/tag/11.14.1)；[Maven 元数据](https://repo.maven.apache.org/maven2/app/rive/rive-android/maven-metadata.xml) |
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

R8 初次真实运行发现 Lottie 6.7.1 固定要求的 Okio 1.17.6 缺少可选 JSR-305 `javax.annotation.Nullable`。已读取 [Okio 1.17.6 官方 sources JAR](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/1.17.6/okio-1.17.6-sources.jar)：全部 25 处使用只有 import 与注解，没有运行时调用。仅在可选 Lottie 模块增加该注解的精确 `-dontwarn`，没有全包忽略、keep-all 或引入 JSR-305 新依赖。重新运行 R8 成功，六个约定携带许可证的库 AAR 和 sources 继续校验；Rive SDK 11.14.1 的许可证与旧样本 11.14.0 的来源分开保留。

本地门禁不等同于 Actions 已运行或所有系统版本实际播放验收；远端与设备结果须另行记录。
