# 引擎工程规则

> 适用对象：修改引擎本体的人，以及自己实现 `GlowEngine` 的人。
>
> 宿主**怎样接入**引擎，见 `INTEGRATION_STANDARD.md`；本文讲的是引擎**内部**必须守住的写法。每条规则都来自来源工程里的一次真机事故，或一次性能回归。

## 1. 依赖边界

- `lumen-engine` 只允许依赖两样东西：Android 框架，和 AndroidX core（含 annotation）。
- `lumen-motion` 在此之外只允许依赖 `lumen-engine`。AppCompat、RecyclerView 的适配放进 `lumen-controls`；`MotionExtractionContractTest` 会扫描 lumen-motion 的全部源码守住这条。
- **不得**引入以下依赖：
  - AppCompat、Material Components、Compose；
  - BetterAndroid、KavaRef；
  - 任何反射库或 Hook 框架。

  需要 AppCompat 的功能，放进可选模块（例如 `lumen-controls`）。原因见 `BETTERANDROID_INITIATIVE.md` §2。
- 引擎**不得**读取宿主的资源、主题属性、`BuildConfig` 或 Application 子类。宿主需要提供的东西，一律通过公开 API 显式传入，例如 `LumenPalette`、`LumenStorageNames`。

## 2. 高于 minSdk 的平台类型必须隔离

### 2.1 为什么

ART 执行 `check-cast` 和 `instance-of` 时，**先解析类型、再判断 null**。所以即使值恒为 null，只要这条指令引用了当前系统上不存在的类，照样会抛 `NoClassDefFoundError`。

这比方法体里任何 `if (SDK_INT >= N)` 或 `runCatching` 都早：Kotlin 为可空字段、安全调用、泛型取值自动插入的强转，往往出现在版本判断之前，或者出现在 `runCatching` 的 lambda 之外。

来源工程 2026-09-11 的线上崩溃就是这个形态，发生在 Android 11（API 30）设备上：

- 两个声明为 API 33 类型（`OnBackInvokedDispatcher?` / `OnBackInvokedCallback?`）的 `var` 被 lambda 捕获；
- Kotlin 为它们生成 `Ref.ObjectRef`，每次读取都要插一条 `check-cast` 到声明类型；
- 在 API 30 上，这两个变量的值恒为 null，紧跟在读取后面的 null 判断却根本没走到，读取那一行就崩了。

### 2.2 规则

1. 引用高于 `minSdk`（27）的 `android.*` 类型时，**只能**写在以 `ApiNN` 结尾、标注了 `@RequiresApi(NN)` 的**隔离类**里。以下写法都算引用：
   - 字段类型；
   - 方法参数和返回值；
   - 局部变量；
   - 强转、`is` 判断；
   - 泛型实参。
2. 非隔离类只持有隔离类的引用，**不得**在自己的签名里出现高版本平台类型。
   - 例：`GlowBackdropTarget.recordedCapture()` 返回 `GlowContentCaptureApi31?`，而不是 `RenderNode?`。
3. 调用隔离类的地方，**必须**先做 `Build.VERSION.SDK_INT >= NN` 判断，而且写成 lint 能识别的形式。
   - 判断如果封装成属性或函数，**必须**标注 `@ChecksSdkIntAtLeast`。
4. **不得**用 `runCatching` 或 `try/catch` 代替版本判断。
5. **不得**用任何反射方式访问高版本 API（见 §3）。

### 2.3 现有隔离类

| API | 隔离类 |
|---|---|
| 28 | `LiquidImageDecoderApi28` |
| 29 | `LiquidThermalMonitorApi29` |
| 31 | `GlowContentCaptureApi31`、`GlowChromeGlassApi31`、`GlowChromeBlurApi31`、`LiquidBlurBackendApi31`、`LiquidChromeBackdropApi31`、`LiquidPerformanceHintApi31`、`FrostedChromeGlassApi31` |
| 33 | `LiquidRefractionBackendApi33`、`LiquidChromeLensApi33`、`FrostedChromeLensApi33`；`PredictiveBackApi33`（lumen-motion，承载 `android.window.*` 返回回调，对外签名一律用 `Any`） |

### 2.4 审计

凡是改动涉及平台类型，都**必须**对一个包含引擎的 APK（例如 `sample` 的 debug 包）跑一次 DEX 审计：

```bash
python tools/audit_checkcast.py sample/build/outputs/apk/debug/sample-debug.apk "$ANDROID_HOME/platforms/android-37/data/api-versions.xml" "$ANDROID_HOME/build-tools/<版本>/dexdump" 27
```

审计报告需要人工分类：

- 出现在 `*ApiNN` 隔离类里，或者所在类只在 SDK 判断之后才会被加载：安全。
- 出现在普通类里、会被无条件执行：缺陷，**必须**修掉。

宿主也可以用这个脚本审计自己的代码，第 5 个参数传入宿主自己的类前缀。

## 3. 不使用反射

- **不得**使用以下任何方式：
  - `Class.forName`；
  - `getDeclaredField` 或 `getDeclaredMethod`；
  - 隐藏 API；
  - 按字符串查找类或方法；
  - KavaRef 一类反射工具。
- 只要守住这一条，引擎的混淆规则就可以保持为空，`consumer-rules.pro` 里只有注释。以后任何新增的反射，都会同时破坏两件事：宿主的零配置混淆，以及 §2 的旧系统安全审计。

## 4. 线程

- 引擎的公开 API 全部标注 `@MainThread`，只能在主线程调用。
- 后台工作使用单线程的守护 executor 或 `HandlerThread`，线程名以 `Lumen-` 开头。
  - 线程在会话 `close()` 时关闭；悬浮栏探针的线程在 `dispose()` 时关闭。
  - **不得**使用进程级共享线程池。
- 后台结果投递回主线程之后，**必须**先确认会话没有关闭、底图没有被替换，才能使用这个结果。后台循环里用 `Thread.interrupted` 尽早放弃过期的任务。
- 给宿主的回调（例如 `onFailure`）**不得**在 `onCreate`、`draw` 或布局的调用栈里同步触发，一律 `post` 出去。

## 5. 分配与缓冲

- `draw()`、`onPreDraw` 和逐帧动画这三类路径上**不得**分配对象：
  - `Paint`、`Matrix`、`Path`、`RectF` 一律复用；
  - `Shader` 只在配置或状态变化时构建。
- 每帧**不得**新建以下任何一样：
  - `RenderEffect`；
  - `RuntimeShader`；
  - `saveLayer` 离屏层；
  - `BlurMaskFilter`。
- 每一块位图都**必须**有写成常量的尺寸或字节预算，并由 JVM 测试守住（`INTEGRATION_STANDARD.md` §8 列出了现有预算）。超出预算时降低采样倍率或降级后端，**不得**扩容。
- 实时取样使用三缓冲轮转：PixelCopy 不能写入当前帧或上一帧仍可能被 RenderThread 引用的位图。
- 静止时**不得**持续工作。实时取样有静止门控，悬浮栏探针有节流；页面静止时引擎的 GPU 帧数应接近零。

## 6. 失败隔离与降级

- **修饰性能力失败时只关闭自己**，不上报材质失败，不影响页面。修饰性能力包括：
  - 回弹视口；
  - 悬浮栏探针；
  - 内容节点玻璃；
  - 滚动边缘溶解。
- **高级材质后端**只能单向降级：REFRACTION → BLUR → TRANSLUCENT。本次会话里失败过的后端不再重试，否则厂商的图形实现如果持续抛错，会造成重绘循环。
- **高级材质整体失败**，走 `SkinRepository` 的两阶段激活状态机：
  - 待确认状态只由首个成功的可见绘制来确认；
  - 失败后回退状态先持久化，再通知宿主。

  渲染器版本号 `CURRENT_LIQUID_RENDERER_VERSION` 只在这里单点递增。渲染协议有不兼容变化时，递增它，让所有设备重新走一遍健康确认。
- 进程级的"发现待确认状态就回滚"，只在**新进程第一次读取**时执行。同一进程里写入待确认状态后发生的 `recreate()`，必须继续看到待确认状态。

## 7. 生命周期与监听

- 在 `ViewTreeObserver`、`View`、`Window` 上注册的每一个监听，都**必须**有对应的移除路径。移除前检查 `isAlive`；观察者换代（重新 attach）时要重新注册。
- 会话关闭之后，任何入口都必须是空操作，不得抛异常。宿主在 `onDestroy` 之后的迟到调用是正常情况。
- 同一进程里只有最新创建的渲染会话，有权确认或上报高级材质的健康状态（`SkinRenderSessionRegistry`）。旧会话迟到的确认会被忽略，旧会话随即退回柔光。

## 8. 测试

- 纯策略（尺寸预算、降级顺序、可读性、光晕几何、状态机）**必须**写成不依赖 `android.graphics` 的 `object` 或 `class`，用 JVM 单测覆盖。
- 每一条真机教训都**必须**落成测试：能写成纯函数的，写策略测试；只能靠写法保证的（注册顺序、门控条件、不许复活的旧实现），写**源码契约测试**。
- 源码契约测试统一使用 `contract/SourceContract`；lumen-motion 另有 `contract/MotionSource`，按简单文件名或函数名切出源码，注释与字符串在同一趟里识别：
  - `after(anchor)` 和 `before(anchor)` 在锚点缺失时**直接失败**。
    - 原因：`substringAfter` 找不到锚点时返回原串，搜索窗口会悄悄扩大成整份文件，`contains` 照样通过——代码一搬家，护栏就静默失效。
  - 读入的源码统一把 CRLF 换成 LF。
- 宿主义务（例如"弹窗标题与入口标题用同一段文字""自带手势的控件打 `EXCLUDED_TAG`"）**不**写进引擎测试，而是写进 `INTEGRATION_STANDARD.md`，由宿主自己的测试守住。

## 9. 命名与日志

- 线程、RenderNode、日志 tag 一律以 `Lumen-` 开头，便于宿主在 systrace、`dumpsys gfxinfo` 和 logcat 里识别引擎。
- 日志**不得**包含用户内容、文件路径或 Uri。详细日志放在 `Log.isLoggable(TAG, Log.DEBUG)` 判断之后。

## 10. 公开 API 纪律

- 新类型默认是 `internal`。只有宿主**确实需要**的类型才公开，而且公开时必须同步更新 `API.md`。
- 公开签名里只允许出现两类类型：Android 框架类型（不高于 `minSdk`，见 §2），和引擎自身的类型。
- 公开 API 的不兼容变化，按 `VERSIONING.md` 升级主版本。

## 11. 构建门禁

修改后在工程根目录运行：

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --no-daemon
```

- 三个任务都必须通过，Lint 必须 0 错误。
- 在 Windows 上一律加 `--no-daemon`：常驻的 daemon 可能产出陈旧的 APK，而安装命令照样返回成功。
- 涉及平台类型的改动，再加跑 §2.4 的审计。
