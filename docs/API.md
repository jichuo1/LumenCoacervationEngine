# 公开 API 清单

> 覆盖当前版本全部受兼容承诺保护的公开声明；各节标题注明引入版本。新增或改变公开声明时同步本文。

> **只有本文列出的声明受兼容承诺保护**（`VERSIONING.md`）。
>
> 有些公开对象里还有未列出的辅助函数或常量，例如 `GlowLegibilityPolicy` 与 `GlowScrollEdgePolicy` 的内部计算函数。它们目前是 `public`，只是因为 Kotlin 对象的成员默认公开，不属于契约，可能在次版本中收回或改变。
>
> 除非另有说明，以下全部 API 只能在主线程调用。所在节号指 `INTEGRATION_STANDARD.md`。

## 1. 进程级

### `com.lumen.coacervation.engine.LumenEngine`（object）

| 成员 | 说明 | 标准 |
|---|---|---|
| `const val VERSION: String` | 引擎版本（语义化版本） | — |
| `const val CONTRACT_VERSION: Int` | 契约版本，当前为 1 | — |
| `fun configure(config: LumenEngineConfig)` | 可选；必须在首次使用前调用；配置读取即冻结 | §3.1 |
| `val config: LumenEngineConfig` | 当前配置；读取即冻结 | §3.1 |
| `fun requestedMaterial(context): SkinId` | 持久化的材质选择 | §3.2 |
| `fun selectMaterial(context, material, realtimeCapture: Boolean? = null): Boolean` | 切换材质；true 时宿主 `recreate()` | §3.2 |
| `fun isRealtimeCaptureEnabled(context): Boolean` | 全屏实时取样开关 | §3.2 |
| `fun setRealtimeCaptureEnabled(context, enabled): Boolean` | 写入开关；需重建 Activity 生效 | §3.2 |
| `fun releaseGraphics()` | 进程级内存告急（trim 15 / ≥80）时释放图形资源 | §3.3 |

### `LumenEngineConfig`（data class）

- `storage: LumenStorageNames = LumenStorageNames()`

### `LumenStorageNames`（data class）

| 属性 | 默认值 |
|---|---|
| `skinPreferences` | `lumen_skin_preferences` |
| `realtimeCapturePreferences` | `lumen_realtime_capture_preferences` |
| `backgroundPreferences` | `lumen_background_preferences` |
| `backgroundAssetDirectory` | `lumen_background` |

构造时会校验：名字不能为空，不能含路径分隔符，三个偏好文件名必须互不相同。

## 2. Activity 接入（`com.lumen.coacervation.engine.host`）

### `LumenActivityDelegate(activity: Activity, paletteProvider: () -> LumenPalette, controlShadowProvider: () -> LumenControlShadowOptions = { LumenControlShadowOptions.DISABLED }, effectTuningProvider: () -> LumenEffectTuning = { LumenEffectTuning.DEFAULT })`

`effectTuningProvider` 自 1.1.0；`controlShadowProvider` 自 1.5.0，插在 `effectTuningProvider` **前面**，既有尾随 lambda 写法不变。构造函数带 `@JvmOverloads`，两参数的写法与旧二进制保持可用。

| 分组 | 成员 | 标准 |
|---|---|---|
| 状态 | `val palette: LumenPalette`、`val isPrepared: Boolean`、`val engine: GlowEngine?`（每次现取，**不得缓存**） | §2.2、§6 |
| | `val effectTuning: LumenEffectTuning`（自 1.1.0；首次读取后缓存） | §2.5 |
| | `val controlShadow: LumenControlShadowOptions`（自 1.5.0；首次读取后缓存，改动重建 Activity 生效） | §5.6 |
| 会话 | `fun prepare()`、`fun bindRoot(root: View, onFailure: (() -> Unit)? = null): Boolean` | §2.3、§2.4 |
| 通知 | `fun onDispatchTouchEvent(event: MotionEvent)`、`fun notifyPositionChanged()`、`fun notifyScrollPositionChanged(scrollHost: View)`、`fun bindContentSource(view: View)` | §4、§6.1 |
| 表面 | `fun surface(color, radiusDp, role: SurfaceRole): Drawable`（控件阴影开启时外包软阴影层） | §5、§5.6 |
| | `cardBackground` / `floatingBackground` / `topBarBackground` / `selectionBackground` / `modalBackground`（`color = palette.surface`，`radiusDp` 有默认值） | §5 |
| | `motionSurfaceBackground(color, radiusDp)`、`liquidMotionSurfaceBackgroundOrNull(color, radiusDp): Drawable?` | §5.2 |
| | `chromeOverlayBackground(color, radiusDp, selected = false)`、`neutralWindowBackground()` | §5、§5.5 |
| 控件 | `styleActionButton(view: TextView, filled: Boolean, radiusDp = 20f)`、`styleSelectionControl(view, radiusDp, selected)`、`styleStatusChip(view: TextView, accent, radiusDp = 9f)`、`controlOutline(radiusDp, emphasized = false): Drawable` | §5.3 |
| 回弹 | `installStretch(scrollTarget: View, isStretchAllowed: () -> Boolean = { true }): View?`、`finishStretch(view: View?)` | §5.4 |
| 诊断 | `val isLiquidRequested`、`val isLiquidEffective`、`val backendName: String?`、`fun diagnostics(): SkinSessionDiagnostics?` | §3.2 |
| 生命周期 | `onStart()`、`onStop()`、`onTrimMemory(level)`、`onLowMemory()`、`onDestroy()` | §2.1 |

### 控件软阴影（自 1.5.0）

浅色主题下浅色表面与白底内容分不清边时启用；色罩与白色描边在浅底上会消失，阴影不依赖它们。环境层（1.6× 模糊半径的宽幅高斯尾）与贴边接触带按到控件轮廓的距离场烘焙成连续剖面，**只画在控件边界之外**，控件边缘保持自己的干净轮廓；纯软件位图缓存，API 27+ 可用，与高级材质无关。祖先容器需 `clipChildren=false` 或按 `outsetPx` 预留外侧间距（密集列表项天然会被裁掉，通常正是想要的效果）。

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenControlShadowOptions(enabled, blurRadiusDp, offsetYDp, spreadDp, opacity, contactOpacity, color, adaptToTheme, softenOverlap, overlapAttenuation)`；`LumenControlShadowOptions.DISABLED` | 配置构造时校验范围；`adaptToTheme` 使深色主题自动加强环境影、减弱接触带；`softenOverlap` 在相邻同级控件落在外扩阴影内时降低本控件强度，`overlapAttenuation`（0.1–1，默认 0.55）为保留比例 | §5.6 |
| `LumenControlShadow.wrap(content, cornerRadiusDp, density, options, dark): Drawable` | 给任意背景包一层阴影；`enabled = false` 原样返回内容 | §5.6 |
| `LumenControlShadow.shadowOnly(cornerRadiusDp, density, options, dark): Drawable` | 只要阴影层，叠在宿主自有背景之下 | §5.6 |
| `LumenControlShadow.outsetPx(options, density): Int` | 阴影在控件边界外的最大外扩像素 | §5.6 |
| `NavigationBarSelectionMask.color(palette, strength): Int` | 底栏选中滑块的对比遮罩：浅色用深色、深色用浅色，`strength` 0–1 连续；结果交给 `chromeOverlayBackground(..., selected = true)` | §12.4 |

### 液态形变（自 1.5.0）

`LumenMorphTile` 是 1 格 ↔ 2 格的独立磁贴：外层固定 2 格，内部用连续曲率路径 + 三条独立弹簧（宽度/圆角/内容）在收起/展开之间形变。它不叠加控件软阴影或液态折射表面（动态半径会让预烘焙阴影纹理每帧失效），只提供跟随 `LumenEffectTuning` 的贴边高光与接触线。详细耦合见适配标准 §13.12。

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenMorphOptions(enabled, collapsedFraction, collapsedRadiusDp, expandedRadiusDp, cornerSmoothing, contentScale, fadeLabel, edgeHighlight, contactLine, contactAlpha, widthSpring, cornerSpring, contentSpring)`；`LumenMorphOptions.DEFAULT` | 构造时校验；默认三条弹簧为 0.35/0.17、0.35/0.16、0.2/0 | §13.12 |
| `LumenMorphTile(context)` | `configure(palette, tuning, options)`、`setContent(icon, label)`、`setColors(fill, content)`、`setExpanded(expanded, animate = true)`、`toggle()`、`setProgress(0..1)`、`isExpanded` | §13.12 |
| `LumenMorphPolicy` | 纯函数：宽度/圆角插值、内容 alpha/offset；控件与 JVM 测试共用 | §13.12 |

### `abstract class LumenActivity : android.app.Activity`

- `val lumen: LumenActivityDelegate`：懒创建。
- `protected open fun resolvePalette(): LumenPalette`：默认返回 `LumenPalette.neutral(系统深浅色)`。
- `protected open fun resolveEffectTuning(): LumenEffectTuning`：默认 `LumenEffectTuning.DEFAULT`（自 1.1.0）。
- `protected open fun resolveControlShadow(): LumenControlShadowOptions`：默认 `LumenControlShadowOptions.DISABLED`（自 1.5.0）。
- 已接好 §2.1 的全部转发。

## 3. 数据类型（`com.lumen.coacervation.engine.model`）

| 类型 | 说明 |
|---|---|
| `LumenPalette(primary, onPrimary, secondary, tertiary, surface, background, surfaceVariant, textPrimary, textSecondary)` | 宿主配色（ARGB Int） |
| `LumenPalette.modern(primary, onPrimary, secondary, tertiary, dark): LumenPalette` | 宿主给强调色，表面与文字用引擎推荐的中性色 |
| `LumenPalette.neutral(dark): LumenPalette` | 引擎自带的中性配色 |
| `LumenEffectTuning(edgeHighlightWidth = 1f, edgeHighlightIntensity = 1f, dragGlowIntensity = 1f, dragGlowRadius = 1f, dragDeformation = 1f)` | 视效调参倍率（自 1.1.0，§2.5）；超范围构造抛 `IllegalArgumentException` |
| `LumenEffectTuning.DEFAULT`、`LumenEffectTuning.clamped(...)` | 引擎原样；把任意输入收进范围（非有限值按 1） |
| `LumenEffectTuning.MIN_EDGE_WIDTH` / `MAX_EDGE_WIDTH` / `MAX_EDGE_INTENSITY` / `MAX_DRAG_GLOW_INTENSITY` / `MIN_DRAG_GLOW_RADIUS` / `MAX_DRAG_GLOW_RADIUS` / `MAX_DRAG_DEFORMATION` | 范围常量：0.25 / 4 / 3 / 4 / 0.5 / 2 / 2 |
| `enum SkinId { MATERIAL_YOU("material_you"), LIQUID("liquid_v1") }` | `storageValue` 属于持久化协议，永不复用 |
| `SkinId.fromStorageValue(value: String?): SkinId?` | 严格解析 |
| `enum SurfaceRole { WINDOW, CARD, MODAL, TOP_BAR, CHIP, FILLED_BUTTON, TEXT_BUTTON, SELECTED_ITEM, FLOATING, MOTION_SURFACE }` | 表面语义 |
| `runtime.SkinSessionDiagnostics(requestedSkin, effectiveSkin, fallbackReason, liquidBackendName, liquidBackendDegradeReason)` | 只读诊断 |

## 4. 悬浮栏（`com.lumen.coacervation.engine.glow`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `class GlowBackdropTarget(context) : FrameLayout` | 悬浮栏下方的内容容器；`var contentCaptureEnabled` 由 `GlowFloatingChrome` 管理 | §6.0 |
| `class GlowFloatingChrome(target, engine: () -> GlowEngine?, coverage: (GlowScrollEdge) -> Float)` | 可读性协调者 | §6 |
| `GlowFloatingChrome.attach(host, edge: GlowScrollEdge?, foregroundColor, companions = emptyList(), thickenHost = true, onForeground: (Float) -> Unit)` | 登记一条栏；`edge = null` 表示不贴滚动边缘（侧边栏、悬浮按钮） | §6、§15.6 |
| `GlowFloatingChrome.onContentMoved()`、`dispose()` | 非滚动的位移通知；释放 | §6 |
| `enum GlowScrollEdge { TOP, BOTTOM }` | | |
| `GlowScrollEdgePolicy.topCoverage(scrollY, fadeLengthPx)`、`bottomCoverage(scrollY, scrollRange, fadeLengthPx)`、`pagerCoverage(position, pageCount, coverageOf)` | 溶解覆盖度 | §6 |
| `GlowLegibilityPolicy.foreground(color, boost, push = FOREGROUND_PUSH): Int` | 前景色加强 | §6 |
| `GlowLegibility(boost, edgeDefinition)`、`GlowLegibility.NEUTRAL`、`GlowSurfaceOptics`、`GlowContentSample` | `GlowEngine` 契约中的值类型 | — |

## 5. 材质契约（SPI）

`interface GlowEngine : AutoCloseable` 与 `class GlowEngineCallbacks(onFirstVisibleDraw, onFatalFailure)`：成员定义见源码 KDoc。

- **宿主调用方**只通过 `LumenActivityDelegate.engine` 取得实例，并传给 `GlowFloatingChrome`，**不应**直接调用其成员。
- **实现方**必须遵守 `ENGINEERING_RULES.md`。1.0 还没有注册入口（`ARCHITECTURE.md` §10）。

## 6. 宿主可实现的接口

| 接口 | 用途 | 标准 |
|---|---|---|
| `liquid.LiquidStaticBackdropHost` | 由 Activity 实现：玻璃背后只有背景，从不发起实时截图 | §5.1 |
| `liquid.LiquidMotionSurfaceFrameProvider` | 由形变 View 实现：`copyLiquidMotionBounds(outBounds)`、`liquidMotionCornerRadiusPx()`、`liquidMotionFallbackColor()` | §5.2 |
| `liquid.LiquidStretchGestureObserver` | 由滚动容器实现：`observeTouch(event)`，补发被回弹视口接管的手势 | §5.4 |
| `interaction.ElasticGestureClaim` | 由容器实现：`val claimsCurrentGesture`；宿主调用 `ElasticGestureClaim.claimedAbove(view)` | §5.4 |

`liquid.LiquidStretchEdge { NONE, TOP, BOTTOM }` 是 `GlowEngine.onStretchDistance` 的参数类型。

`widget.LumenMaxHeightScrollView(context,maxHeightPx)` 保持按内容收敛且不超过上限的测量，
不强制填满空内容。它基于 Core `NestedScrollView`，可把拖动与惯性类型交给引擎。
`motion.modal.LumenPanelScroll.prepare(root,installStretch)` 在公共面板入口一次性安装视口；
处理 `NestedScrollingChild3` 与平台竖向 `ScrollView`，继续识别它们内部的内联滚动区域；
已有视口保持幂等，失败保留原滚动控件、嵌套滚动开关和原生 overscroll。
标准 `LumenModalPresenter` 已接入；自定义宿主 presenter 在准备正文后调用一次即可。

`GlowEngine.onStretchDistance(distance, edge, scrollHost)` 保留原两参数回调的默认转发。
引擎安装回弹时传入实际滚动 View，渲染器按 `scrollHost.rootView` 刷新该窗口；弹窗回弹
不触发主窗口的截图抑制或背后表面刷新。适配渲染器可使用主线程 API
`LiquidStretchWindowRegistry.update(window,distance,intensity,direction)`、`stateFor(host)`、`clear()`，
复用按弱窗口键保存的强度、方向和距离量化；返回的 `LiquidStretchWindowState` 只读，未变化时
`update` 返回 null。不为每帧创建状态对象。

同一窗口有多个回弹视口时，三参数回调必须改用
`updateForScrollHost(scrollHost,distance,intensity,direction): LiquidStretchWindowUpdate?`。
它按弱滚动 View 保存独立贡献，聚合最大距离和光学强度，并对边缘方向保留迟滞；
某个视口归零不清除其他活动视口。只有聚合值变化才返回对象，该对象按窗口复用。
`LiquidStretchWindowUpdate.window: View?` 是真实所属窗口的弱引用，`state` 是该窗口只读状态；
刷新窗口和判断是否仍需抑制截图均使用返回的窗口及 `state.distance`。
滚动 View 摘除后的通知仍回到其原窗口，并强制按零值清理；迟到的非零距离不会恢复旧状态。
宿主在滚动目标结束或摘除时仍应发布终态，`clear()` 一并释放滚动归属和窗口状态。

## 7. 控件（`com.lumen.coacervation.engine.liquid`）

- `class LiquidChoiceDrawable(width, height, density, surface, accent, onAccent, outline, checkbox = false, thumb = false) : Drawable`
  - 开关的轨道和滑块、复选框。
  - 纯渐变，不采样背景，有状态（stateful）。
  - 替换后，宿主必须调用 `refreshDrawableState()`（§5.3）。

## 8. 触摸光晕（`com.lumen.coacervation.engine.touch`）

| 声明 | 说明 |
|---|---|
| `class GlowState` | 每个表面一份，复用；`val shape: GlowShape`、`fun update(frame, dtSeconds, radiusPx, baseAlpha, config)`、`fun reset()`（只在不可见边界调用） |
| `class GlowFrame` | 每帧输入：`press`、`offsetX/Y`、`velocityX/Y`、`centerX/Y`、`boundsWidth/Height`、`cornerRadius`、`pileRoomPx` |
| `fun reachablePileRoomPx(viewScreenLeft, viewScreenTop, viewScreenRight, viewScreenBottom, screenWidth, screenHeight, touchLocalX, touchLocalY, boundsWidth, boundsHeight): Float` | 计算 `GlowFrame.pileRoomPx` |
| `class GlowShape` | 输出：中心、半轴、旋转、亮核前移、`alphaByte`、`visible` |
| `class GlowConfig`、`GlowConfig.create(density, maxTravelPx, travelEpsPx, velocityRefPxPerSec, edgeBandPx, …)` | 每个表面生成一次 |
| `class TouchGlowRenderer(color, radiusPx, …)` | `draw(canvas, shape)`；`recolor(color)` 只在配置期调用 |

约束见 §7。

## 9. 自定义背景（`com.lumen.coacervation.engine.background`）

| 声明 | 说明 |
|---|---|
| `object LiquidBackgroundStore` | `read(context): LiquidBackgroundReadResult`（主线程可用）、`importFromUri(context, uri): LiquidBackgroundImportResult`（后台线程）、`restoreAutomatic(context): Boolean`（应当在后台线程） |
| `LiquidBackgroundStore.decodePreview(context, config, viewWidth, viewHeight, palette): Bitmap?`（自 1.1.0） | 后台线程 API：按控件像素解码自定义背景，超过 2 MiB 等比缩小；自动模式、无效尺寸或解码失败返回 null。位图所有权交给宿主，接入见 §9.1 |
| `LiquidBackgroundReadResult(config, issue, assetPresent)` | |
| `LiquidBackgroundConfig(mode, assetId, assetSha256, normalizedWidth, normalizedHeight, displayName)`、`LiquidBackgroundConfig.AUTOMATIC` | |
| `enum LiquidBackgroundMode { AUTOMATIC, CUSTOM }` | |
| `enum LiquidBackgroundConfigIssue { NONE, MISSING_OR_INVALID_SCHEMA, UNKNOWN_MODE, INVALID_CUSTOM_ASSET, TYPE_MISMATCH }` | |
| `sealed interface LiquidBackgroundImportResult { Success(config), Failure(reason) }` | |
| `enum LiquidBackgroundImportFailure { READ_FAILED, FILE_TOO_LARGE, UNSUPPORTED_IMAGE, DIMENSIONS_TOO_LARGE, ENCODE_FAILED, STORAGE_FAILED }` | |

## 10. 可选模块 `lumen-controls`

`com.lumen.coacervation.engine.controls.LumenControls.style(root: View, lumen: LumenActivityDelegate)`：

- 把 `SwitchCompat`、`CheckBox`、`EditText` 换装成引擎材质；
- 未 `prepare()` 时是空操作（§5.3）。

`LumenControls.stopScroll(host: ViewGroup): Boolean`：主线程停止 `RecyclerView` 当前惯性及平滑滚动，已处理时返回 `true`，其它类型返回 `false`。通过 `LumenReveal.setHorizontalScrollMotionStopper(LumenControls::stopScroll)` 接入横向轮播定位（§14.6）。

## 11. 交互与动效（`lumen-motion`）

### 11.1 长按弹性（`com.lumen.coacervation.engine.interaction`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenElasticInteraction(activity, lumen, isExcluded = { tag == EXCLUDED_TAG }, effectTuning = { lumen.effectTuning }, springSpec = LumenSpringSpec.DEFAULT, rubberCurve = ElasticRubberCurve.TANH)` | `dispatch(event, superDispatch)`、`clear()`、`installDialog(dialog): () -> Unit`、`dispose()`；`effectTuning` 自 1.1.0，每次按下时读取；`springSpec`/`rubberCurve` 自 1.5.0 | §12、§2.5 |
| `ElasticInteractionController.EXCLUDED_TAG` / `CONTAINER_TAG` | 不参与弹性 / 只承载、自己不形变 | §12.1 |
| `ElasticInteractionController(root, notifyPositionChanged, isExcluded, highlightColor, effectTuning = { LumenEffectTuning.DEFAULT }, springSpec = LumenSpringSpec.DEFAULT, rubberCurve = ElasticRubberCurve.TANH)` | 底层控制器（一个窗口一个）；宿主通常用上面的封装。`effectTuning` 自 1.1.0；`springSpec`/`rubberCurve` 自 1.5.0 | §12 |
| `ElasticTravelPolicy { AVOID_NEIGHBORS, PARENT_BOUNDS }`；`LumenElasticInteraction.travelPolicy` / `ElasticInteractionController.travelPolicy`（自 1.1.0） | 默认避让相邻控件；父容器模式保留原列表交叠行程。下次按下时生效，已开始的拖动/回弹不改变；Activity 封装同时传给其弹窗 | §14.7 |
| `LumenSpringSpec(stiffness, dampingRatio)`、`LumenSpringSpec.DEFAULT`、`LumenSpringSpec.ofResponseBounce(responseSeconds, bounce)`（自 1.5.0） | 长按回弹的弹簧规格；`ofResponseBounce` 用"周期+弹跳"设计口语换算（`stiffness=(2π/response)²`、`dampingRatio=1−bounce`，与厂商弹簧库同语义）。范围校验；恒欠阻尼 | §12 |
| `ElasticRubberCurve { TANH, RATIONAL }`（自 1.5.0） | 跟手位移的橡皮筋曲线族：默认 TANH 为引擎原样；RATIONAL 初段更跟手、渐近上限更韧。两者都以同一行程上限渐近、在 touchSlop 处连续归零 | §12 |

### 11.2 可打断动画内核（`com.lumen.coacervation.engine.motion`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `InterruptibleMotionSession` | `invalidate(): Long`、`owns(token)`、`reset(value, now)`、`sample(value, now)`、`velocity(now)` | §13.0 |
| `InterruptibleMotionContinuation(start, target, velocity, durationMs)` | `value(fraction)`：按当前速度续接，切线有界 | §13.0 |
| `InterruptibleMotionPolicy` / `InterruptibleMotionPhase` | `canNavigate`、`preserveFrame`、`remainingDuration`；阶段枚举 | §13.0 |
| `MotionRect(left, top, right, bottom)` | 与 Android 无关的矩形（屏幕或窗口坐标） | §13 |
| `MorphCornerPolicy.collapsedRadius(declared, width, height)` | 形变起点圆角：来源声明的圆角（≤ 短边一半），没有声明取短边一半 | §14.2 |
| `MorphCornerMode { DECLARED, CAPSULE }`；`MorphCornerPolicy.collapsedRadius(declared, width, height, mode)`；`LumenModalPresenter.anchorCornerMode`（自 1.1.0） | 默认取来源声明圆角；CAPSULE 保留短边一半的起点。呈现器每次 present 固定规则，不改变已显示面板；原三参数方法和构造签名保留 | §14.7 |
| `LumenEasing` | `emphasizedDecelerate()`、`emphasizedAccelerate()`、`standard()`、`secondaryExpand()`、`secondaryCollapse()` | §13.9 |
| `MicroMotion` | `swapText(view, text, restAlpha = 1f)`、`showBadge(v, growFromEnd = false)`、`hideBadge(v)`、`setVisible(parent, child, visible, animate = true)`、`revealHint(root, hint, show, announcement = null)` | §13.9 |

### 11.3 弹窗（`com.lumen.coacervation.engine.motion.modal`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenModalPresenter(activity, lumen, style = LumenModalStyle(), elastic = null, styleContent = {})` | 一个 Activity 一个 | §13.1 |
| `createContainer(): LinearLayout` | 弹窗内容容器；第一个子 View 应当是标题 | §13.1 |
| `present(dialog, container, preferredWidth = null, anchor = null, anchorStyle = CONTAINER, onExpanded = {}, onBackDismiss = {}, anchorBounds = null, coverBounds = null, anchorCornerRadiusPx = NaN, titleView = null)` | 呈现；`anchorCornerRadiusPx` 与 `anchorBounds` 配套；`titleView` 为标题迁移的目标端（默认第一个子 View） | §13.1–13.4、§14.2、§15.4 |
| `dismiss(dialog, container, onDismissed = {})` | 关闭（锚点弹窗自动走收起形变） | §13.1 |
| `ModalSubPanelOrigin`：`anchorBounds`、`anchorCornerRadiusPx`、`coverBounds` | 点击那一刻抓取的来源矩形、圆角与父面板表面 | §13.4、§14.2 |
| `captureSubPanel(parentDialog, parentContainer, source): ModalSubPanelOrigin`、`presentSubPanel(origin, dialog, container, onExpanded = {}, onBackDismiss = {}, titleView = null)`、`dismissParent(origin)` | 覆盖式子面板 | §13.4 |
| `anchorBounds(view): MotionRect?`、`surfaceBounds(dialog, container): MotionRect?` | 来源矩形 / 弹窗可见表面 | §13.4 |
| `activeDialog: Dialog?`、`onDestroy()` | 当前弹窗；销毁时硬关 | §13.1 |
| `ModalAnchorStyle { CONTAINER, BUBBLE }` | 形变 / 锚定气泡 | §13.2 |
| `LumenModalStyle(cornerRadiusDp = 28f, …, maxWidthDp = 560, backdropBlur = false)` | 外观参数，默认值即来源工程取值；`maxWidthDp` 为未给确定宽度时的卡片最大宽度（≤ 0 不限） | §13.1、§15.4 |

### 11.4 条目形变成全屏页（`com.lumen.coacervation.engine.motion.morph`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `ContainerMorphLauncher.launch(source, destination, entry, title, configure = {}, start = source::startActivity)`、`clear(destination, entry)` | 来源页 | §13.5 |
| `ContainerMorphHost(context, collapsedSurfaceColor, expandedSurfaceColor, titleColor, sourceTitle, surfaceHandoffExpansion = 0.1f, collapsedStrokeColor = TRANSPARENT, collapsedStrokeWidthPx = 0f)` | 目标页内容根：`installContentInsets()`、`setMotionSurfaceBackground(d)`、`replacePage(page, toolbarTitle)`、`liquidBackdropRoot()`、`registerNavigationBack(view)`、`val expansion`；自 1.5.2 新增 `var followScreenCorners = false`，启用后按 API 31+ 当前窗口的系统四角插值和裁剪；缺少数据或不覆盖该角时保持原几何 | §13.5 |
| `host.LumenBackdropReadiness`（自 1.5.2） | `prepareForMotion()`（默认空实现）、`val isReadyForMotion: Boolean`，主线程调用；自定义背景在准备态完成首帧皮肤交接后为 true，形变控制器有界等待冷背景；普通背景不增加等待 | §13.5 |
| `host.LumenPreparedBackdropCache<K, V>(maxBytes = 4 MiB)`（自 1.5.2） | 宿主自有 renderer 的发布像素缓存：`get(key)`、`put(key, value, byteCount)`、`clear()`、`retainedBytes`、`close()`；每实例一个条目，默认 4 MiB；宿主可按已有图片配方显式设置 1 byte～34 MiB，超范围构造失败，超过实例预算的条目拒绝。键必须是不可变配置，值只含已发布像素与独立绘制状态，不得持有 View/Context/Activity；每种 renderer 一个实例，额外宿主预算独立计入 | §13.5 |
| `ContainerMorphController(activity, host, lumen, destination, launchOrigin, allowLaunchOriginForExit, destinationTitle, collapsedCornerRadiusDp = 12f, isBusinessBlocked = { false }, onMotionStarted = {}, onExpanded = {})` | `start(isFreshLaunch)`、`beginPredictiveBack()`、`progressPredictiveBack(p)`、`cancelPredictiveBack()`、`commitBack()`、`isSettledExpanded`、`isExpanded`、`onDestroy()`；`suppressSystemTransitions(activity)` | §13.5 |
| `ContainerMorphOrigin.from(intent): ContainerMorphOrigin?` | 目标页读取启动几何；`entryCornerRadiusPx` 是入口卡片声明的圆角（NaN 表示没有） | §13.5、§14.2 |
| `ContainerMorphEntrySpec` | 来源工程诊断入口的视觉参数（圆角、描边、表面色） | §13.5 |

### 11.5 翻页、手风琴、定位（`motion.pager` / `motion.expansion` / `motion.reveal`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenPagePager(context)` | 页数不限；`selectPage(index, animate = true)`、`selectedPage`、`pagePosition`、`isSettled`、`motionAnchorY`、`switchParts`；回调 `onPageSelected` / `onMotionStarted` / `onUserInteraction` / `onPositionChanged`；`LumenPagePager.frameworkSwitchParts(view)` | §13.6 |
| `PageTextChain(pager, headings)` | `onPositionChanged()`、`dispose()` | §13.6 |
| `LumenPageScrollView(context, onUserScroll, onContentTouch)` | 页内滚动容器（`NestedScrollView`，实现 `LiquidStretchGestureObserver`）；可空属性 `onScrollPositionChanged: ((View) -> Unit)?` 在实际 scrollX/Y 改变后同步回调，构造签名保持不变；销毁时设为 null | §13.6 |
| `SectionExpansionController(card, content, chevron, density, cornerRadiusDp = 12f, notifyPositionChanged)` | `setExpanded(target, animate = true)`、`expanded`、`cancel()` | §13.7 |
| `LumenReveal(accentColor, highlightDelayMs = 240, highlightDurationMs = 560)` | `reveal(scrollView, target, settling = [], topOffsetPx = 0, highlight = true, afterReveal = null)`（按两轴实际位置验收后交付回调）、`setHorizontalScrollMotionStopper(stopper: ((ViewGroup) -> Boolean)?)`（横向第三方容器停止适配；null 恢复默认）、`highlight(target)`、`cancel()`、`isActive` | §13.8、§14.6 |

### 11.6 控件（`com.lumen.coacervation.engine.widget`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenNavigationBar(context, titles, icons, colors, backgroundFactory, onSelect, onUserInteraction, onVisualMovement)` | 胶囊底栏（1～6 项，超出报错）：`setPageProgress(v, notifyPositionChanged = true)`、`setSelectedPage(i)`、`setLegibility(boost, haloColor)`、`dispose()` | §12.4 |
| `NavigationBarColors(text, selectedText, highlight)`、`NavigationBarSurface { BAR, SELECTION }` | 底栏/档位条配色与表面 | §12.4 |
| `LumenSlidingSelection(context, indicatorBackground, orientation = VERTICAL, notifyPositionChanged = {})` | 选中框连贯滑动的单选组（自 1.1.0）：`addOption(view, params)`、`select(index, animate = true)`、`selectedIndex`、`onSelect`、`setOnHighlightListener { index, weight -> }`（`fun interface OnHighlightListener`，原始类型参数、逐帧不装箱）、`indicator`、`options` | §13.11 |
| `LumenSegmentScrubBar(context, attrs)` | 分段档位条（1～8 段，超出报错）：`configure(labels, colors, thumbBackground, trackBackground, selectedIndex, onSelect)` | §12.4、§15.1 |
| `LumenSpring(start, target, velocity = 0f)` | 解析阻尼弹簧：`value(seconds)`、`velocity(seconds)`；重定向保留位置与速度 | §13.0 |
| `CoverableRippleDrawable(baseColor, content, mask)`、`CoverableRippleDrawable.rounded(palette, cornerRadiusPx)`、`coverOpacity` | 自绘圆角涟漪；可被上层表面"盖住"而淡出 | §12.2、§13.2 |
| `BubbleDrawable(bubbleColor, arrowWidthPx, arrowHeightPx, cornerRadiusPx, arrowOffsetPx, strokeColor = 0, strokeWidthPx = 0f)` | 带箭头的气泡背景（矢量缩放） | — |

## 12. `lumen-controls` 补充

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenControls.switchParts(view): Pair<Drawable?, Drawable?>?` | `SwitchCompat` 的（轨道, 滑块），接到 `LumenPagePager.switchParts` | §13.6 |
| `LumenReorderCallback(canDrag, onMove, onDrop, directions = null)` | 长按拖拽排序（`ItemTouchHelper.Callback`）；`isDragging`；方向默认按布局自动判断；`onDrop` 在全部交叠拖拽回位清理后异步交付，每次完成通知一次，参数是清理时的位置或 `-1` | §12.5、§14.5 |
| `LumenReorderCallback.directionsFor(layoutManager)`、`liftScale(longSidePx, density)`、`dropSettleDurationMs(px)` | 自动方向、按尺寸封顶的拾起放大、按距离的落位时长 | §14.5 |


## 13. 局部视效会话（host，自1.1.0）

规范与完整参数范围见 [SURFACE_SESSIONS.md](SURFACE_SESSIONS.md)。入口均为主线程。

- LumenSurfaceSession(context, palette, options)：不接管根/窗口/输入、不读写偏好的AutoCloseable会话。
- bind(view, surface, source: View?) / bindSource(view, surface, source: LumenContentSource?)：可逆背景绑定。
- setListener / updatePalette / updateOptions / notifyContentChanged / notifyPositionChanged / `notifyScrollPositionChanged(scrollHost: View)`。
- pause / resume / releaseGraphics / diagnostics / close；关闭后入口无副作用。
- LumenSurfaceBinding：id / update / diagnostics / close；旧绑定不能关闭新绑定。
- LumenContentSource：coordinateView / excludesSurfaces / drawContent(Canvas)。Session 的 GPU 与软件录制均裁剪到 `coordinateView` 的局部 `[0,width) × [0,height)`；需要额外采样余量的自定义来源应声明足够大的视口，不能依赖画出该范围的内容。
- LumenSurfaceOptions / LumenSurfaceSampling / LumenSurfaceSessionOptions：不可变配置，所有字段受范围约束。
- `LumenSurfaceSampling.fadeCurve`：`LumenSurfaceFadeCurve.SMOOTH`（默认，保持原平滑曲线）或 `LINEAR`（hold 到 end 之间等速衰减）。方向、端点及透明尾部规则相同；GPU、软件采样和静态承托使用同一曲线。不改变未显式选择 LINEAR 的表面。
- LumenSurfaceMaterial / LumenSurfaceBackend / LumenSurfaceFadeDirection / LumenSurfaceFailure：公开枚举。
- LumenSurfaceState / LumenSurfaceDiagnostics：请求/实际材质、每绑定状态及会话计数。
- LumenSurfaceListener.onSurfaceState(id, backend, failure, firstVisibleDraw)：异步合并、异常隔离。
- LumenSurfacePresets.floating / fadingBand / staticPanel：可继续copy调节的配置。

## 14. 局部增强（自1.2.0）

完整默认值、有效范围、优先级与平台回退见 [VISUAL_EFFECTS.md](VISUAL_EFFECTS.md)。以下配置为不可变data class，构造/copy均校验范围；值类型与JSON编解码不依赖渲染线程，其余渲染/交互入口为主线程。

| 声明 | 公开成员 |
|---|---|
| `host.LumenSurfaceSession` | 新重载`bind(view,surface,source: View?,enhancements)` / `bindSource(view,surface,source: LumenContentSource?,enhancements)`；`performanceDiagnostics(): LumenSurfacePerformance` |
| `LumenSurfaceBinding` | `isBound`、`updateEnhancements`、`samplingRegions`、`setPressPixels(x,y,pressure)`、`setShapesPixels(ax,ay,aw,ah,bx,by,bw,bh,secondShape)`、`clearCustomShapes()`、`setLightDirection(x,y,z)`、`setFrameTimeNanos(now,manual)`、`emitRipplePixels(x,y)`、`clearTransientEffects()`、`setQualityPressure(pressure)` |
| `LumenSurfaceEnhancements` | geometry/progressiveBlur/press/light/material/quality/debug；`DEFAULT` |
| `LumenSurfaceCorners` | topLeft/topRight/bottomRight/bottomLeft；`mirrored()` |
| `LumenSurfaceGeometryOptions` | cornersEnabled/corners/mirrorCornersInRtl/fusionEnabled/fusionRadiusDp/antiAliasWidthDp/shadowEnabled/shadowRadiusDp/shadowOpacity |
| `LumenProgressiveBlurOptions` | enabled/weakRadiusDp/strongRadiusDp/weakStart/weakEnd/strongStart/strongEnd/direction/strength/maxBandHeightDp |
| `LumenLocalPressOptions` | enabled/displacementDp/radiusFraction/highlightStrength/cancelOutside/releaseDurationMs/rippleEnabled/rippleAmplitudeDp/rippleSpeedDpPerSecond/rippleWidthDp/rippleLifetimeMs/maxRipples |
| `LumenSurfaceLightOptions` | enabled/angleDegrees/altitude/intensity/specularStrength/specularPower/edgeWidthDp/transformNormals/gestureInfluence/smoothingTimeMs |
| `LumenMaterialRecipeOptions` | intent/normalizeBySize/maxEdgeFraction/maxRefractionFraction/contrastFloor/reduceTransparency/reduceMotion/chromaticStrength/saturation/useIntentDefaults |
| `LumenSurfaceQualityOptions` | enabled/mode/adaptive/lowerThreshold/upperThreshold/hysteresis/minimumDwellMs/maxExecutionPixels/maxBitmapPixels/bitmapIntervalMs |
| `LumenSurfaceDebugOptions` | countersEnabled/timingEnabled/drawSamplingBounds/boundsLineWidthDp/boundsOpacity/samplingRange |
| `LumenMaterialIntent` | UNCHANGED / READING / CLEAR / OPAQUE_ACCESSIBLE / DECORATIVE |
| `LumenDetailMode`、`LumenSamplingRangeMode` | LOW / BALANCED / HIGH；SAFE_INTERIOR / PADDED_EXPERIMENTAL |
| `LumenVersionedContentSource : LumenContentSource` | sourceEpoch/contentVersion/availability/dependencies；getter为纯主线程快照，依赖默认空 |
| `LumenSourceAvailability` | READY / TEMPORARILY_UNAVAILABLE / PROHIBITED / INDEPENDENT_SURFACE |
| `LumenSurfacePerformance` | contentRecordings/proxyRecordings/effectChainBuilds/runtimeShaderBuilds/softwareRequests/softwareCompletions/staleCompletions/contentRecordingNanos/softwareProcessingNanos/activeSoftwareBytes/logicalGpuContentPixels/logicalGpuEffectPixels/timingEnabled |
| `LumenSurfaceSamplingRegions` | shapeBounds/requiredSampleBounds/recordedBounds/materializedBounds/outputClip/sourceId/sourceEpoch/contentVersion/capturedVersion/estimatedSourceAgeNanos/availability |
| `LumenSurfaceRegion`、`LumenRegionSpace` | left/top/right/bottom/space；SOURCE_LOCAL / TARGET_LOCAL / EXECUTION_PIXELS |
| `LumenEffectPreset(surface,enhancements,seed)` | `toJson(): String`；companion `fromJson(text): LumenEffectPreset`，schema1，最大16384字符，无图片或路径 |
| `motion.LumenFrameClock` | `fun nowNanos(): Long`，非负单调时钟；固定回放可显式seek |
| `motion.LumenFixedFrameClock` | initialNanos；`nowNanos()` / `seekNanos(nanos)` / `advanceMillis(millis)` |
| `interaction.LumenSurfaceInteraction(view,binding,press,light,clock)` | `update(press,light,reduceMotion)` / `observeTouch(event)` / `advanceFrame()` / `cancel()` / `pause()` / `resume()` / `close()`；不消费事件、不替换宿主监听 |
| `widget.LumenSlidingSelection` | `setOnGeometryListener` / `getOnGeometryListener`；`OnGeometryListener.onGeometry(left,top,right,bottom,targetLeft,targetTop,targetRight,targetBottom,moving)`，原始类型参数 |
| `widget.LumenFusedSelection(selection,palette,surface,enhancements,sharedNodeBaseline=false)` | `update(surface,enhancements)` / `setSharedNodeBaseline(enabled)` / `updatePalette` / `diagnostics` / `pause` / `resume` / `close`；可逆装饰真实选择器 |

新类型的包名除明确标注motion/interaction/widget外均为`com.lumen.coacervation.engine.host`。


## 15. 可选传感器光源（自1.2.1，lumen-motion）

包`com.lumen.coacervation.engine.sensor`。参数全部字段、默认及范围见[P2配置](P2_EFFECTS_AND_ASSETS.md)。

- `LumenSensorLightOptions`及全部构造字段、`LumenSensorLightState`、`LumenSensorLightDiagnostics(state,sensorType,events,updates,rejected,registrations)`。
- `LumenLightVectorListener.onLight(x,y,z)`；`LumenGravityListener.onGravity(x,y,z,timestampNanos)`；`LumenGravityInput.sensorType/start(rateHz,allowAccelerometerFallback,listener)/stop()`。可注入输入须在主线程回调。
- `LumenSensorLightController(view,listener,options=LumenSensorLightOptions(),input=null)`：`start/stop/update/diagnostics/close`。回调只输出向量，宿主选择接入的binding。

## 16. 独立生成器及粒子（自1.2.1，lumen-effects）

包`com.lumen.coacervation.engine.effects`。`LumenEffectOptions`、`LumenProceduralOptions`、`LumenFilmOptions`、`LumenPaperOptions`、`LumenEnergyOptions`、`LumenParticleOptions`全部构造字段受兼容承诺；参数清单与范围见[P2配置](P2_EFFECTS_AND_ASSETS.md)。

- `LumenProceduralKind { FILM,PAPER,ENERGY }`、`LumenParticleShape { ORB,STREAK }`、`LumenEffectState`和`LumenEffectDiagnostics`全部字段。
- `LumenEffectLayer(view,options=LumenEffectOptions(),clock=system)`：`update(value)`、`emitBurst(x,y):Int`、`triggerEnergy()`、`pause/resume/clear/advanceFrame/diagnostics/close`。x/y为宿主View内像素，关闭后调用为空操作。
- `LumenEffectPreset(options=LumenEffectOptions())`：`toJson():String`、`fromJson(text):LumenEffectPreset`，schema固定为1。导出只含参数，不含任意执行代码。

## 17. 统一动画资产（自1.2.1，lumen-assets）

包`com.lumen.coacervation.engine.assets`。所有选项／预算见[P2配置](P2_EFFECTS_AND_ASSETS.md)。

- `LumenAssetOptions`全部构造字段；`LumenAssetSelection(artboard=null,animation=null,stateMachine=null)`；`LumenAssetFormat/Fit/State/Failure`枚举；`LumenAssetInput(name,kind)`、`LumenAssetInputKind { NUMBER,BOOLEAN,TRIGGER }`。
- `LumenAssetMetadata(format,width,height,durationMs,seekable,decodedImagePixels=0,nativeClock=false,speedControl=true,repeatControl=true,frameRateControl=true,inputs=emptyList())`；`LumenAssetDiagnostics(state,failure,generation,frames,dropped,inFlight,bytes,metadata)`。frames计量成功手动提交，无法统计供应商原生时钟帧。
- `LumenAssetException(failure)`供工厂明确拒绝原因；`LumenAssetSource.open():InputStream`、`LumenAssetFactory.format/decode(bytes,selection,options)`在会话后台执行，`attach(context,asset,options)`在主线程执行。
- `LumenDecodedAsset.metadata/close`：可在解析线程或主线程关闭，宿主实现须保证幂等、只释放自己的资源。`LumenAssetPlayer.view/metadata/update/render(progress):Boolean/setPlaying/close`及默认`setNumber/setBoolean/fire`在主线程执行；false表示不支持或未提交。
- `LumenAssetListener.onState(state,failure)`异步主线程通知；`LumenAssetPlayerFailureListener.onFailure(failure)`和默认`LumenAssetPlayer.setOnFailure(listener?)`供异步Surface回调失败上报，会话再次按代次过滤。
- `LumenAssetSession(container,options=LumenAssetOptions(),clock=system)`：`setListener`、`load(source,factory,selection=LumenAssetSelection())`、`play/pause/resume`、`seek(progress)`、`update(value)`、`setNumber(name,value):Boolean`、`setBoolean(name,value):Boolean`、`fire(name):Boolean`、`advanceFrame/diagnostics/close`。输入名称1–128字符，progress为0–1有限值。
- 独立工厂：`assets.lottie.LumenLottieFactory()`、`assets.pag.LumenPagFactory()`、`assets.rive.LumenRiveFactory(context,renderer=LumenRiveRenderer.CANVAS)`，各实现上述统一工厂。`assets.rive.LumenRiveRenderer { CANVAS,GPU }`为引擎自有枚举，默认SDK Canvas后端，GPU需显式选择。公开签名无播放库类型。

Rive原生时钟能力边界、PAGFile最终释放方式、供应商解析内存及中断边界属于[P2配置](P2_EFFECTS_AND_ASSETS.md)明确约束，不能把帧率或字节门禁解释为原生运行时的硬实时／内存保证。

## 18. 宿主 motion 原语（自1.2.3，lumen-motion）

既有高层 presenter/delegate 继续可用。下面的可选入口供已经拥有皮肤、窗口包装和业务页面的宿主接入；UI 类及动画入口在主线程调用，纯策略允许 JVM 调用。包名以 `com.lumen.coacervation.engine` 为根。

| 包 / 类型 | 接入边界 |
|---|---|
| `interaction.ElasticClipBoundary` | 自定义视口标记；弹性形变不提升该节点、不解除该节点及外层的裁剪。原生 ScrollView、HorizontalScrollView、ScrollingView 和 LiquidStretchViewport 自动识别。 |
| `interaction.ElasticInteractionController` | 每窗口一实例，`dispatch(event, original)` 只分发一次原流；`clear/dispose` 恢复自身借用的变换与裁剪。`travelPolicy` 默认 `AVOID_NEIGHBORS`，旧宿主可显式选 `PARENT_BOUNDS`。 |
| `interaction.ElasticMotionPolicy/ElasticMotionGroupPolicy` 与配套策略、spring、lease 类型 | 纯运动学和归属协议。宿主标签必须使用引擎 `EXCLUDED_TAG/CONTAINER_TAG`，不能继续写旧字符串。 |
| `motion.modal.IconAnchoredMotionLayer/Controller/Spec` | 保留同一个表面；动画 `applyFrame` 与展开 `clearShape` 都裁正文，落定路径随真实卡片布局更新。控制器的内容移动回调由宿主接到自己的采样位置通知。 |
| `motion.modal.BubblePanelLayer/BubbleMotionController`、`BubbleIconProxy`、`BubbleLayerMotionSpec`、`BubblePlacementSpec` 及几何类型 | 气泡放置、图标借用和退场由引擎统一持有。宿主使用同一个 `widget.CoverableRippleDrawable` 类型，覆盖退场才能识别真实按钮涟漪。 |
| `motion.modal.BubbleSkinSurfaceView/BubbleSurfaceDrawable` | 可直接承载宿主 Drawable，不要求创建第二个皮肤会话。 |
| `motion.modal.ModalCardRoot/ModalTitleMotion`、标题颜色与描述策略、`ModalBackdropBlur/Spec` | 子面板遮挡、原生标题交接与平台模糊；宿主保留标题文字、配色和功能开关。 |
| `motion.pager.LumenPagePager/PageTextChain` 与配套 motion/lifecycle/navigation 策略 | 页数由真实子页面决定；`LumenPagePager` 可继承，支持宿主提供 `switchParts`，继续识别 AppCompat 开关的 track/thumb。 |
| `motion.expansion.SectionExpansionController/ExpansionMotionPolicy/NestedExpansionPolicy` | 同一个进度驱动高度、兄弟位移和轮廓；位置通知必须接到宿主采样会话。 |
| `motion.reveal.RevealRequest/RevealScrollMotion` | 取消和滚动定位使用统一代次与速度续接；宿主保留导航目的地。 |
| `motion.morph.ContainerMorphHost/Geometry/Spec/ContentTiming/TitleMode` | 宿主提供内容、标题、颜色和 Drawable，host 的位置回调保持接线。 |
| `widget.BoundedNavigationMotion(maxItems)` | 几何委托给 `NavigationBarMotion`；非法点击 count 被拒绝，其余位置按宿主上限钳制。默认控件原有容量不变。 |
| `motion.LumenAnimator` | `ofFloat/ofInt/property` 返回原生 Android animator；不另建时钟、不接管宿主监听器和取消语义。 |
| `motion.CompactMotionPolicy` | `requireHeights/heightAt/rowAlphaAt` 让折叠高度与伴随行透明度共享进度；业务尺寸和时长由宿主提供。 |

`liquid.LiquidStretchViewport`、`LiquidStretchOverscrollPolicy`、`LiquidStretchEdge`、`LiquidStretchUnconsumedAction`、`LiquidStretchGestureObserver` 位于 **lumen-engine**。`installAround(scrollTarget,isStretchAllowed,onStretchDistance)` 只替换指定滚动容器的直接父槽位，失败恢复原位置；`samplingOverscroll/samplingBeforeContent` 供宿主在同一帧进行反向采样，`finishStretch` 在退场时清零。宿主不同时安装旧的回弹副本。

### 回弹采样与选中框的位置同步

`liquid.LiquidStretchSampling` 供宿主适配器和内置渲染器共享原生回弹坐标。主线程调用
`resolve(host, backdropRoot, out)`，传入实际绘制表面与稳定底图根；三个输出值分别是视口原点、
高度和有符号回弹强度；`out` 至少包含三个元素，无活动回弹时清零。主窗口和 Dialog 的 AGSL 绘制均将该数组传给采样后端；
`opticalTransform` 为不支持 AGSL 的位图后端在当前表面范围构造端点匹配的仿射近似。
`opticalTransform(bounds, localToBackdrop, rootX, rootY, sampling, out)` 写入调用方的矩阵，
无回弹时保留原坐标。实例复用矩阵与数组，不持有 View，不分配逐帧对象。

`LumenSlidingSelection` 在选中框边界、行程或长按跟随变换改变后重录自己的背景并通知位置变化，
静止帧不额外失效。宿主保留行内容、颜色和业务选择回调；无需另外维护选中框动画。
### ContainerMorphController 的回调构造

第三个参数可以传 `notifyPositionChanged: () -> Unit`。其他既有参数保持相同含义；可选参数增加：

- `resolveGeometry: ((preferLiveOrigin: Boolean) -> ContainerMorphGeometry?)?`：提供宿主已有来源坐标协议；只在准备形变时计算，不在逐帧路径调用。
- `exitTitleMode`：根据当前业务页选择来源标题或跨页标题淡变。
- `shouldFinishOnBack/onInternalBack`：保留页面内部返回逻辑；业务阻塞仍由 `isBusinessBlocked` 提供。
- `hasLaunchOrigin`：使用宿主原来的 Intent/弱引用来源时，告知入场是否可解析。

`start/beginPredictiveBack/progressPredictiveBack/cancelPredictiveBack/commitBack/onDestroy` 的调用顺序沿用原入口。`showExpanded()` 用于替换内部业务页前结束形变；`isExpanded/isSettledExpanded/isClosingOrFinished` 为只读状态。`onDestroy` 后这些入口为空操作，原 delegate 重载仍把位置刷新接到 `lumen.notifyPositionChanged()`。

## 19. Fluent 亚克力与层级（host，自1.4.3）

包 `com.lumen.coacervation.engine.host`，位于 **lumen-engine**。配方、回退规则与数值来源见 [FLUENT_MATERIAL.md](FLUENT_MATERIAL.md)。全部入口只能在主线程调用，关闭后为空操作。

| 声明 | 公开成员 |
|---|---|
| `LumenFluentControl(view, palette, options = LumenFluentControlOptions(), session: LumenSurfaceSession? = null, source: View? = null)` | `update(options)` / `updatePalette(palette)` / `notifyPositionChanged()` / `diagnostics(): LumenFluentDiagnostics` / `isClosed` / `close()`；安装可逆背景与平台阴影，只恢复仍由自己持有的背景、`elevation` 和 API 28+ 阴影颜色；来源不得包含 `view` |
| `LumenFluentControlOptions` | acrylic / elevation / cornerRadiusDp（null = 层级默认 4dp 或 8dp，0–128）/ stateLayerEnabled / focusVisualEnabled / reduceTransparency / reduceMotion |
| `LumenAcrylicOptions` | enabled / kind / tintColor / tintOpacity（0–1）/ luminosityOpacity（0–1）/ fallbackColor / blurRadiusDp（0–48，默认 48）/ noiseOpacity（0–0.1，默认 0.02）/ fallbackWhenWindowInactive / fallbackOnPowerSave / fallbackOnLowRamDevice / exactBlend；颜色和透明度为 null 时取 kind 的主题默认值 |
| `LumenFluentElevationOptions` | level / shadowEnabled / strokeEnabled / strokeWidthDp（0.5–2）/ accentStroke / pressedLowersElevation |
| `LumenAcrylicKind` | DEFAULT / BASE / ACCENT（ACCENT 以 `palette.primary` 着色） |
| `LumenFluentElevation(fluentValue)` | LAYER(1) / CONTROL(2) / CARD(8) / TOOLTIP(16) / FLYOUT(32) / DIALOG(128) |
| `LumenAcrylicFallbackReason` | NONE / DISABLED / REDUCE_TRANSPARENCY / LOW_RAM_DEVICE / POWER_SAVE / WINDOW_INACTIVE / NO_BACKDROP / BACKDROP_UNAVAILABLE / CLOSED |
| `LumenAcrylicBlend` | EXACT（API 29+ 平台混合模式）/ APPROXIMATE |
| `LumenFluentDiagnostics` | closed / acrylicVisible / fallbackReason / backdropBackend / blend / draws / transitions / noiseTextureBytes |

## 20. 可选云母与内容分层（host，自 1.4.5）

包 `com.lumen.coacervation.engine.host`，位于 **lumen-engine**。完整资料依据、配方、参数默认值、线程和预算见 [MICA_MATERIAL.md](MICA_MATERIAL.md)。除 `LumenMicaImage.prepare()` 外，控制器入口只能在主线程调用。此入口不改变 `SkinId`；避免与其他控制器共同接管同一个根背景。

| 声明 | 公开成员 |
|---|---|
| `LumenMicaBackdrop(view, palette, options = LumenMicaOptions(), image: LumenMicaImage? = null)` | `update(options)` / `updatePalette(palette)` / `setImage(image?)` / `refreshPolicy()` / `layerBackground(layer = CONTENT, radiusDp = 8f): Drawable` / `diagnostics(): LumenMicaDiagnostics` / `isClosed` / `close()`；关闭时仅恢复仍由自身持有的根背景，保留内边距；setter、刷新与关闭的迟到调用为空操作 |
| `LumenMicaImage` | `prepare(source: Bitmap): LumenMicaImage`（companion，`@WorkerThread`），`byteCount: Int`；输入为调用期间只读的软件 Bitmap，输出为独立不可变快照，不持有或回收输入；最长边 96 像素；取消通过线程中断传播 |
| `LumenMicaPreparer()`（自 1.5.2） | `prepare(source: LumenMicaImageSource, listener: LumenMicaPreparationListener)` / `cancel()` / `isClosed` / `close()`；主线程创建和调用；每实例一个工作线程，最多一个执行中请求、一个最新待处理来源及一个最新未交付结果；回调异步投递到主线程，不依赖 View 挂载；关闭后入口为空操作 |
| `LumenMicaImageSource`（自 1.5.2，fun interface） | `load(): Bitmap`（`@WorkerThread`）；只有开始执行才调用；返回独占、可读的软件 Bitmap，所有权转移到 preparer，后者在预处理返回或失败时回收；不得返回共享或正在显示的位图 |
| `LumenMicaPreparationListener`（自 1.5.2，fun interface） | `onPrepared(image: LumenMicaImage?, failure: LumenMicaPreparationFailure)`（异步主线程）；成功为 image 非空且 NONE，失败为 image 空；只交付最新请求；回调取得的快照可共享，preparer 不再回收它 |
| `LumenMicaPreparationFailure`（自 1.5.2） | NONE / SOURCE_FAILED / PREPARATION_FAILED / WORKER_UNAVAILABLE；不传异常原文、路径或 Uri |
| `LumenMicaOptions` | enabled = true / kind = BASE / tintColor、tintOpacity、luminosityOpacity、fallbackColor（默认 null，按 kind 与主题选择；opacity 限 0–1，拒绝非有限值）/ fallbackWhenWindowInactive、fallbackOnPowerSave、fallbackOnLowRamDevice = true / highContrast、reduceTransparency、reduceMotion = false |
| `LumenMicaKind` | BASE / BASE_ALT |
| `LumenMicaLayer` | COMMAND / CONTENT / CARD；工厂返回宿主所有的低透明度 Drawable，主题、kind、辅助功能改变后由宿主重新创建并赋值；radiusDp 限 0–128 |
| `LumenMicaFallbackReason` | NONE / DISABLED / HIGH_CONTRAST / REDUCE_TRANSPARENCY / LOW_RAM_DEVICE / POWER_SAVE / WINDOW_INACTIVE / NO_BACKGROUND / MEMORY_PRESSURE / RENDER_UNAVAILABLE / CLOSED |
| `LumenMicaDiagnostics` | closed / kind / fallbackReason / micaVisible（上一帧云母贡献，回退过渡中可能仍为 true）/ snapshotBytes（当前快照与着色副本合计）/ draws / transitions |

## 21. 小视口来源与共享录制（host，自 1.4.6）

包 `com.lumen.coacervation.engine.host`，位于 **lumen-engine**。全部入口在主线程调用。来源选择、材质、主题、业务坐标、失效与资源预算由宿主决定，API 不接管根 Window，不增加采样线程或常驻帧回调。接入见 [小视口内容源](VIEWPORT_CONTENT_SOURCE.md)与[局部会话](SURFACE_SESSIONS.md)。

| 声明 | 公开成员与语义 |
|---|---|
| `LumenViewportContentSource(source: LumenContentSource, regions: (consumer: (View, Float, Float, Float) -> Unit) -> Unit)` | `LumenVersionedContentSource` 的包装器；regions 同步枚举 `surface, insetX, insetY, padding`，单位为该 surface 本地 px；在原来源坐标中求相交区域并集并向外取整，source 的 coordinateView 在包装期间必须稳定 |
| `LumenViewportContentSource(content: View, regions)` | 同上；使用引擎既有 View 内容源，保留 View 自身滚动的录制语义 |
| `LumenViewportContentSource.sync(): Boolean` | 在会话 pre-draw 前显式更新移动区域、模糊余量与代理尺寸；返回裁剪边界是否变化。无有效区域时退回完整来源；provider 异常时暂不可用，之后可再次同步恢复；关闭后返回 false |
| `LumenViewportContentSource` 来源成员及 `close()` | 暴露稳定 coordinateView、原来源 dependencies、sourceEpoch、contentVersion、availability 与 excludesSurfaces；不通过坐标代理的 dirty 驱动捕获。ViewGroup 使用透明 overlay 坐标代理，普通 View 保留原锚点并裁剪 Canvas；源中全部注入表面须列入 regions，未声明排除表面的源不得包含其表面。close 移除代理，不关闭原来源；关闭后不重新附着或绘制 |
| `LumenDrawableScene.create(hardware: Boolean, scale: Float = 1f): LumenDrawableScene` | companion 工厂并提供 Java 静态重载；API 29+ 的硬件请求创建 RenderNode 录制场，旧系统回退 Picture。scale 及其倒数须有限，scale 在 `(0, 1]`；只影响硬件材质，软件保持完整分辨率 |
| `LumenDrawableScene.record(drawable: Drawable, width: Int, height: Int)` | 用 Drawable 当前 bounds 录制，宽高须为正；不修改其 bounds/callback，不保留传入 Drawable。硬件栅格尺寸向上取整；调用方限制尺寸与预算并裁剪逻辑区域，取整可能在右/下多出不足一个栅格像素 |
| `LumenDrawableScene.draw(canvas: Canvas)` / `close()` | 回放最近完成的录制；硬件实例只接受硬件 Canvas，软件采样须持有独立实例。RenderNode 在重新录制与尺寸变化时保持身份；不自动安排刷新。close 释放录制资源且幂等，关闭后的 record/draw/close 均为空操作 |

小视口的原生前景探针独立于自定义源的像素配方。可读性控制器按完整 source/target 相对矩阵映射探针，过滤无效区域时保持结果与绑定身份一致；这些修正不改变原有公开签名。

## 22. Prism 棱镜玻璃与液态选中框（自 1.5.0）

玻璃位于 **lumen-engine** 包 `com.lumen.coacervation.engine.host`；选中框位于 **lumen-motion** 包 `com.lumen.coacervation.engine.widget`。配方、默认值、深浅色与接入见 [PRISM_MATERIAL.md](PRISM_MATERIAL.md)。全部入口只能在主线程调用，关闭后为空操作。不改变 `SkinId`。

| 声明 | 公开成员 |
|---|---|
| `LumenPrismGlass(view, palette, options = LumenPrismOptions(), session: LumenSurfaceSession? = null, source: View? = null)` | `update(options)` / `updatePalette(palette)` / `notifyPositionChanged()` / `diagnostics(): LumenPrismDiagnostics` / `isClosed` / `close()`；直接作为 View 背景安装，只恢复仍由自己持有的背景；来源不得包含 `view` |
| `LumenPrismOptions` | enabled / cornerRadiusDp（null = 胶囊，0–128）/ blur / lens / surface / highlight / innerShadow / press / shadow / reduceTransparency / reduceMotion / fallbackOnLowRamDevice / fallbackOnPowerSave |
| `LumenPrismBlurOptions` | enabled（只控制模糊）/ radiusDp（0–48，默认 8）/ saturation（0–2，默认 1.5） |
| `LumenPrismLensOptions` | enabled / refractionHeightDp（0–64，默认 24）/ refractionAmountDp（0–64，默认 24）/ depthEffect（0–1）/ dispersion（0–1） |
| `LumenPrismSurfaceOptions` | tintColor / tintOpacity（null = 深浅色默认 #FAFAFA / #121212 @ 40%）/ fallbackColor / fallbackOpacity（0–1，默认 0.86）；颜色只取 RGB |
| `LumenPrismHighlightOptions` | enabled / widthDp（0–4）/ opacity（0–1）/ angleDegrees（±360）/ falloff（0–8）/ color（只取 RGB） |
| `LumenPrismInnerShadowOptions` | enabled（默认关）/ radiusDp（0–48）/ opacity（0–0.6）/ color |
| `LumenPrismShadowOptions` | enabled / radiusDp（0–48，默认 24）/ offsetYDp（±16，默认 4）/ opacity（0–0.5，默认 0.1）/ color |
| `LumenPrismPressOptions` | enabled / glowOpacity（0–0.5）/ washOpacity（0–0.3） |
| `LumenPrismFallbackReason` | NONE / DISABLED / REDUCE_TRANSPARENCY / LOW_RAM_DEVICE / POWER_SAVE / NO_BACKDROP / BUDGET_EXCEEDED / BACKDROP_UNAVAILABLE / CLOSED |
| `LumenPrismDiagnostics` | closed / glassVisible / fallbackReason / backdropBackend / lensActive / dark / draws |
| `LumenNavigationBar.setPrismSelection(options: LumenPrismSelectionOptions?)` / `isPrismSelectionEnabled` | null 为经典选中框（逐帧不变）；可随时切换或改参 |
| `LumenPrismSelectionOptions` | pressedScale（1–1.8）/ contentMagnification（1–1.6）/ lensHeightDp、lensAmountDp（0–32）/ dispersion（0–1）/ velocityDeformation（0–2）/ highlightOpacity（0–1）/ shadowOpacity（0–0.4）/ innerShadowOpacity（0–0.6）/ pressedWashOpacity（0–0.2）/ scaleXSpring、scaleYSpring（`LumenSpringSpec`）；`DEFAULT` |
