# 凝光视效引擎可移植性审查报告

> 审查对象：Bilibili Innocent Lab 模块（`com.Bilibili_Innocent_Lab.xposedmodule`，main 分支 2026-09-27）中的凝光视效引擎。
> 审查结论已落实为本工程 `lumen-engine` 1.0.0。本文记录"原来是什么样、改了什么、为什么、还剩什么"。
> 第二轮把长按弹性、打断动画、打开与关闭动画纳入为 `lumen-motion`，见 §9。

## 1. 结论

| 项 | 结论 |
|---|---|
| 总体可移植性 | **高**。引擎在来源工程里已按契约（`GlowEngine`）组织，`ui/skin` 下 68 个源码文件中只有 8 个引用引擎以外的代码，没有反射，没有 Hook/Xposed 依赖。 |
| 需要解耦的外部依赖 | 6 类（配色、资源色、弹性交互、Activity 基类、BetterAndroid/KavaRef 工具函数、偏好文件名），全部已处理，见 §3。 |
| 抽离后的行为 | 与来源工程一致。迁移的 312 个单元/契约测试全部通过；示例应用在真机（Android 16，1440×3168，120Hz）上柔光与高级材质两种材质均正常渲染，高级材质走 REFRACTION 后端并完成首帧健康确认。 |
| 旧系统安全性 | DEX 审计（`tools/audit_checkcast.py`）：高于 minSdk 27 的平台类型只出现在 `*Api28/29/31/33` 隔离类中，调用点全部在 SDK 判断之后，不存在 2026-09-11 那类 `check-cast` 崩溃形态。 |
| 静态检查 | 三个模块 Lint 0 错误；引擎 8 条警告全部来自迁移前的原代码或 Gradle 版本提示（§6.3）。 |

## 2. 审查范围

| 纳入引擎 | 来源（`ui/…`） | 文件数 | 说明 |
|---|---|---|---|
| 契约与悬浮栏可读性 | `skin/engine` → `glow` | 9 | `GlowEngine` 契约、`GlowFloatingChrome`、内容节点玻璃、滚动边缘溶解、可读性策略 |
| 高级材质 | `skin/liquid` | 30 | RuntimeShader 折射、PixelCopy 实时取样、后端降级链、刷新率/热/ADPF |
| 柔光材质 | `skin/material` | 7 | 静态环境磨砂 + 软件透镜 |
| 会话与状态机 | `skin/runtime` | 10 | 会话、材质选择与健康回退状态机、内存压力 |
| 背景 | `skin/background` | 4 | 环境底图、自定义背景图导入 |
| 几何 | `skin/geometry` | 2 | 视图采样矩阵 |
| 模型 | `skin/model` | 5 | 皮肤标识、表面角色、令牌 |
| 触摸光晕 | `activity/AdaptiveGlowPolicy`、`widget/TouchGlowRenderer` | 2 | 触点光晕的纯几何状态机 + 绘制器 |
| 手势归属 | `interaction/ElasticGestureClaim` | 1 | 回弹视口接管手势的声明接口 |

**第一轮不纳入**（属于应用而非引擎）：

- `ElasticInteractionController`：全局长按弹性交互。它挂在 Activity 分发上、给任意控件做形变，是交互框架而不是视效引擎；引擎只提供它需要的 `ElasticGestureClaim` 与 `notifyPositionChanged`。**第二轮已纳入 `lumen-motion`（§9）。**
- `MonetColors` / `ModernPalette` 的取色部分：从壁纸生成调色板（依赖 m3color 与应用的配色规范设置）属于宿主主题系统。
- 底栏、日志档位条、弹窗形变等具体控件：它们是引擎的**使用者**。**第二轮已纳入 `lumen-motion`（§9）。**

## 3. 外部依赖与处理

| # | 来源工程中的依赖 | 出现位置 | 处理 |
|---|---|---|---|
| 1 | `ui.theme.MonetColors`（7 色调色板，含壁纸取色） | 7 个文件 | 引擎自带 `LumenPalette`（9 色，多了两个文字色）；取色留给宿主。`LumenPalette.modern()` 原样保留来源工程"中性表面 + 强调色"的数值。 |
| 2 | 模块资源 `R.color.colorTextGray / colorTextDark` | `SkinnedActivity`、`MaterialYouTokenResolver` | 并入 `LumenPalette.textPrimary / textSecondary`（来源工程命名反直觉：`colorTextGray` 才是主文字色，映射按实际语义）。 |
| 3 | `ElasticInteractionController` | `SkinnedActivity` | 不迁移（§2）。 |
| 4 | `ElasticGestureClaim` | `LiquidStretchViewport` | 迁入 `interaction` 包并公开。 |
| 5 | BetterAndroid `AppViewsActivity` | `SkinnedActivity`、`ActivitySkinSession` | 会话改收 `android.app.Activity`；宿主接入改为组合式 `LumenActivityDelegate`（任意 Activity 基类可用）+ 可选的 `LumenActivity`。 |
| 6 | BetterAndroid `AndroidVersion` ×10、`parentOrNull` ×2，KavaRef `classOf` ×2 | 11 个文件 | 换成原生 `Build.VERSION` / `parent as? ViewGroup` / `X::class.java`。随之删除 15 处 `@SuppressLint("ReplaceWithAndroidVersion")`（BetterAndroid 自带 lint 规则的压制注解）。是否在宿主层使用 BetterAndroid 见 `BETTERANDROID_INITIATIVE.md`。 |
| 7 | AppCompat `SwitchCompat` | `SkinnedActivity` 的控件换装 | 拆到可选模块 `lumen-controls`；引擎本体不依赖 AppCompat。 |
| 8 | 偏好文件名 `ui_skin_preferences` / `ui_liquid_effect_preferences` / `liquid_background_preferences`、背景图目录 `ui_skin_background` | 3 个文件 | `LumenStorageNames` 可配置，默认 `lumen_*`。来源工程将来迁移时配置成旧名即可无损接管用户设置。 |
| 9 | `GithubUpdateBadgeDrawable`（`skinUpdateBadge`） | `SkinnedActivity` | 应用专属控件，不迁移。 |

另有三处机械清理：线程与 RenderNode 名的 `BIL-` / `BilibiliInnocentLab-` 前缀改为 `Lumen-`（10 处）；`ModuleMemoryPressureHub` 改名 `LumenMemoryPressureHub` 并删除来源工程专属的空方法 `persistForImminentKill`；源码注释里指向来源工程文件（`AGENTS.md`、`MainActivity`）的表述改为本工程文档或通用说法。

## 4. 审查中顺带发现并修正的问题

| 问题 | 证据 | 处理 |
|---|---|---|
| `ActivitySkinSession.surfaceBackground` 的 `materialOutline` 参数是死参数 | 会话收到后从未读取，直接调用 `activeEngine.surface(color, radius, role)`；来源工程各调用点对同一角色传值不一致（"选中项"有时 true 有时 false），但都不影响结果 | 删除参数。行为不变。 |
| 两个"是否支持内容节点玻璃"的属性里含版本判断，但 lint 看不穿 | 去掉 BetterAndroid 后 lint 报 `AnnotateVersionCheck` | 加 `@get:ChecksSdkIntAtLeast(api = S)`；随之删除调用处两个永远不成立的冗余 `SDK_INT < S` 判断（逻辑等价）。 |
| 自定义背景解码线程名没有统一前缀 | `liquid-background-loader`，其余线程都是 `Lumen-*` | 改名 `Lumen-LiquidBackground`；注释里残留的 `BIL-LiquidCapture` 一并改为 `Lumen-LiquidCapture`。 |
| 示例应用的回弹无效 | 回弹视口靠嵌套滚动工作，平台 `ScrollView` 默认不分发嵌套滚动（来源工程用的是 `NestedScrollView`）；包进视口后还会被关掉原有的 overscroll | 示例改用 `NestedScrollView`；适配标准 §5.4 写明滚动容器必须分发嵌套滚动。**未上真机复验**（见 §7）。 |

## 5. 公开 API 面

来源工程里引擎全部是 `internal`（同一模块内使用）。独立引擎只把宿主**确实需要**的类型改为 `public`，改动集中由 `tools/open_api.py` 完成，完整清单见 `API.md`。原则：

- 宿主接入入口（`LumenEngine`、`LumenActivityDelegate`、`LumenActivity`）、数据类型（`LumenPalette`、`SkinId`、`SurfaceRole`）、可组合组件（悬浮栏、背景图、触摸光晕）公开。
- `GlowEngine` 作为 SPI 公开：宿主将来可以实现自己的材质。
- 渲染管道、状态机、策略类保持 `internal`；`GlowBackdropTarget` 上三个只给引擎自己用的方法、`LiquidBackgroundStore.decodeBackdrop`（渲染器内部的后台解码）由 public 收回 internal。
- `reachablePileRoomPx` 由 internal 改为 public：公开字段 `GlowFrame.pileRoomPx` 的文档要求用它计算，宿主必须能调用。
- 兼容承诺只覆盖 `API.md` 列出的声明；Kotlin 对象成员默认公开带出来的辅助函数不在承诺内（`VERSIONING.md` §1）。

## 6. 测试迁移

### 6.1 数量

来源工程中与引擎相关的测试文件 45 个 + 触摸光晕 6 个。迁移后 47 个测试套件、**312 个测试，全部通过**。

| 未迁移 | 原因 |
|---|---|
| `FairRunningMemoryCallSiteTest`（整文件） | 断言来源工程 `DefaultApplication` 与公平内存协调器的接线 |
| `ModalSkinPositionContractTest`（整文件） | 断言来源工程 `MainActivity` 弹窗动画逐帧通知皮肤 |
| `ChromeBackdropCoordinatesInstrumentedTest`（设备测试） | 需要真机运行；独立工程暂未建立设备测试流水线，见 §7 |

### 6.2 从迁移文件中移除的 11 个用例（全部是应用层接线断言）

| 文件 | 原 → 新 | 移除的内容 | 归宿 |
|---|---|---|---|
| `AdaptiveGlowRenderGuardTest` | 5 → 2 | 底栏、档位条、弹性控制器三个宿主控件不自建 shader、触点换算到当前坐标系 | 适配标准 §7 |
| `ControlDrawableStateHandoffTest` | 2 → 1 | 来源工程自定义开关 `MaterialSwitch` 自取配色后刷新状态 | 适配标准 §5.3 |
| `FrostedMotionSurfaceIntegrationTest` | 6 → 4 | 设置备份页、诊断页两个全屏宿主的接线 | 适配标准 §5.1 |
| `GlowFloatingChromeWiringTest` | 8 → 6 | 设置首页层级（内容容器只装 pager、栏是兄弟）、翻页通知 | 适配标准 §6 |
| `LiquidControlStyleTest` | 8 → 6 | 35 个弹窗统一使用模态容器；二级页与更新角标接线 | 应用层约定，不进引擎标准 |
| `PositionRefreshTest`（原 `SettingsPagePositionRefreshTest`） | 5 → 4 | 设置页翻页器"应用平移与可见性后才通知" | 适配标准 §4 |

另有若干用例**改写断言对象**但保留意图：`SkinnedActivity` 的控件换装、位移转发、手势转发、中性窗口背景、按角色取表面，改为断言 `LumenControls` / `LumenActivityDelegate` / `LumenActivity`；`FrostedMotionSurfaceIntegrationTest` 原来只测来源工程诊断入口的两档遮罩透明度，改为遍历一组有代表性的调用方透明度。

### 6.3 静态检查

| 模块 | Lint Error | Lint Warning |
|---|---|---|
| `lumen-engine` | 0 | 8：`ExifInterface` ×2、`UseKtx` ×2（`LiquidBackgroundStore`，迁移前原代码）；`AndroidGradlePluginVersion` ×2、`GradleDependency`、`NewerVersionAvailable`（版本提示） |
| `lumen-controls` | 0 | 0 |
| `sample` | 0 | 7：`SetTextI18n` ×5、`DataExtractionRules`、`MissingApplicationIcon`（示例应用，有意从简） |

## 7. 未验证与已知限制

- **示例回弹**：示例改用 `NestedScrollView` 后的新包已安装到测试机，但验证时用户正在使用手机，没有注入手势，回弹效果未经真机确认。
- **设备矩阵**：真机验证只覆盖一台 Android 16 设备（REFRACTION 后端）。BLUR（API 31–32）与 TRANSLUCENT（API 27–30、软件画布）两档后端只有 JVM 策略测试与来源工程的历史真机记录，没有在独立工程里重新跑过。
- **设备测试**：`ChromeBackdropCoordinatesInstrumentedTest`（内容节点坐标与 View 坐标一致）未迁移。
- **逐像素回归**：来源工程重构时用"同一画面两次截图最大差 0"做过逐像素证明；独立工程尚未为示例应用建立基线截图。
- **发布**：Maven 发布只验证到工程内的本地仓库（`build/repo`，两个 AAR、sources jar、POM 与 Gradle 模块元数据齐全，`lumen-controls` 的 POM 正确依赖 `lumen-engine:1.0.0`）；没有发布到任何远程仓库，也没有用外部宿主工程按坐标实际接入过。
- **Compose**：引擎基于 View 系统。纯 Compose 应用需要通过 `AndroidView` 或在根 View 上接入，未做专门适配。
- **来源工程尚未切换**：Bilibili Innocent Lab 仍使用自己的那份引擎代码，两份代码目前各自维护。切换方式见 §8。

## 8. 来源工程今后切换到本引擎的路径

1. `LumenEngine.configure(LumenEngineConfig(LumenStorageNames(skinPreferences = "ui_skin_preferences", realtimeCapturePreferences = "ui_liquid_effect_preferences", backgroundPreferences = "liquid_background_preferences", backgroundAssetDirectory = "ui_skin_background")))`：沿用旧文件名，用户的材质选择、实时取样开关、自定义背景无损保留。
2. `SkinnedActivity` 改为持有 `LumenActivityDelegate` 与 `LumenElasticInteraction`，保留更新角标；`monetColors` 换算成 `LumenPalette`。`MainActivity` 的弹窗底座改为 `LumenModalPresenter`；已外移的 72 处 `dismissWithAnimation` 改为 `modals.dismiss`，遥测/更新渠道子面板改用 `captureSubPanel` / `presentSubPanel`；两个全屏页改用 `ContainerMorphController`。
3. 应用层契约测试（§6.2 移除的 11 个用例）留在来源工程，改为断言对引擎公开 API 的调用。
4. 切换前后按来源工程 2026-09-23 的方法拍两套材质的静止画面，逐像素对比。

## 9. 第二轮：交互与动效的纳入（`lumen-motion`）

第一轮按"视效引擎"划界，把长按弹性、弹窗形变、底栏等判为引擎的使用者（§2）。按用户要求，第二轮把这些交互与动效整体纳入，做成独立的可选模块 `lumen-motion`。引擎本体不变。

### 9.1 结论

| 项 | 结论 |
|---|---|
| 可移植性 | **高**。38 个来源文件的外部依赖只有 BetterAndroid 小工具函数（§9.3）和两处 AppCompat 类型判断。真正的耦合在两个 Activity 的编排里（约 1150 行），已抽成通用组件。 |
| 抽离后的行为 | 编排逐段照搬，真机教训的注释原样保留；迁移的 123 个契约用例断言的正是这些顺序与记账，全部通过。 |
| 依赖 | 只依赖 `lumen-engine` 和 AndroidX core，不依赖 AppCompat、RecyclerView，零反射，都有测试守住。 |
| 验证 | 280 + 1 个测试全部通过；四个模块 Lint 0 错误；DEX 审计 41 处高版本类型引用全部在隔离类里。**没有上真机**（§9.6）。 |

### 9.2 纳入范围

| 能力 | 来源 | 目标 |
|---|---|---|
| 长按拖动形变与触点高光 | `ui/interaction/Elastic*`，`SkinnedActivity` 的接线 | `interaction/`、`LumenElasticInteraction` |
| 可打断动画内核 | `NavigationMotionPolicy.kt` | `motion/InterruptibleMotion.kt` |
| 弹窗打开/关闭：锚点形变、气泡、标题迁移与描述分层、父面板让位、背景模糊、预测式返回 | `IconAnchoredMotion*`、`Bubble*`、`ModalTitle*`、`ModalCardRoot`、`ModalBackdropBlur*`、`PredictiveBackApi33`，以及 `MainActivity` 的呈现与关闭编排 | `motion/modal/`、`LumenModalPresenter` |
| 二级面板里开三级面板（覆盖式子面板） | `presentSizedModalDialog` 的 cover 路径，以及遥测/更新渠道面板的调用约定 | `captureSubPanel` / `presentSubPanel` / `dismissParent` |
| 条目形变成全屏页 | `SettingsBackupMotion*`、`DiagnosticsTransitionOrigin`、`DiagnosticsActivity` 编排、`MainActivity.launchDiagnostics` | `motion/morph/` |
| 翻页、文字链、页内滚动 | `SettingsPagePager`、`SettingsPageMotionPolicy`、`SettingsPageTextChain`、`SettingsHomeScrollView` | `motion/pager/` |
| 手风琴 | `SectionExpansionController`、`ExpansionMotionPolicy`、`NestedExpansionPolicy` | `motion/expansion/` |
| 定位并高亮 | `SettingsRevealScrollMotion`、`SettingsRevealRequest`、`MainActivity` 的定位与高亮 | `motion/reveal/` |
| 微动效 | 文字切换、角标弹出、显隐过渡、提示条滑入（散在 `MainActivity` 与三个弹窗文件里） | `MicroMotion`、`LumenEasing` |
| 胶囊底栏、档位条、涟漪、气泡 Drawable | `ModernNavigationBar`、`ModernNavigationMotion`、`LogSegmentScrubBar`、`CoverableRippleDrawable`、`BubbleDrawable` | `widget/` |
| 长按拖拽排序 | `SettingsFavoritesDialogs` 的 `ItemTouchHelper` 回调 | `lumen-controls` 的 `LumenReorderCallback` |

**不纳入**：

- **`PredictiveBack.kt`**：用 KavaRef 反射调隐藏 API，按窗口开关预测式返回，与零反射冲突。宿主改用 manifest 声明（适配标准 §13.10）。
- **`MaterialSwitch`**：带设置项身份、收藏同步，属于业务控件。开关换装由 `lumen-controls` 提供。
- **`ReplyTopologyPanelView` 等注入到 B 站的界面**：不在宿主自己的 Activity 里。
- **激活卡、更新角标 Drawable、发布亮点**：业务视觉。角标的弹出动画已提炼进 `MicroMotion`。

### 9.3 依赖处理

| 来源依赖 | 处理 |
|---|---|
| BetterAndroid：`AndroidVersion` ×2、`child()` ×9、`textToString()` ×7、`parentOrNull()` ×1、`textColor` ×1；Hikage `@HikageView` ×1 | 换成原生写法（迁移脚本逐项计数） |
| `SwitchCompat` 类型判断：弹性排除、翻页器识别开关拨钮 | 弹性排除泛化为"除复选框、单选框以外的 `CompoundButton`"（对 `SwitchCompat` / `Switch` 行为不变；`ToggleButton` 从参与变为不参与）；翻页器改为可注入的 `switchParts`，`SwitchCompat` 版由 `lumen-controls` 提供 |
| `AppCompatTextView`（档位条的分段标签） | 改用 `TextView` |
| 皮肤会话调用（`skinModalBackground`、`notifyPreparedSkinPositionChanged`、`isMaterialYouSkinEffective` 等） | 改用 `LumenActivityDelegate` 的公开 API |
| 应用偏好（背景模糊开关、预测式返回开关） | 背景模糊改为 `LumenModalStyle.backdropBlur`（默认关）；预测式返回开关不纳入 |
| 按 `object` 单例登记的全屏形变来源 | 改为按目标 Activity 分开的登记表 |

### 9.4 测试迁移

| 类别 | 数量 |
|---|---|
| 策略单测（15 个文件） | 152 个用例，原样迁移 |
| 源码契约测试（17 个文件，按"翻译命名、改断言对象"迁移） | 123 个用例 |
| 新增：`MotionExtractionContractTest` | 5 个用例：零 AppCompat、零反射、子面板 API、窗口时序、按目标页登记 |
| 新增：`LumenReorderCallbackTest`（lumen-controls） | 1 个用例 |

删除或改写的来源用例（都是断言来源工程业务页面的），以及它们的归宿：

| 用例 | 归宿 |
|---|---|
| `DiagnosticsLateCallbackTest`（整文件，3 个） | 诊断页的数据回调，属业务 |
| `SettingsPagePositionRefreshTest` 的 4 个引擎用例 | 已在 `lumen-engine` 的 `PositionRefreshTest` |
| `ElasticCaptureGateTest.newUpdateBadgeDoesNotStealTheIconGesture` | 适配标准 §12.1（角标打 `EXCLUDED_TAG`） |
| `SectionExpansionWiringTest.sectionToggleDelegatesToTheProgressDrivenController` | 适配标准 §13.7 |
| `BubbleDrawableGeometryTest` 的 2 个（更新角标、自由复制调用方） | 业务调用方 |
| `ModalAnchorRegressionTest.scannedAndUnscannedPanels…` | 适配标准 §13.1（来源是可点的那一行） |
| `ModalMotionRefinementTest.missingSnapshot…`、`updateChannelCloseButtonSitsOnTheCardBottom` | 前者属业务；后者见适配标准 §13.4（弹性占位） |
| `BubbleLayerIntegrationTest.keyboardWaits…` 里搜索弹窗的部分 | 适配标准 §13.1（输入法在 `onExpanded` 里弹）；引擎侧断言保留 |
| `ModalBackdropBlurSpecTest` 里偏好存储与设置界面位置的断言 | 改为断言 `LumenModalStyle.backdropBlur` 默认关闭 |
| 未迁移：`InteractiveOverdrawClipConventionTest`、`ModalTitleMotionConventionTest`、`SwitchConfirmationMotionTest`、`ElasticExpandableRowGateTest` | 都是来源工程调用点的约定，分别写进适配标准 §12.3、§13.3、§13.1、§12.2 |

### 9.5 顺带修正

- `BubbleDrawable` 在动效层里没有调用方，是独立的带箭头气泡 Drawable，已从 `motion/modal` 挪到 `widget` 并公开。
- 原 `MainActivity` 里弹窗容器 `elevation = if (liquid || materialYou) 0 else 12dp` 这两个条件互补，结果恒为 0；抽离后直接写 0，由测试钉住。

### 9.6 未验证

- **真机**：本轮收尾时手机没有连接（`adb devices` 为空）。以下观感都**没有**在真机上看过：示例里的长按弹性、翻页打断、文字链、弹窗形变、覆盖式子面板、气泡、全屏页形变与预测式返回、手风琴、拖拽排序、定位高亮。
- 抽出的编排依靠逐段照搬和 123 个契约用例保证与来源工程一致，但契约测试只能证明写法与顺序，证明不了观感。

## 10. 第三轮：多种卡片尺寸与排布的适配

来源工程的交互与动效是在"竖向设置列表 + 细长条目 + 工具栏小图标"上调出来的。本轮逐个组件检查了它们对尺寸与排布的隐含假设，修正了七处。规则写进适配标准 §14；纯几何放进可测试的策略函数。

### 10.1 发现与处理

| 组件 | 原来的假设 | 放进什么排布会出错 | 处理 |
|---|---|---|---|
| 弹窗锚点形变 | 起点圆角 = 短边一半（来源只有图标与细长行） | 方块、大卡片从一个圆开始长 | 优先用来源声明的 outline 圆角；子面板在点击那一刻一并抓取 |
| 全屏页形变 | 起点圆角是目标页写死的参数 | 圆角不同的入口交接时跳变 | 入口圆角随 `Intent` 传过去，参数只兜底 |
| 长按弹性 | 行程只看父容器内缘，并用行程上限兜底 | 网格、瀑布流、轮播里卡片钻到邻居底下 | 朝同层兄弟的方向，最多走到离兄弟 2.5dp 处 |
| 手风琴 | 只让竖向 `LinearLayout` 里后面的兄弟跟随 | 网格、约束布局里下面的卡片在动画起点跳位 | 竖向列表不变；网格与横排做行高换算；其他容器推同一列 |
| 文字链 | 放开裁剪时只跳过竖向滚动容器；只按竖向视口筛选 | 横向轮播滚出视口的卡片会画出来 | 一切能滚的容器都是裁剪边界；滚出视口的卡片不参与 |
| 拖拽排序 | 只允许上下拖；拾起统一放大 3% | 网格、横向列表不能换位；大方块鼓出一圈 | 按 `LayoutManager` 自动放开方向；放大量按长边封顶 8dp |
| 定位高亮 | 只做竖向滚动 | 轮播里不可见的目标定位不到 | 先横向滚到目标完整可见，两步都到位再高亮 |

已经自适应、本轮只做了确认的：

- 弹性行程上限（短边 16%，封顶 18dp）；
- 拉伸增长（每轴 2.5dp）；
- 高光半径（长边 0.7）与 outline 圆角裁剪；
- 手风琴每层自己的圆角；
- 文字链按到卡片边缘的余量收紧。

### 10.2 行为变化

- **竖向列表里的长按弹性**：来源工程允许卡片朝邻居走满 18dp（与邻居重叠几 dp，半透明玻璃叠在一起），现在停在离邻居 2.5dp 处。
- **从细长条目打开弹窗**：起点从胶囊（短边一半）变为条目自己声明的圆角。无背景图标的行为不变。

### 10.3 验证

| 项 | 结果 |
|---|---|
| 测试 | `lumen-motion` 296 个（新增 `LayoutAdaptationPolicyTest` 12 个、`LayoutAdaptationContractTest` 4 个），`lumen-controls` 2 个，`lumen-engine` 312 个，全部通过 |
| Lint | 四个模块 0 错误 |
| DEX 审计 | 41 处高版本类型引用全部在隔离类里 |
| 示例 | 新增第四页"排布"：两列网格、大小混排、横向轮播、卡中卡、网格里的手风琴；列表页新增三列网格排序 |

**仍未上真机**：本轮收尾时手机仍未连接。上面这些观感都没有在真机上看过。

## 11. 第四轮：排布上的硬编码

按类别扫了三个库模块：数量上限、写死的尺寸、只认某种方向或容器类型、用屏幕尺寸代替窗口尺寸、从右到左布局。规则写进适配标准 §15。

### 11.1 发现与处理

| # | 位置 | 硬编码 | 限制了什么 | 处理 |
|---|---|---|---|---|
| 1 | `LumenPagePager`、`PageTextChain`、`PageMotionPolicy` | 最多 4 页（按 4 开数组、`require`） | 5 页以上的分页 | 去掉上限，按页号存 |
| 2 | `LumenSegmentScrubBar` | 段数 `coerceIn(0, 4)`：第 5 段起**静默丢弃** | 5～8 档的分段选择 | 上限 8，超出时报错；数组按段数分配 |
| 3 | `LumenNavigationBar`、`NavigationBarMotion` | 最多 4 项；默认宽度写死 320dp | 5 项底栏 | 上限 6（超出报错）；几何函数不再按 4 夹紧；默认宽度按项数放宽 |
| 4 | `GlowFloatingChrome`（引擎本体） | 每条栏必须贴顶或贴底；同一条边的多条栏逐条覆盖 | 侧边导航栏、悬浮按钮；顶栏 + 标签栏 | 边可以为空（不做溶解，照样做可读性与节点玻璃）；同边多条栏按并集 |
| 5 | `LumenReveal` | 只认单子 View 的滚动容器、按绝对位置滚；横向那一步把内容坐标当成可见坐标 | `RecyclerView` 列表；已滚动过的 `HorizontalScrollView` | 非 `ScrollView` 容器按剩余距离闭环推进；横向扣掉容器滚动量（第三轮引入的缺陷，本轮修正） |
| 6 | `LumenModalPresenter` | 气泡首次摆位用整块屏幕的尺寸 | 分屏、自由窗口、折叠屏 | 用 Activity 窗口的尺寸与原点 |
| 7 | `LumenModalPresenter` | 标题必须是容器第一个子 View；卡片没有最大宽度 | 带图标的标题行；平板、横屏 | 新增 `titleView` 参数；`maxWidthDp`（默认 560dp，只约束未给确定宽度的卡片） |
| 8 | `ContainerMorphHost`、`ContainerMorphOrigin` | 飞行标题写死粗体；两端按标题 View 的边框定位 | 目标页换字体、字距；标题带内边距时，打开动画结束横跳一个内边距（真机测得 32px） | 沿用目标页标题的字体、字距、字体特性、字体内边距；两端改按文字本身定位 |
| 9 | `MicroMotion.showBadge` | 轴心写死在左下角 | 从右到左布局；挂在起始侧的角标 | 按布局方向解析；`growFromEnd` 参数 |
| 10 | `LiquidStretchViewport`（引擎本体） | 替换层级时不接管 ID | 被兄弟按 ID 约束的滚动容器 | 不改代码（接管 ID 会让宿主 `findViewById` 拿错 View、丢滚动状态）；写成宿主约定 §15.7 |

### 11.2 已知限制（本轮不改）

- **全屏页形变的标题迁移只支持从左到右**：从右到左时自动降级为只做容器形变。要支持得在形变帧里按右边缘插值；没有从右到左环境的真机验证，本轮不做。
- **锚定气泡的小角只朝上或朝下**：侧边栏图标上的气泡会贴在图标上方或下方。
- **回弹视口只处理竖向**：横向轮播保留平台自己的拉伸效果。
- **胶囊底栏只有横向形态**：竖向的侧边导航栏不在本组件范围内。侧边栏可以用普通 View 实现，并以 `edge = null` 登记到悬浮栏。

### 11.3 验证

| 项 | 结果 |
|---|---|
| 测试 | `lumen-engine` 314 个（新增 `ChromeEdgeContractTest` 2 个）；`lumen-motion` 304 个（新增 `LayoutLimitContractTest` 7 个，`PageMotionPolicyTest` 新增 1 个）；`lumen-controls` 2 个；全部通过 |
| Lint | 四个模块 0 错误 |
| 示例 | 档位条改为 5 段；新增以 `edge = null` 登记的悬浮按钮 |

**真机（2026-09-28，1440×3168，API 36）**：5 段档位条、轮播定位（先手动横滚再定位）、悬浮按钮、气泡、弹窗形变、全屏页形变、翻页、底栏拖动、拖动排序均通过。
- 录屏逐帧测得，全屏页打开结束时标题横跳 32px（上表第 8 项），已修，修后落点与真标题重合。
- 示例的角标行多加了 `topMargin`，角标下半截被切掉，已修。
- 没测：分屏、从右到左布局、平板宽度。
