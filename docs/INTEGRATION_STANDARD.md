# 凝光视效引擎适配标准

> 契约版本：**1**（`LumenEngine.CONTRACT_VERSION`），适用于引擎 1.x。
>
> 本文是宿主接入引擎的**规范**：宿主怎样接、必须做什么、不得做什么。代码注释和测试用"适配标准 §x"引用这里的节号。节号只增不改，废弃的条款标注"已废弃"但保留编号（见 `VERSIONING.md`）。
>
> 最小可运行的对照实现是 `sample/`，其中每一步都标了本文的节号。

## 0. 用语与适用范围

| 用语 | 含义 |
|---|---|
| **必须** / **不得** | 违反即不符合本标准。后果可能是崩溃、泄漏、材质状态错乱，或出现可见的视觉错误。 |
| **应当** / **不应** | 有正当理由时可以偏离，但要在宿主代码里用注释写明理由。 |
| **可以** | 可选能力。 |

- **适用对象**：基于 Android View 系统的应用，`minSdk ≥ 27`。纯 Compose 应用见 §11。
- **线程**：除非另有说明，本文提到的全部 API 都**必须**在主线程调用。
- **宿主**：接入引擎的应用。**会话**：一个 Activity 从 `prepare()` 到 `onDestroy()` 的视效生命周期。

## 1. 工程接入

### 1.1 依赖方式

宿主**应当**按下面的优先级选一种方式接入：

1. **Maven 坐标**。组是 `com.lumen.coacervation.engine`，产物有三个：
   - `lumen-engine`：必需；
   - `lumen-motion`：可选，长按弹性与打开/关闭/可打断动画；
   - `lumen-controls`：可选。

   在本工程运行 `publishAllPublicationsToProjectLocalRepository`，会把两个产物发布到 `build/repo`。宿主把这个目录，或任何私有仓库，加进 `repositories` 即可：

   ```kotlin
   dependencies {
       implementation("com.lumen.coacervation.engine:lumen-engine:1.0.0")
       implementation("com.lumen.coacervation.engine:lumen-motion:1.0.0")   // 可选
       implementation("com.lumen.coacervation.engine:lumen-controls:1.0.0") // 可选
   }
   ```

2. **Gradle 复合构建**。在宿主的 `settings.gradle.kts` 里写 `includeBuild("../LumenCoacervationEngine")`，依赖写法与第 1 种相同，Gradle 会自动替换成源码工程。宿主的 AGP 版本**应当**与引擎一致；不一致时改用第 1 种。

3. **拷贝源码**（不推荐）。如果这样做，**必须**保持以下三点：
   - 包名 `com.lumen.coacervation.engine` 不变；
   - 契约测试一起拷贝；
   - 继续执行 `ENGINEERING_RULES.md` 的全部规则。

   否则以后无法对照上游升级。

### 1.2 构建要求

- `minSdk` 必须 **≥ 27**。
- Java/Kotlin 字节码目标必须 **≥ 17**。
- 宿主 `compileSdk` 不得低于引擎依赖的 AndroidX 所要求的版本；Gradle 的 `checkAarMetadata` 会指出。
- 混淆：宿主**不需要**额外规则。引擎不使用反射，AGSL 着色器以字符串常量内联在源码里。
- 同一进程里**不得**同时存在两份引擎。例如不得把引擎重定位（relocate）到别的包名后，与另一份引擎共存。原因：材质状态机、渲染会话登记、内存压力中心都是进程级单例。两份引擎会各有一套单例，却读写同一组默认偏好文件，状态机会互相覆盖。

### 1.3 模块

| 模块 | 是否必需 | 依赖 | 内容 |
|---|---|---|---|
| `lumen-engine` | 必需 | AndroidX core | 两套材质、会话与状态机、悬浮栏可读性、回弹视口、触摸光晕、自定义背景 |
| `lumen-motion` | 可选 | `lumen-engine` | 长按弹性与高光、可打断动画、弹窗与全屏页的打开和关闭、翻页与文字链、手风琴、定位高亮（§12、§13） |
| `lumen-controls` | 可选 | + AppCompat、RecyclerView | 把 `SwitchCompat`、`CheckBox`、`EditText` 换装成引擎材质；翻页器的 `SwitchCompat` 识别；拖拽排序 |
| `sample` | 参考 | — | 按本标准写的最小接入示例 |

## 2. Activity 接入

每个使用引擎的 Activity，**必须**从下面两种方式中选一种：

- **组合式（推荐）**：持有一个 `LumenActivityDelegate`，按 §2.1 转发回调。任何 Activity 基类都可以这样用，包括 `Activity`、`AppCompatActivity`、`ComponentActivity`、BetterAndroid 的 `AppViewsActivity`。
- **继承式**：直接继承 `LumenActivity`，它已经接好了 §2.1 的全部转发。只适合直接继承 `android.app.Activity` 的宿主。

委托**不接管**以下任何事情，这些仍然由宿主自己管理：

- `onCreate`；
- Window、contentView、系统栏；
- 语言；
- Activity 何时重建。

### 2.1 生命周期与输入转发

宿主**必须**转发下表的全部回调。表中的时机也是强制的。

| Activity 回调 | 委托方法 | 时机 | 漏掉的后果 |
|---|---|---|---|
| `dispatchTouchEvent` | `onDispatchTouchEvent(event)` | 在 `super` **之前** | 高级材质把重同步排进手指按住的时段，新手势的头几帧出现迟滞 |
| `onStart` | `onStart()` | 在 `super` 之后 | 回到前台后实时取样不恢复，刷新率探测也不重置 |
| `onStop` | `onStop()` | 在 `super` 之前 | 切到后台后仍在取样和重录，持续耗电 |
| `onTrimMemory(level)` | `onTrimMemory(level)` | 在 `super` 之前 | 内存告急时不释放取样缓冲 |
| `onLowMemory` | `onLowMemory()` | 在 `super` 之前 | 同上 |
| `onDestroy` | `onDestroy()` | 在 `super` **之前**，并且放进 `try/finally` | 会话和渲染线程泄漏；进程内的渲染会话登记不释放 |

- 宿主持有的 `GlowFloatingChrome` 必须先 `dispose()`，然后再调用 `onDestroy()`（§6）。
- `onDestroy()` 是幂等的。调用之后，委托上的全部方法都变成空操作，`engine` 返回 null。

### 2.2 配色由宿主提供

- 宿主**必须**通过 `paletteProvider` 提供 `LumenPalette`。引擎不从壁纸取色，也不读取宿主的资源和主题属性。
- `paletteProvider` 在第一次需要配色时调用一次，结果在本 Activity 的生命周期内缓存。配色变化（例如深浅色切换、主题色切换）**必须**通过重建 Activity 生效。
- 宿主如果在 `configChanges` 里声明自己处理 `uiMode`，那么收到配置变化后**必须**重建 Activity，否则配色会停在旧值上。
- 宿主**应当**使用 `LumenPalette.modern(primary, onPrimary, secondary, tertiary, dark)`：强调色由宿主给，表面和文字用引擎推荐的中性色。只有宿主有自己的表面色规范时，才直接构造 `LumenPalette`。
- 两个文字色的语义：
  - `textPrimary`：直接压在玻璃上的主文字和图标。它也是悬浮栏可读性计算用的前景色。
  - `textSecondary`：按钮文字和次要说明。

### 2.3 `prepare()` 的时机

- `prepare()` 会读取引擎的偏好文件，也就是宿主私有目录里的材质选择。所以它**只能**在以下检查全部通过之后调用：
  - 宿主自己的隐私授权门；
  - 早退分支（例如"未同意协议""重定向到别的页面""即将 `finish()`"）。

  未通过的分支**不得**调用 `prepare()`。
- 未调用 `prepare()` 时，委托的行为如下，宿主不需要为此写分支：
  - 表面 API 返回等价的静态材质；
  - 控件换装是空操作；
  - `engine` 为 null。

  授权前的界面可以照常使用这些 API。
- `prepare()` **应当**在构建视图、也就是第一次调用表面 API 之前调用。在它之前取到的表面是静态的，之后**不会**自动换成真材质。
- `prepare()` 是幂等的。

### 2.4 `bindRoot()`

- **必须**在 `setContentView` 之后调用，传入可见的内容根，通常就是传给 `setContentView` 的那个 View。
- 内容根**必须**铺满窗口：根背景就是整个窗口的底图，悬浮表面和滚动边缘溶解都从它取样。
- 主题的 `android:windowBackground` **应当**设为透明，或者与 `neutralWindowBackground()` 一致，以免首帧从主题底色跳到引擎底图。
- `onFailure` 只在**整个**高级材质渲染器失败、并且回退状态已经持久化之后回调一次，而且总在当前调用栈之外触发。宿主**应当**在这里提示用户并 `recreate()`，让整页表面按柔光重新创建。
- 后端的正常降级（REFRACTION → BLUR → TRANSLUCENT）**不会**触发 `onFailure`。
- 返回 false 表示以下情况之一：还没有 `prepare()`；Activity 已经结束；绑定本身失败。

### 2.5 视效调参（自 1.1）

宿主**可以**通过 `LumenEffectTuning` 调整边缘高光、长按拖动的形变与光晕。五个字段都是相对引擎默认值的**倍率**，默认 1 即引擎原样：

| 字段 | 范围 | 作用 | 生效时机 |
|---|---|---|---|
| `edgeHighlightWidth` | 0.25～4 | 表面边缘高光的厚度。柔光缩放边框描边；高级材质缩放折射 rim 带（菲涅尔、镜面的铺展宽度）与轮廓描边 | 会话创建时 |
| `edgeHighlightIntensity` | 0～3 | 表面边缘高光的亮度；0 关闭 | 会话创建时 |
| `dragDeformation` | 0～2 | 长按拖动时控件的形变程度：跟手位移行程、按压收缩与拉伸一起缩放；0 关闭形变，光晕照常。位移仍不越过相邻卡片（§14.1），拉伸仍受绝对上限约束 | 每次按下时 |
| `dragGlowIntensity` | 0～4 | 长按拖动时触点光晕的亮度；0 关闭光晕，形变照常 | 每次按下时 |
| `dragGlowRadius` | 0.5～2 | 长按拖动时触点光晕的半径 | 每次按下时 |

- 调参经 `LumenActivityDelegate` 的第三个参数 `effectTuningProvider` 传入（继承式接入覆盖 `LumenActivity.resolveEffectTuning()`）。它与 `paletteProvider` 一样只在首次需要时调用一次，结果在本 Activity 生命周期内缓存；边缘高光的改动**必须**重建 Activity 生效（与 §3.2 切换材质相同）。
- `LumenElasticInteraction` 的 `effectTuning` 参数默认读委托的缓存值。设置页需要滑块即时预览时，宿主**可以**传入读取自己当前值的 lambda；它在每次按下时调用一次，**不应**在里面做 I/O。
- 构造时超出范围会抛 `IllegalArgumentException`。来自滑块等连续输入的值**应当**先经 `LumenEffectTuning.clamped(...)` 收进范围。
- 引擎**不**持久化调参；存哪里、怎么迁移由宿主决定。
- 调参只影响高光，不影响 §6 悬浮栏的可读性补偿（暗边、加厚色罩）与控件描边（§5.3）。

## 3. 进程级接线

### 3.1 `LumenEngine.configure()`

- 这一步是可选的。如果调用，**必须**在任何 Activity 调用 `prepare()` 之前、任何 `LumenEngine` 读写之前完成，典型位置是 `Application.onCreate`。
- 配置一旦被读取就冻结。之后再用不同的配置调用会抛出 `IllegalStateException`，这样同一进程不会前后读写两套文件。
- 存储名（`LumenStorageNames`）的约束：
  - 三个偏好文件名必须互不相同；
  - 不得含路径分隔符；
  - 构造时会校验这两点。
- 从旧版本迁移时，**应当**沿用旧文件名，这样用户已有的设置能无损接管（见 `PORTABILITY_AUDIT.md` §8）。

### 3.2 材质切换

- 切换**必须**通过 `LumenEngine.selectMaterial(context, material, realtimeCapture)` 完成。宿主**不得**直接读写引擎的偏好文件。
- 返回 **true**：宿主**必须**重建使用引擎的可见 Activity（`recreate()`），新材质才会生效。
- 返回 **false**：表示没能持久化。宿主**必须**把界面上的开关恢复原状，并提示保存失败。
- 切到 `SkinId.LIQUID` 会进入"待确认"状态：
  - 新会话第一次成功的可见绘制，才算确认健康；
  - 如果进程在确认之前死亡，或者渲染器失败，下次启动会自动回退到 `SkinId.MATERIAL_YOU`，不会反复崩溃。

  宿主不需要、也**不得**自己实现这套回退。
- 界面开关**应当**显示 `requestedMaterial()`，也就是用户的选择；诊断信息**应当**显示 `isLiquidEffective` 和 `backendName`，也就是实际生效的状态。两者可能不同，例如设备不支持，或者已经发生了健康回退。

### 3.3 内存压力

- `Application.onTrimMemory(level)` 里，**只**在以下两种情况调用 `LumenEngine.releaseGraphics()`：
  - `level == TRIM_MEMORY_RUNNING_CRITICAL`（15）；
  - `level >= TRIM_MEMORY_COMPLETE`（80）。
- **不得**按"数值越大越严重"来判断。20（`UI_HIDDEN`）只是普通的切后台；在这里释放，会让用户每次切后台都丢掉高级材质。
- `releaseGraphics()` 释放全部会话的图形资源，包括：
  - 实时取样缓冲；
  - 预缩放底图；
  - 内容节点的显示列表。

  然后把高级材质降到零额外资源的 TRANSLUCENT 后端。稳定底图保留，因为它就是用户看见的背景。
- Activity 级的 `onTrimMemory` 和 `onLowMemory` 已经由委托转发（§2.1），不需要再调用 `releaseGraphics()`。

### 3.4 多进程

引擎的状态按进程缓存。宿主**应当**只在一个 UI 进程里使用引擎。

如果多个进程都使用引擎：一个进程发起的材质切换，只对它自己立即可见，其他进程要等重启后才能读到。

## 4. 位移与手势通知

- **滚动不需要通知。** 引擎自己监听 `ViewTreeObserver` 的滚动回调。
- 以下几种方式移动带引擎表面的 View（或它的祖先）时，宿主**必须**在**每一帧**调用 `notifyPositionChanged()`：
  - `translationX/Y`、`scaleX/Y`、`rotation`；
  - 属性动画、`ViewPropertyAnimator`；
  - Transition。

  原因：属性动画既不触发滚动回调，也不重录子 View 的显示列表。不通知的话，玻璃采样会停在动画开始时的位置。典型场景：
  - 翻页器；
  - 弹窗进出场；
  - 共享元素转场；
  - 拖拽排序；
  - 展开/收起。
- 翻页器、弹窗这类同一帧里会同时改 `translation` 和 `visibility` 的宿主，**必须**在两者**都**应用之后再通知。否则引擎会按半新半旧的几何采样一帧，出现一帧错位。
- 这个通知很廉价，只是标记脏并按帧合并。同一帧里多次调用不会重复工作，宿主不需要自己节流。
- `onDispatchTouchEvent(event)` 的转发时机见 §2.1。

## 5. 表面

宿主只声明一块表面"**是什么**"（`SurfaceRole`），**不得**自己选择 Liquid 还是柔光的实现。

| 角色 | 用途 | 委托方法 | 默认圆角 |
|---|---|---|---|
| `CARD` | 普通内容卡片 | `cardBackground()` | 15dp |
| `FLOATING` | 悬浮栏、悬浮按钮 | `floatingBackground()` | 28dp |
| `TOP_BAR` | 贴边顶栏 | `topBarBackground()` | 0 |
| `SELECTED_ITEM` | 选中的条目、输入框 | `selectionBackground()` | 22dp |
| `MODAL` | 弹窗、底部面板 | `modalBackground()` | 28dp |
| `MOTION_SURFACE` | 入口与全屏形变共享的表面（§5.2） | `motionSurfaceBackground()` | 由调用方给 |
| `FILLED_BUTTON` / `TEXT_BUTTON` | 按钮 | `styleActionButton()` | 20dp |
| `CHIP` / `WINDOW` | 引擎内部使用 | — | — |

- 角色**必须**按语义选择，**不得**为了某种视觉效果挑角色，例如为了更透明把卡片标成 `FLOATING`。两套材质会按角色分配不同的光学参数和刷新策略。
- 表面 Drawable **必须**作为**恰好一个** View 的 `background` 使用：
  - 引擎通过 `Drawable.callback` 找到宿主 View，据此换算屏幕位置；
  - **不得**把同一个实例设给多个 View；
  - **不得**把它画进宿主自己的 Canvas。
- `radiusDp` 的单位是 dp。颜色参数只用于回退绘制和着色，最终视觉由材质决定。
- 宿主**不得**直接实例化引擎内部的 Drawable 类，只能通过委托获取。
- 悬浮栏里的小按钮和选中指示，**应当**使用 `chromeOverlayBackground()`：它是轻量叠层，不做逐个光学采样。只有确实需要独立玻璃的按钮，才使用 `FLOATING` 表面，并且作为 `companions` 登记到悬浮栏（§6）。

### 5.1 全屏宿主与静态底图（`LiquidStaticBackdropHost`）

有些页面的卡片背后只有背景，没有内容会从卡片下面透过来，例如二级设置页、诊断页。这类页面的 Activity **应当**实现 `LiquidStaticBackdropHost`。原因如下：

- 在这类页面上，实时截图折射出来的仍然是背景本身，没有视觉收益。
- 反而在形变动画结束后大约 0.5 秒，采样源会从稳定底图换成截图，整页控件的光影跳变一次。

实现这个接口后，引擎保留实时档的光学参数，但**从不发起 PixelCopy**，玻璃始终采样稳定底图。

内容会从玻璃下面滚过的页面（首页、列表）**不得**实现它，否则悬浮栏只能折射背景，看不到下面的内容。

### 5.2 形变表面（`MOTION_SURFACE`）

- 在入口卡片和全屏页之间做共享形变时（例如点击卡片展开成全屏），两端**应当**使用同一角色 `MOTION_SURFACE`，这样形变过程中材质连续。
- 形变中的 View 如果不通过自身 bounds 表达当前几何（例如自绘圆角、按进度插值），**必须**实现 `LiquidMotionSurfaceFrameProvider`。引擎每帧从它读取三项：
  - 当前边界；
  - 圆角；
  - 回退色。
- 调用方颜色里的 alpha 会被保留：半透明的遮罩在两套材质下都保持同样的不透明度。
- 只想在高级材质生效时才让引擎接管形变层的宿主，使用 `liquidMotionSurfaceBackgroundOrNull()`。它返回 null 时，宿主保留自己的绘制。

### 5.3 控件

- **原生控件**：宿主**可以**用 `lumen-controls` 的 `LumenControls.style(root, lumen)` 一次性换装 `SwitchCompat`、`CheckBox`、`EditText`。
  - 必须在 `prepare()` 之后、视图树构建完成之后调用。
  - 之后动态加入的控件需要再调用一次。
  - 这个方法不挂层级监听，也不轮询。
- **按钮**：用 `styleActionButton(view, filled)`。它的结构是：玻璃作为背景，涟漪放在前景。
  - 之后宿主**不得**再给这个按钮设置背景涟漪，否则会切断玻璃 Drawable 与 View 的回调。
  - 可选条目用 `styleSelectionControl()`，状态标签用 `styleStatusChip()`。
- **自定义控件**：宿主自己的开关或复选框：
  - **应当**用 `LiquidChoiceDrawable` 画轨道、滑块或勾选框；
  - 焦点和按压描边用 `controlOutline()`；
  - 取色**必须**来自 `LumenPalette`，不得引入新的色源；
  - 替换控件的 Drawable 之后，**必须**调用 `refreshDrawableState()`。否则新 Drawable 会停在默认状态，直到下一次按压才显示正确的选中态。

### 5.4 回弹与手势归属

- `installStretch(scrollTarget, isStretchAllowed)` 把一个**已经在层级中**的滚动 View 包进回弹视口。返回值：
  - 成功时返回视口；
  - 失败时返回 null，原层级保持不变。

  宿主**不得**假定返回值非 null。回弹只是修饰，失败不影响页面。
- 视口不接管滚动容器的 ID：被兄弟按 ID 约束的滚动容器要先包一层，见 §15.7。
- 滚动容器**必须**分发嵌套滚动。推荐 `NestedScrollView` 或 `RecyclerView`。
  - 平台 `ScrollView` 和 `ListView` 默认不分发嵌套滚动：包进视口后不会回弹，而且视口会把它原有的 overscroll 关掉。
  - 如果一定要用它们，必须 `setNestedScrollingEnabled(true)`。
- 包进视口之后，滚动容器的 `overScrollMode` 由视口设为 `NEVER`。宿主**不得**改回去，否则会出现两层 EdgeEffect。
- `isStretchAllowed` 在不该回弹时返回 false，例如翻页器正在翻页、这一页不是当前页。离开某一页时，**应当**调用 `finishStretch(viewport)`，让视口立即归位。
- **手势归属**：视口接住一次正在进行的回弹时，整段手势归视口所有，按下事件不会下发给内容。
  - 宿主如果有自己的全局按压高光或弹性交互控制器（挂在 Activity 的分发上、自己做命中测试的那一类），**必须**在点亮按压态之前检查 `ElasticGestureClaim.claimedAbove(view)`。为 true 时，**不得**给这个控件点亮按压高光。
  - 宿主自己的容器如果也会整段接管手势，**可以**实现 `ElasticGestureClaim` 参与同一套判定。
- **手势观察**：滚动容器如果在 `dispatchTouchEvent` 里观察手势（例如速度追踪、翻页判定），**必须**实现 `LiquidStretchGestureObserver`。视口接住回弹时，会把整段手势直接交给容器的 `onTouchEvent`，绕过 `dispatchTouchEvent`；这时 `observeTouch(event)` 是唯一的补发通道。

### 5.5 窗口背景与授权前界面

- 授权前或 `prepare()` 之前的界面，**应当**用 `neutralWindowBackground()` 作为根背景。它只用配色，不读材质偏好，也不做位图工作。
- 自定义背景图通过 `LiquidBackgroundStore` 管理，只作用于高级材质：
  - `importFromUri` 导入；
  - `restoreAutomatic` 恢复自动背景；
  - `read` 查询当前配置。
- `importFromUri` 要做文件 IO 和解码，**不得**在主线程调用。`restoreAutomatic` 会删除不再引用的背景图文件，**应当**在后台线程调用。`read` 只读偏好，可以在主线程调用。
- 渲染器在创建时读取一次背景配置。所以写入之后，**必须**重建 Activity 才会生效。

## 6. 悬浮栏

`GlowFloatingChrome` 把三项能力接到同一组悬浮栏上：

- 滚动边缘溶解；
- 根据下方内容自适应可读性；
- 内容节点玻璃（API 31+）。

### 6.0 层级结构

```
root（bindRoot）
 ├ GlowBackdropTarget      ← 内容容器：只装会从栏下穿过的内容
 │   └ 滚动容器 / 翻页器
 ├ 顶栏（FLOATING）         ← 与内容容器是兄弟
 ├ 底栏（FLOATING）
 └ 侧栏 / 悬浮按钮（可选）  ← attach 时 edge = null，不参与溶解（§15.6）
```

- 悬浮栏**必须**是 `GlowBackdropTarget` 的**兄弟**，与它同一个父容器，**不得**是它的子 View。原因：
  - 探针和内容节点玻璃录制的是"栏下面有什么"，如果把栏自己录进去，就形成反馈；
  - 溶解层必须正好夹在内容和栏之间。
- `GlowBackdropTarget` 里**只**放内容。**不得**放悬浮栏，也不得放栏的装饰层。
- `engine` 参数**必须**传一个每次现取的 lambda，写成 `{ lumen.engine }`，**不得**缓存引擎实例：高级材质中途失败时，引擎会被换成柔光。
- `attach(host, edge, foregroundColor, companions, thickenHost, onForeground)` 的各参数：
  - `foregroundColor`：栏上文字/图标的原色，一般是 `palette.textPrimary`。
  - `onForeground(boost)`：接收 0..1 的前景加强量，0 表示恢复原色。宿主**应当**用 `GlowLegibilityPolicy.foreground(color, boost)` 算出新颜色，再应用到栏上直接压着玻璃的文字和图标。
  - `companions`：栏内自带玻璃表面的子控件，与栏共用同一份补偿。
  - `thickenHost = false`：栏本体始终保持清透，补偿只加在 `companions` 上。适用于栏上没有直接压在玻璃上的文字、图标全在圆按钮里的情况；这时加厚本体只会把背景洗灰。
- `edge` 可以为 `null`（侧边栏、悬浮按钮等不贴滚动边缘的悬浮表面）；同一条边可以登记多条栏，见 §15.6。
- `coverage(edge)` 返回 0..1 的溶解覆盖度。滚动容器用 `GlowScrollEdgePolicy.topCoverage()` / `bottomCoverage()` 计算，翻页器用 `pagerCoverage()` 在相邻页之间插值。
- 翻页、展开收起这类**非滚动**的内容位移，宿主**必须**调用 `onContentMoved()`。滚动由栏自己监听。
- 在 Activity `onDestroy()` 里，**必须**先 `dispose()` 悬浮栏，再调用委托的 `onDestroy()`。

### 6.1 内容源（柔光）

宿主**必须**调用 `bindContentSource(view)`，把悬浮表面下方的内容层交给会话。这个内容层是悬浮表面的兄弟或兄弟的后代，**不得**是它的祖先。

- 柔光材质的软件透镜要重绘这一层。
- 高级材质直接截取窗口，用不到内容层；但作为休眠回退的柔光必须事先拿到它，否则高级材质中途失败切到柔光时，悬浮表面只剩静态磨砂。

所以无论当前是哪种材质，都要调用。

## 7. 触摸光晕

`GlowState`、`GlowConfig`、`GlowShape`、`GlowFrame`、`TouchGlowRenderer` 组成触摸光晕：

- **策略部分**是纯几何状态机，每帧零分配，可以在 JVM 上单测；
- **绘制部分**只建一次 shader。

宿主的约束：

- 宿主表面**不得**自己构建 `RadialGradient`、`BlurMaskFilter`、`RenderEffect` 或 `saveLayer` 来画按压光晕。一律通过 `TouchGlowRenderer.draw(canvas, state.shape)` 绘制，因为以上几种都会在每帧产生离屏图层或重建 shader。
- `TouchGlowRenderer.recolor()` 只允许在配置期调用。每帧调用它等于每帧重建 shader，是性能红线。
- 触点**必须**换算到控件**当前帧**的坐标系：如果控件正在做平移动画，要先减去它的 `translationX/Y`，再写进 `GlowFrame`。否则光晕会跟着动画漂移。
- 每个表面**必须**持有并复用自己的 `GlowState`，每帧调用 `update(frame, dtSeconds, radiusPx, baseAlpha, config)`。`dtSeconds` **必须**是真实的帧间隔，这样 60/90/120Hz 走的是同一条曲线。
- `GlowState.reset()` **只**能在不可见的边界上调用：`press == 0` 的那一帧，或者表面销毁时。在打断续航时 reset，会让可见的椭圆瞬间变回正圆。
- `GlowConfig` 用 `GlowConfig.create(density, …)` 按密度生成，每个表面生成一次，**不得**每帧新建。

## 8. 性能与内存预算

以下上限由引擎强制执行，宿主不需要配置。列出来是为了让宿主评估自己的预算。

| 项 | 上限 |
|---|---|
| 高级材质实时取样 | 三缓冲轮转；按约 100 万像素的预算缩放（下限 24 万像素，缩放倍率不超过 0.72）。1080×2400 与 1440×3200 都收敛到约 671×1490，单缓冲约 3.81 MiB，三缓冲约 11.44 MiB；最高 120 fps |
| 实时取样静止门控 | 连续 2 张截图与当前帧逐像素相同，就停止采集；窗口有新绘制时，等 2 帧再截；静止期间每 1s 兜底探测一次 |
| 滚动中的抑制 | 滚动期间玻璃采样稳定底图；停顿不超过 160ms 仍算同一次滚动 |
| 实时取样失败 | 间隔 120ms 重试；连续 4 次失败后暂停采集 |
| 高级材质稳定底图 | 0.25× 采样，单缓冲 ≤ 2 MiB |
| 自定义背景的光学副本 | ≤ 2 MiB |
| 柔光环境底图 | 0.20× 采样，≤ 16 万像素 |
| 柔光透镜采样 | ≤ 2.4 万像素 / 次，间隔 ≥ 28ms |
| 悬浮栏可读性探针 | ≤ 8 Hz（125ms），补偿过渡 240ms |
| 自定义背景输入 | 文件 ≤ 32 MiB；声明尺寸 ≤ 64 Mi 像素 |
| 自定义背景存储 | 规范化后 ≤ 4 Mi 像素、≤ 16 MiB |

- **后端降级**：按下表条件选后端。某一档失败后，本次会话内单向降级，不再回头尝试。

| 后端 | 条件 |
|---|---|
| REFRACTION | API 33+，硬件加速 |
| BLUR | API 31+，硬件加速 |
| TRANSLUCENT | 其他所有情况 |

- **后台线程**：引擎只使用自己的守护线程，线程名一律以 `Lumen-` 开头。这些线程在会话关闭时释放；悬浮栏探针的线程在 `dispose()` 时释放。
- **回调线程**：引擎交给宿主的回调一律在主线程触发，包括 `onFailure`、`onForeground`、`GlowEngineCallbacks`。

## 9. 存储与隐私

- 引擎只写宿主私有目录，内容有四样：
  - 三个 SharedPreferences 文件：材质选择与健康状态、实时取样开关、自定义背景配置；
  - `filesDir` 下的一个背景图目录。

  文件名由 `LumenStorageNames` 决定。
- 引擎**没有**网络访问，**不**上传任何数据，**不**记录用户内容。
  - 实时取样（PixelCopy）只截取宿主自己的窗口，结果只存在于内存里的轮转缓冲中，不落盘。
  - 可读性探针只统计亮度和细节两个标量。
- 宿主如果有"清除数据"或"恢复默认"功能，**应当**用 `selectMaterial(context, SkinId.MATERIAL_YOU)` 和 `LiquidBackgroundStore.restoreAutomatic()` 复位，**不得**直接删除引擎的文件。
- 引擎不申请任何权限。自定义背景通过宿主传入的 `content://` Uri 读取，读取权限由宿主的选择器授予。

### 9.1 自定义背景预览（自1.1.0）

宿主可以用 `LiquidBackgroundStore.decodePreview(context, config, viewWidth, viewHeight, palette)`
显示导入后的图片预览。配色与尺寸均显式传入；引擎不读取 View、宿主主题或设置。

- 布局完成后取得预览控件的像素宽高，再在后台线程调用；主线程调用会被拒绝。
- 控件尺寸在 2 MiB ARGB_8888 预算内时按实际像素解码，超出后等比缩小；不改变图片导入或静态背景预算。
- 返回 null 表示自动背景、尚未测量的尺寸或资产不可解码。失败只影响预览，不触发材质失败。
- 宿主拥有任务和位图：替换请求、关闭页面时取消任务，交付时核对代次、Activity 和 View 的有效性。
  未显示的迟到位图可以 `recycle()`；已显示的位图随 Drawable/display list 引用释放，不提前回收。
- 宿主可以参照 `sample/SampleBackgroundPreviewLoader.kt`：每个 Activity 一份 worker，
  `onDestroy` 关闭，弱引用接收者，失效结果不交付。

## 10. 验收清单

宿主接入完成后，逐条确认：

- [ ] §2.1 的六个回调全部转发，时机正确。
- [ ] 授权门、早退分支之后才调用 `prepare()`；`prepare()` 在构建视图之前。
- [ ] `bindRoot()` 在 `setContentView` 之后；`onFailure` 里提示并 `recreate()`。
- [ ] `Application.onTrimMemory` 只在 15 或 ≥ 80 时调用 `releaseGraphics()`。
- [ ] 材质开关：`selectMaterial` 返回 true 时 `recreate()`，返回 false 时恢复开关并提示。
- [ ] 每一个属性动画位移，都逐帧调用 `notifyPositionChanged()`；翻页器在平移和可见性都应用之后才通知。
- [ ] 悬浮栏是 `GlowBackdropTarget` 的兄弟；`bindContentSource` 已调用；`onDestroy` 前 `dispose()`。
- [ ] 回弹的滚动容器支持嵌套滚动；宿主的按压高光检查 `ElasticGestureClaim.claimedAbove`。
- [ ] 替换控件 Drawable 后调用了 `refreshDrawableState()`。
- [ ] 两种材质各截一张静止画面，确认表面、悬浮栏、控件都正常。
- [ ] 高级材质下连续滚动 30 秒无掉帧；切后台再回来，材质保持不变。
- [ ] （使用 `lumen-motion` 时）§12 的三处接线齐全：`dispatch` 包住原分发、`onPause`/`onStop` 调 `clear()`、`onDestroy` 前 `dispose()`；自带手势的控件打了 `EXCLUDED_TAG`，它们的直接宿主放行了两层裁剪。
- [ ] 所有弹窗都经 `LumenModalPresenter` 构造、呈现、关闭；标题是容器第一个子 View；没有自设 `OnDismissListener`。
- [ ] 二级面板里开三级面板时，在点击那一刻 `captureSubPanel`；"去别处"的动作调用了 `dismissParent`。
- [ ] 条目形变成全屏页：目标页是透明窗口主题；返回的四个回调都转给了控制器；页内返回按钮已登记。
- [ ] 手势返回在弹窗、全屏页上都能拖动预览、取消回弹、松手续接（API 34+）。
- [ ] （多种尺寸与排布）卡片圆角由背景声明（自绘背景实现了 `getOutline`）；卡中卡的外层打了 `CONTAINER_TAG`；网格里的手风琴格子没有纵向撑满；`RecyclerView` 条目里没有用 `SectionExpansionController`（§14）。
- [ ] 在网格、横向轮播、大小混排里各长按拖动一次：卡片不钻到邻居底下；从方块和大卡片打开的面板从卡片自己的圆角长出来。
- [ ] （排布）被兄弟按 ID 约束的滚动容器先包了一层再装回弹视口；标题不在弹窗第一个子 View 时显式传了 `titleView`；底栏 ≤ 6 项、档位条 ≤ 8 段（§15）。
- [ ] 开发者选项里的"不保留活动"打开时，反复进出页面不泄漏（用 LeakCanary 或 heap dump 确认没有残留的会话）。

## 11. 不在本标准范围内

- **Compose**：引擎基于 View 系统。
  - 纯 Compose 应用**可以**把 `ComposeView` 放进 `bindRoot` 的根里，从而获得根背景和实时取样；但表面 API 返回的是 Drawable，Compose 组件需要通过 `AndroidView` 或 `drawBehind` 自己桥接。
  - 这条路径没有经过验证。
- **多窗口与画中画**：沿用系统行为，没有做专门适配。
- **注入到别的应用里的界面**（例如来源工程注入 B 站的评论拓扑面板）：它们不在宿主自己的 Activity 里，不适用本标准。
- **宿主自己实现材质**（实现 `GlowEngine`）：`GlowEngine` 是公开的 SPI，但 1.0 还没有开放把自定义实现注册进会话的入口。实现方**必须**遵守 `ENGINEERING_RULES.md` 的全部规则。

## 12. 长按弹性与高光（`lumen-motion`）

长按一个控件约 160ms 后拖动，它会跟手位移、按压收缩，触点高光在控件里流动、越界时贴边堆积；松手弹簧回弹。原有的点击、选中不受影响：普通轻点照常派发，只有判定为"按住后拖动"时，才向原事件流补发一次 `CANCEL`，再接管剩余事件。

宿主通过 `LumenElasticInteraction` 接入：

- **必须**在 `dispatchTouchEvent` 里先转发 `lumen.onDispatchTouchEvent(event)`，然后 `return elastic.dispatch(event) { super.dispatchTouchEvent(it) }`。原分发只能经这个 lambda 传入一次，**不得**再把控制器装成 `OnTouchListener`。
- **必须**在 `onPause`、`onStop` 里调用 `clear()`；在委托的 `onDestroy()` 之前调用 `dispose()`。
- 弹窗窗口用 `installDialog(dialog)` 接入。`LumenModalPresenter` 会自动调用；宿主自建的 Dialog 要在 `setContentView` 之后自己调用。
- 控制器只在按下时检查视图树；之后每帧只校验缓存的路径，并写四个属性和一层 overlay。它不截图，也不开空闲轮询。
- 形变程度与触点光晕的亮度、半径**可以**用 §2.5 的 `dragDeformation`、`dragGlowIntensity`、`dragGlowRadius` 调整。

### 12.1 参与与排除

- 以下控件不参与弹性，保持原生语义：
  - 开关类控件（除 `CheckBox`、`RadioButton` 以外的 `CompoundButton`，含 `Switch`、`SwitchCompat`）；
  - 输入框、`SeekBar`；
  - 长按有自己含义的控件；
  - 可选中文本；
  - 能滚动的控件。
- **自己处理按压/拖动的控件必须打 `ElasticInteractionController.EXCLUDED_TAG`**，否则两套手势会互相抢。包括：`LumenNavigationBar`、`LumenSegmentScrubBar`、`ItemTouchHelper` 管理的列表行、宿主自己的拖动控件。
- 叠在图标上的角标这类装饰控件也**应当**打 `EXCLUDED_TAG`，让整枚图标（含角标）作为一个形变组；否则按在角标上时，只有角标在动。
- 弹窗的内容容器会被呈现器打上 `CONTAINER_TAG`：容器可以承载参与弹性的控件，但点空白处时容器自己不形变。

### 12.2 形变组与高光

- 命中的控件会被**提升到它的表面拥有者**，也就是最近一层有背景的祖先，作为形变单位；紧贴包裹、没有活动余量的包装层会继续向上提升。所以一个"卡片"会整体动，而不是只有卡片里的某一行字在动。
- 高光按控件的 outline 圆角裁剪。点击涟漪**应当**用 `CoverableRippleDrawable.rounded(palette, radiusPx)`，它的 content 与 mask 同圆角。原因：透明 content 的 outline 是直角，高光会裁成方角，和涟漪边缘割裂。
- 可展开的标题行、悬浮胶囊本体**应当**参与弹性，不要打 `EXCLUDED_TAG`。胶囊本体设为 `isClickable = true` 后，长按落在按钮以外时，整条胶囊一起形变。
- **不得**用 `View.alpha` 去隐藏带涟漪的控件：alpha 为 0 时父级跳过绘制，涟漪会被冻结，还原时才补播一次。

### 12.3 溢出绘制：宿主容器必须放行裁剪

按压缩放、拖动位移、光晕都会画出控件自身的边界。

- 这类控件的**直接宿主容器必须**设 `clipChildren = false`、`clipToPadding = false`。控件自己设置管不了父容器这一层；`clipToPadding` 默认为 true，会把溢出部分齐切成"矩形断框"。
- 硬约束：溢出上界要小于宿主的内边距。胶囊底栏、档位条这类控件大约溢出 8dp。
- 全局弹性在拖动期间会临时放行祖先链的裁剪，收尾时恢复，宿主不需要为它额外处理。

### 12.4 自带手势的控件

- **`LumenNavigationBar`**（胶囊底栏，1～6 项，见 §15.1）：
  - 轻点选页；横向按住拖动时滑块跟手，松手吸附到最近一页；纵向拖动只做弹性位移，不选页；
  - 触点高光带方向；
  - 宿主在翻页器的 `onPositionChanged` 里调用 `setPageProgress(position, notifyPositionChanged = false)`，在 `onPageSelected` 里调用 `setSelectedPage(index)`，把悬浮栏可读性接到 `setLegibility(boost, palette.surface)`，销毁时 `dispose()`。
- **`LumenSegmentScrubBar`**（分段档位条）：与底栏共用手势判定、高光和弹簧；用 `configure(labels, colors, thumbBackground, trackBackground, selectedIndex, onSelect)` 配置，1～8 段（§15.1）。
- 两者都**必须**满足 §12.1 的 `EXCLUDED_TAG` 和 §12.3 的宿主裁剪。

### 12.5 列表拖拽排序（`lumen-controls`）

`ItemTouchHelper(LumenReorderCallback(canDrag, onMove, onDrop)).attachToRecyclerView(list)` 的效果：

- 长按拾起时有轻触觉反馈，同时放大到 1.03、透明度降到 0.9；
- 其余行逐级补位；
- 松手后按拖动距离滑回落位，时长 220～420ms。

宿主的义务：

- 拖动中途**不得**向适配器提交外部数据。`ItemTouchHelper` 正持有被拖行的索引，外部提交会把顺序弹回去。只在 `onMove` 里同步本地顺序，在 `onDrop` 里一次性提交。
- **应当**关掉默认的 change 交叉淡化（`supportsChangeAnimations = false`）。
- 过滤、搜索视图下，可见顺序与存储顺序不同构，**应当**通过 `canDrag` 禁用拖拽。

## 13. 打开、关闭与可打断动画（`lumen-motion`）

### 13.0 可打断动画内核

所有形变共用同一套打断语义：

- `InterruptibleMotionSession` 记录值和速度，用代次让过期的回调与 post 失效；
- `InterruptibleMotionContinuation` 从当前值和当前速度续接到新目标，切线有界，不会过冲；
- `InterruptibleMotionPolicy` 决定何时保留当前画面参数、剩余时长怎样按剩余行程缩短。

宿主自己写可打断动画时，**应当**复用这套内核，**不应**另写一套速度承接。

### 13.1 弹窗呈现器（通用规则）

每个 Activity 持有一个 `LumenModalPresenter`（弹窗内容换装用 `styleContent`）。所有弹窗都通过它来构造、呈现和关闭。

- **构造**：用 `createContainer()` 创建内容容器；容器的**第一个子 View 应当是标题 TextView**（§13.3）。
- **呈现**：`present(dialog, container, anchor = 来源控件)`。
  - 传入来源控件，就从来源位置形变出来；不传则居中缩放入场。
  - 来源**必须**是用户实际点击的那一行（可见、可点的条目），**不得**是整个可滚动的父分组。
- **关闭**：一律 `dismiss(dialog, container) { 后续动作 }`，**不得**自己写退场，也**不得**直接 `dialog.dismiss()`。锚点弹窗会自动走对应的收起形变，并在收拢后才执行后续动作。
- **不得**给这些 Dialog 设置自己的 `OnDismissListener`：呈现器在里面做收尾，包括释放弹性、注销返回回调、归还父面板。
- 需要弹输入法的面板，**应当**在 `onExpanded` 回调里再弹，不要在形变途中弹。
- 同一时刻只有一张"当前弹窗"。呈现新弹窗会硬关当前那张，覆盖式子面板除外（§13.4）。
- 呈现器已经处理好的时序，宿主不需要重复：
  - 压暗层铺到系统栏下面；
  - 窗口动画在 `setContentView` 之后关闭；
  - 返回回调在 `show()` 之后注册；
  - 收起末帧上屏后下一帧再移窗；
  - 旋转、分屏时直接落到稳定端；
  - 逐帧通知引擎按新位置采样。

### 13.2 锚定气泡

`present(..., anchor = 图标, anchorStyle = ModalAnchorStyle.BUBBLE)`：弹窗贴在工具栏小图标旁边，伸出一个指向图标的小角，以小角尖端为轴缩放；正文逐行链式浮现，相邻行恒定重叠 50%。

- 来源**必须**是 `ImageView`：气泡期间图标图案交给代理层飞行，原位图案总量守恒。
- 图标按钮的涟漪**应当**是 `CoverableRippleDrawable`，否则气泡盖上来后涟漪退场会把按钮提亮一下。
- 气泡曲线是按短行程调的，**不得**挪用到长行程的居中形变上（§13.9）。

### 13.3 标题迁移与描述分层

条目形变时，如果条目标题与弹窗标题**文字相同**，标题会从条目的位置平移、缩放到弹窗标题的位置；描述文字留在原位渐隐。

- 想要标题迁移，入口行标题与弹窗标题**应当**用同一段文字（同一个字符串资源）。
- 文字不同时静默降级，只做容器形变。这个降级不报错、不进日志，所以宿主**应当**用测试守住这条约定。
- 入口行允许是"标题 + 换行 + 摘要"合成的一个 TextView，此时只有渲染后的首行参与匹配。

### 13.4 覆盖式子面板（二级面板里开三级面板）

在一张面板里的控件上打开下一张面板时（例如 GitHub 面板里的"遥测说明"），父面板**不关闭**：子面板从被点的控件长出来，展开端正好盖住父面板；收起时父面板重新露出来。

- **必须**在控件被点击的**那一刻**调用 `presenter.captureSubPanel(parentDialog, parentContainer, source)`，再调用 `presentSubPanel(origin, dialog, container)`。
  - 形变一开始父面板就会改 alpha 和 outline，事后取到的位置不是用户看到的那个。
  - 父面板取的是**画出来的表面**，不是容器矩形：气泡面板的小角高度在 padding 里。
- 子面板宽度与左上角严格对齐父面板，高度取两者的较大值。内容比父面板矮时，**应当**在关闭行上方放一条 `LinearLayout.LayoutParams(-1, 0, 1f)` 的弹性占位，让关闭行贴着卡片底边、与父面板那颗重合。
- 子面板里"关掉自己再去别处"的动作（例如打开第三张面板），**必须**调用 `presenter.dismissParent(origin)`，让父面板与子面板的退场并行，然后在 `dismiss(子面板) { ... }` 的回调里打开下一张。
- 呈现器已经处理：
  - 子面板不叠第二层压暗和模糊；
  - 父面板在被覆盖的区域里按子面板不透明度让位（外轮廓不回缩、开头不露底、结尾不叠亮），完全盖满时才收起共边描边；
  - 子面板关闭后，把"当前弹窗"还给父面板。
- 来源控件在一张**即将关闭**的面板里、且不需要覆盖时，改用 `present(..., anchorBounds = presenter.anchorBounds(source))`：同样**必须**在点击那一刻抓取。

### 13.5 条目形变成全屏页

从一个条目打开一个 Activity，条目的卡片形变成整个页面：带标题迁移、可 seek 的预测式返回，以及打断续接。

- **来源页**：`ContainerMorphLauncher.launch(activity, Target::class.java, entry, entryTitle)`；来源页销毁时调用 `ContainerMorphLauncher.clear(Target::class.java, entry)`。
- **目标页主题必须**是透明窗口：`windowIsTranslucent=true`、`windowBackground` 透明、`backgroundDimEnabled=false`、`windowIsFloating=false`。收缩时要露出下面的来源页。
- **目标页 `onCreate` 的步骤**：
  1. `ContainerMorphController.suppressSystemTransitions(this)`；
  2. 构造 `ContainerMorphHost`：折叠端表面与来源条目同色同圆角；
  3. `setContentView(host)` → `host.installContentInsets()` → `host.replacePage(page, toolbarTitle)` → `lumen.bindRoot(host.liquidBackdropRoot())`；
  4. 构造 `ContainerMorphController`，调用 `start(savedInstanceState == null)`。
- **返回**：把 `OnBackPressedCallback` 的四个回调分别转给 `beginPredictiveBack` / `progressPredictiveBack` / `cancelPredictiveBack` / `commitBack`；页内返回按钮用 `host.registerNavigationBack(button)` 登记。形变期间整页拦截输入，只放行这一枚按钮。
- 业务上暂时不能返回时（导出中、选择器打开、页内弹窗开着），通过 `isBusinessBlocked` 告诉控制器。
- 形变期间到达的新内容，**应当**延后到 `onExpanded` 再渲染。
- 回弹视口**应当**在 `onExpanded` 里安装、在 `onMotionStarted` 里结束，`isStretchAllowed` 用 `controller.isSettledExpanded`。

### 13.6 翻页与文字链

`LumenPagePager`（页数不设上限，见 §15.1）的行为：

- 横向拖动随时可以接住正在进行的翻页：从当前位置接手，按下不会冻结动画；
- 点击底栏跨页时，按距离和速度走非线性时长（240～560ms）。

`PageTextChain(pager, headings)` 让各页文字按"离手指远近"链式跟随：只写绘制矩阵，不改布局。

宿主的接线：

- 在 `pager.onPositionChanged` 里依次调用：
  - `navigation.setPageProgress(...)`；
  - `lumen.notifyPositionChanged()`；
  - `chrome.onContentMoved()`；
  - `textChain.onPositionChanged()`。

  翻页器保证在平移和可见性都应用之后才回调（§4）。
- 在 `onPageSelected` 里结束上一页的回弹；在 `onMotionStarted` 里结束所有页的回弹。每页回弹的 `isStretchAllowed` 是"是当前页，且 `pager.isSettled`"。
- 页内容器**应当**放行两层裁剪，否则文字链会被行矩形截断。带表面的文字控件（按钮、胶囊）不参与文字链。
- 使用 AppCompat 开关的宿主**应当**设置 `pager.switchParts = { LumenControls.switchParts(it) ?: LumenPagePager.frameworkSwitchParts(it) }`：按在开关拨钮上时交给开关，按在开关行的文字上横滑时照常翻页。
- 页内滚动容器**应当**用 `LumenPageScrollView`：它会把用户滚动、键盘翻页、无障碍滚动都报成"用户导航"。
- 销毁时调用 `textChain.dispose()`。

### 13.7 手风琴

`SectionExpansionController(card, content, chevron, density, notifyPositionChanged = { lumen.notifyPositionChanged() })`。

- `content` 是 `card` 的子 View，初始为 `GONE`；展开和收起都调用 `setExpanded(target)`。
- 一个进度弹簧同时驱动以下几项：
  - 整条祖先链的圆角裁剪；
  - 内容行的级联显影；
  - 兄弟控件的滑行；
  - 箭头的转角。
- 反转只是翻转目标：展开到一半收起，会从中间态连续倒带。
- 宿主**不得**再给展开和收起写自己的高度动画：逐帧改 `layoutParams.height` 会让整棵树每帧重新布局。

### 13.8 定位并高亮

`LumenReveal(accentColor).reveal(scrollView, target, settling, topOffsetPx)` 把目标滚到停靠点，然后在目标上闪一次圆角高亮。

- `scrollView` 可以是 `ScrollView` / `NestedScrollView`，也可以是 `RecyclerView` 等任意能竖向滚动的容器（§15.2）。
- 目标所在的分组如果正在展开，**必须**通过 `settling` 传入：几何稳定之前不计算停靠点。
- 有顶部悬浮栏时，`topOffsetPx` = 栏高 + 留白。
- 宿主在 `onPause`、开始其他导航（翻页、用户滚动）时**应当**调用 `cancel()`。

### 13.9 微动效与曲线

`MicroMotion` 提供四个小过渡，都可以被下一次调用打断：

- `swapText`：文字淡出、换字、淡入；
- `showBadge` / `hideBadge`：角标弹出和收起；
- `setVisible`：子项显隐时，父容器让位；
- `revealHint`：提示条滑入。

命名曲线在 `LumenEasing` 里。**曲线要按行程长度选**：`secondaryExpand` 适合短行程加缩放，用在长行程的居中形变上，头几十毫秒就会走掉大半行程。

### 13.10 预测式返回

- 宿主**应当**在 manifest 的 `<application>` 上声明 `android:enableOnBackInvokedCallback="true"`（配合 `tools:targetApi="33"`）。Android 16 起，系统对 targetSdk 36+ 强制启用。
- 弹窗的返回由 `LumenModalPresenter` 处理：
  - API 34+ 支持拖动预览、取消回弹、松手续接；
  - API 33 松手后才开始动画；
  - 三键导航走按键。
- 全屏形变页的返回见 §13.5。
- 来源工程里"按窗口开关预测式返回"的功能依赖隐藏 API 反射，**没有**纳入引擎（`ENGINEERING_RULES.md` §3）。

### 13.11 选中框连贯滑动（自 1.1）

`LumenSlidingSelection` 是单选组：选中框是一块独立的表面，点新选项时从旧位置连续滑到新位置，位置与尺寸一起插值（260ms，强调减速曲线，与来源工程一致）；滑动途中再点别的选项，从当前位置与速度续接。竖向列表与横向分段都适用。

- 选中框的表面**应当**用 `lumen.selectionBackground(...)` 或 `lumen.surface(..., SurfaceRole.SELECTED_ITEM)`，圆角与选项的涟漪一致。
- 选项**不得**自带不透明表面：选中框画在选项下面，会被遮住。选项只放涟漪（`CoverableRippleDrawable.rounded`）；需要"轨道"时把表面设在 `LumenSlidingSelection` 自身上（横向分段控件的写法）。
- **必须**把 `notifyPositionChanged` 接到 `lumen::notifyPositionChanged`：选中框逐帧移动，高级材质需要跟着重新采样（§4）。
- 选项的点击由控件接管：用户选中不同选项时回调 `onSelect`；程序调用 `select(index, animate)` 不回调。
- 标题渐变**应当**用 `setOnHighlightListener { index, weight -> }`：`weight` 是选中框盖住该选项的比例，连点、改向、长距离滑动时都连续，不会有停在半高亮的行。
- 选中后会重建页面的设置（切换材质、深浅色、配色）**应当**等选中框滑到位（约 280ms）再应用，连点只保留最后一次（见 `sample` 的 `choiceRow`）。
- 选项高度可以不同（长说明折行）；行在滑动途中变高、首帧之后才定高，选中框都会跟上，宿主不需要手动重摆。
- 选中的行被长按拖动形变时，选中框跟随行的位移与缩放；横向分段控件把表面设在控件自身时，整条控件是一个形变单位（§12.2）。

## 14. 尺寸与排布适配（`lumen-motion`）

同一套交互与动效会被放进不同尺寸的卡片（小图标、细长条目、方块、大卡片、横幅）和不同排布（竖向列表、网格、大小混排、瀑布流、横向轮播、卡中卡）。能自动适配的，引擎按几何自己算；需要宿主配合的，写在各小节里。

### 14.0 尺寸自适应一览

| 组件 | 随尺寸变化的量 |
|---|---|
| 长按弹性 | 行程上限 = min(18dp, 短边 × 16%)；拉伸每轴最多长大 2.5dp；高光半径 = 长边 × 0.7，按控件自己的 outline 圆角裁剪 |
| 弹窗形变、全屏页形变 | 起点圆角 = 来源自己声明的圆角（不超过短边一半）；没有声明时取短边一半（图标收成正圆） |
| 拖拽排序 | 拾起放大 = min(1.03, 1 + 8dp ÷ 长边)：一般行放大 3%，大卡片最多长大 8dp |
| 文字链 | 偏移 ≤ min(到所在卡片边缘的余量, 条目 28dp / 标题 32dp)：窄卡片动得少，不会被卡片边缘截断 |
| 手风琴 | 每一层的收卷圆角取该层自己的 outline 圆角 |
| 定位高亮 | 比视口还高的目标按顶部对齐；比轮播视口还宽的目标按起始边对齐 |

### 14.1 长按弹性：网格、瀑布流、轮播与卡中卡

- 形变组朝**同层兄弟**的方向，最多走到离兄弟 2.5dp 的地方，不会钻到邻居底下。朝父容器内缘的方向仍按行程上限放行。
  - 竖向列表里只有上下两个方向有兄弟；网格、瀑布流、轮播里四个方向都可能有。
  - 这对竖向列表也是一处行为变化：来源工程里卡片朝邻居能走满 18dp（会与邻居重叠几 dp），现在停在邻居前。
- 邻居只在**同一个父容器**里找。所以卡片**应当**是排布容器的直接子 View；如果每张卡片外面还包了一层，这层包装**应当**紧贴卡片（没有内边距），这样形变组会提升到包装层，邻居就是其他包装层。
- **卡中卡**：外层卡片里放着几张各自独立的小卡片时，外层卡片**必须**打 `ElasticInteractionController.CONTAINER_TAG`。否则外层卡片有表面、又有多个孩子，会被当成一个整体：按任何一张小卡片，整张外层卡片一起动。
- 卡片的圆角**应当**由背景声明，`lumen.cardBackground(color, radiusDp)` 已经声明。宿主自绘卡片背景时**必须**实现 `Drawable.getOutline`，否则高光按直角裁剪，形变起点也会退回短边一半。

### 14.2 形变的起点

- 从条目、方块、大卡片打开弹窗（§13.1），或打开全屏页（§13.5），形变都从来源自己的位置和圆角开始：
  - 方块从圆角方块长出来；
  - 大卡片会缩成比它小的面板；
  - 部分滚出屏幕的卡片，从可见的那一部分开始。
- 来源在另一张即将关闭的面板里时（§13.4），圆角要和矩形一起在点击那一刻抓取：`captureSubPanel` 会自动抓；直接调用 `present(..., anchorBounds = ...)` 时，**应当**同时传 `anchorCornerRadiusPx`。
- 全屏页形变会自动把入口卡片的圆角写进 `Intent`。目标页 `ContainerMorphController` 的 `collapsedCornerRadiusDp` 只在入口报不出圆角时兜底。
- 锚定气泡（`ModalAnchorStyle.BUBBLE`）只用于工具栏小图标（约 48dp 以内）。卡片类来源一律用默认的 `CONTAINER`。

### 14.3 手风琴：放在什么容器里

`SectionExpansionController` 按分节所在的容器决定谁跟着滑、滑多少：

| 容器 | 行为 |
|---|---|
| 竖向 `LinearLayout` | 后面的兄弟整段跟随（来源工程的情形，行为不变） |
| `GridLayout`、`TableLayout`、横排 `LinearLayout` | 一行的高度 = 这一行最高那格。卡片变矮 s 时，行只变矮 `max(E, m) − max(E − s, m)`（E = 卡片展开高度，m = 同行其他格的最高高度）。同行有更高的格子时，下面的行一动不动。换算后的量继续往上层传 |
| 其他容器（`ConstraintLayout`、`RelativeLayout`、瀑布流的列……） | 只有正下方、同一列的兄弟跟随 |

宿主的义务：

- 网格里的分节卡片和同行格子**不得**纵向撑满行高（不要用 `FILL_VERTICAL` 一类的纵向重力），否则引擎读不到"同行其他格"的真实高度。
- **不得**把 `SectionExpansionController` 用在 `RecyclerView` 的条目里：滚动容器会截断跟随链，条目还会被回收复用。`RecyclerView` 里的展开条目改用它自己的 item animator（`notifyItemChanged`）。
- 手风琴所在的每一层 wrap 容器**应当**放行两层裁剪（§12.3），否则跟随的兄弟滑动时会被切。

### 14.4 文字链：多列与轮播

- 多列网格里，同一行的文字一起动：链式先后按离手指的纵向距离排。
- **横向轮播**（`HorizontalScrollView`、横向 `RecyclerView` 等一切能滚动的容器）是文字链的裁剪边界：
  - 引擎不放开它的裁剪；
  - 文字的偏移余量算到轮播视口边；
  - 滚出轮播视口的卡片不参与。
- 宿主不需要做任何额外接线。

### 14.5 拖拽排序：网格与横向列表

- `LumenReorderCallback` 按 `LayoutManager` 自动决定能往哪个方向拖：
  - `GridLayoutManager`、`StaggeredGridLayoutManager`（多列）：上下左右；
  - 横向列表：左右；
  - 竖向列表：上下。

  需要限制方向时，传 `directions`。
- 拾起时的放大量按长边封顶（§14.0），宿主不需要为大方块另外调参。

### 14.6 定位：轮播里的目标

- 目标在横向轮播里时，`LumenReveal` 先把轮播横向滚到目标完整可见（两侧留 12dp），竖向和横向都到位后才闪高亮。
- 目标**必须**已经挂在视图树上。`RecyclerView` 里还没绑定到屏幕上的条目，**应当**先 `scrollToPosition`，等布局完成后再调用 `reveal`。

### 14.7 保持来源交互与形变效果（自1.1.0）

已有界面接入时，宿主可以显式选择原效果，而不改变引擎默认的多排布适配：

- `elastic.travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS`：仅按父容器内缘与最低行程预算限制拖动，允许原列表的交叠效果。默认 `AVOID_NEIGHBORS` 仍遵循 §14.1。规则在每次按下时固定，修改只影响后续按压；Activity 封装会传给现有和新建的弹窗控制器。
- `modals.anchorCornerMode = MorphCornerMode.CAPSULE`：锚点形变从短边一半的圆角开始；默认 `DECLARED` 仍遵循 §14.2。每次 `present` 固定本面板的规则，后续修改不影响该面板的打开、打断与反向关闭。
- 沿用原来不限制面板最大宽度的行为时，传 `LumenModalStyle(maxWidthDp = 0)`；其他尺寸仍按宿主原值显式传入。
- 行程选项不会改变点击监听、手势阈值、弹簧与光晕；圆角选项不会改变标题来源、正文时序或返回手势。宿主仍须逐项验证原界面的静止、移动、打断与返回效果。

## 15. 排布上的约定与限制

§14 讲的是同一套动效在不同尺寸、排布里怎样自己适配。本节讲另一类问题：来源工程只在"手机竖屏、4 个页面、单条顶栏和底栏"上跑过，代码里写死了一些数量、尺寸、容器类型和方向。已经去掉的写在前面；做不到自动适配、需要宿主遵守的，写成约定。

### 15.1 数量

| 组件 | 来源工程 | 现在 |
|---|---|---|
| `LumenPagePager` / `PageTextChain` | 最多 4 页 | 不设上限 |
| `LumenNavigationBar` | 最多 4 项 | 最多 6 项，超出时构造报错；默认宽度按项数放宽（每项约 72dp，至少 320dp） |
| `LumenSegmentScrubBar` | 超过 4 段**静默丢弃** | 最多 8 段，超出时 `configure` 报错 |

- 分页器的每一页都常驻内存。页数多、页面重时，宿主**应当**按需填充页内容。
- 远距离跳页的时长仍封顶 560ms，中间页会依次快速划过。页数很多时，宿主**应当**考虑用列表或搜索代替点击底栏远跳。
- 6 项以上的一级导航**不应**放进胶囊底栏，应当改用侧边栏或"更多"入口。

### 15.2 滚动容器的类型

| 能力 | 支持的滚动容器 |
|---|---|
| 定位高亮（§13.8） | `ScrollView` / `NestedScrollView`，按绝对位置定位；`RecyclerView` 以及其他能竖向滚动的容器，按剩余距离闭环推进，滚到底即停 |
| 手风琴的视口跟随（§13.7） | `ScrollView` / `NestedScrollView`（单个内容子 View）。`RecyclerView` 条目里不得使用，见 §14.3 |
| 回弹视口（§5.4） | 只处理竖向。**不应**对横向轮播调用 `installStretch`，它们保留平台自己的横向拉伸效果 |
| 文字链（§14.4） | 任何能滚动的容器都视为裁剪边界 |

### 15.3 窗口：分屏、自由窗口、折叠屏

- 弹窗、气泡的位置都按 **Activity 窗口**计算，不按整块屏幕。窗口比屏幕小，或者不在屏幕原点时，同样正确。
- 条目形变成全屏页时，来源页和目标页的窗口尺寸**应当**一致（容差 4px），两边才能直接按窗口坐标对齐。
  - 不一致时退而按屏幕坐标对齐；
  - 两种都对不上时（例如来源在分屏的一半、目标全屏），不拿错位的矩形硬做形变：入场直接显示完整页面，关闭时用不依赖来源位置的淡出缩小。
- 窗口在形变途中改变尺寸（旋转、调整分屏）时，所有形变都会直接落到稳定端。

### 15.4 弹窗与形变的外观

- **标题**：标题迁移的目标端默认取弹窗容器的第一个子 View。标题放在"图标 + 标题"的横排里、或前面还有别的控件时，**应当**通过 `present(..., titleView = ...)` 显式传入，否则标题迁移会静默降级。
- **宽度**：宿主没给确定宽度时，弹窗卡片最宽 `LumenModalStyle.maxWidthDp`（默认 560dp），平板、横屏里长文字不会把卡片撑满整个窗口。确定宽度一律照办：`preferredWidth`、覆盖式子面板对齐父面板、气泡。
- **字形**：条目形变成全屏页时，飞行标题的字体、字距、字体特性、字体内边距（`includeFontPadding`）取自目标页标题；颜色保持来源条目的颜色。
- **落点**：两端都按文字本身对齐，不按 View 边框。标题可以带内边距、复合图标，也可以在更宽的 View 里居中，引擎会扣掉这些偏移（按首行计算）。

### 15.5 从右到左布局

- **已适配**：
  - 翻页方向、底栏与档位条的命中和指示位置、文字链的偏移方向；
  - 弹窗和气泡的几何（按物理坐标计算，天然不受文字方向影响）；
  - 角标弹出的轴心（`MicroMotion.showBadge`，按角标自己的布局方向解析起始侧；挂在起始侧上角时传 `growFromEnd = true`）。
- **已知限制**：
  - 条目形变成全屏页时，只有两端标题都是从左到右的单行文字才做标题迁移。从右到左时自动降级为只做容器形变，标题不飞。
  - 锚定气泡的小角只朝上或朝下。侧边栏图标上的气泡会贴在图标上方或下方，不会从侧面伸出。

### 15.6 悬浮栏：侧边栏、悬浮按钮、多条栏

- `GlowFloatingChrome.attach(host, edge, ...)` 的 `edge` 可以为 `null`，给不贴滚动边缘的悬浮表面用：平板侧边导航栏、悬浮按钮、悬浮迷你播放条。它们照样做可读性补偿和内容节点玻璃，只是不参与滚动边缘溶解。
- 同一条边可以登记多条栏（例如顶栏 + 标签栏），溶解层按它们的并集计算。
- 所有悬浮表面都**必须**是 `GlowBackdropTarget` 的兄弟（§6.0）。

### 15.7 回弹视口与按 ID 的约束

`installStretch(scrollTarget)` 会把滚动容器换到回弹视口下面。视口接过原来的 `LayoutParams`，但**不接管** ID：接管的话，宿主的 `findViewById` 会拿到视口而不是滚动容器，按 ID 保存的滚动位置也会丢。

所以，如果滚动容器在 `ConstraintLayout` / `RelativeLayout` 里、并被兄弟**按 ID 引用**（例如"某个 View 位于滚动容器下方"），宿主**必须**先把滚动容器包进一个 `FrameLayout`，把约束和被引用的 ID 放在这层包装上，再对里面的滚动容器调用 `installStretch`。


## 16. 局部与注入视效（1.1）

不控制整个Activity时使用LumenSurfaceSession，接线与约束见[SURFACE_SESSIONS.md](SURFACE_SESSIONS.md)。
不得为了使用局部材质而重建第三方Activity或重挂它的内容；来源必须匹配实际窗口并排除注入表面。
配置、Hook、业务输入与前景文字由宿主管理，采样、材质与资源生命周期交给会话。

### 16.1 受控增强（1.2.0）

独立四角、双形状融合、局部按压、光源、渐进模糊及细节质量使用`LumenSurfaceEnhancements`，接线、全部参数和回退见[VISUAL_EFFECTS.md](VISUAL_EFFECTS.md)。
来源授权和保护状态由宿主显式声明；禁止/独立Surface不得借助回退采样绕过。固定时钟由宿主推进，参数JSON不包含实时输入事件或像素。
`LumenFusedSelection`只是背景装饰，保留原选项的业务和无障碍语义；有外扩的直接容器显式关闭clipChildren与clipToPadding并留出空间。
