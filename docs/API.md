# 公开 API 清单（1.0.0）

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

### `LumenActivityDelegate(activity: Activity, paletteProvider: () -> LumenPalette, effectTuningProvider: () -> LumenEffectTuning = { LumenEffectTuning.DEFAULT })`

第三个参数自 1.1.0；构造函数带 `@JvmOverloads`，两参数的写法与旧二进制保持可用。

| 分组 | 成员 | 标准 |
|---|---|---|
| 状态 | `val palette: LumenPalette`、`val isPrepared: Boolean`、`val engine: GlowEngine?`（每次现取，**不得缓存**） | §2.2、§6 |
| | `val effectTuning: LumenEffectTuning`（自 1.1.0；首次读取后缓存） | §2.5 |
| 会话 | `fun prepare()`、`fun bindRoot(root: View, onFailure: (() -> Unit)? = null): Boolean` | §2.3、§2.4 |
| 通知 | `fun onDispatchTouchEvent(event: MotionEvent)`、`fun notifyPositionChanged()`、`fun bindContentSource(view: View)` | §4、§6.1 |
| 表面 | `fun surface(color, radiusDp, role: SurfaceRole): Drawable` | §5 |
| | `cardBackground` / `floatingBackground` / `topBarBackground` / `selectionBackground` / `modalBackground`（`color = palette.surface`，`radiusDp` 有默认值） | §5 |
| | `motionSurfaceBackground(color, radiusDp)`、`liquidMotionSurfaceBackgroundOrNull(color, radiusDp): Drawable?` | §5.2 |
| | `chromeOverlayBackground(color, radiusDp, selected = false)`、`neutralWindowBackground()` | §5、§5.5 |
| 控件 | `styleActionButton(view: TextView, filled: Boolean, radiusDp = 20f)`、`styleSelectionControl(view, radiusDp, selected)`、`styleStatusChip(view: TextView, accent, radiusDp = 9f)`、`controlOutline(radiusDp, emphasized = false): Drawable` | §5.3 |
| 回弹 | `installStretch(scrollTarget: View, isStretchAllowed: () -> Boolean = { true }): View?`、`finishStretch(view: View?)` | §5.4 |
| 诊断 | `val isLiquidRequested`、`val isLiquidEffective`、`val backendName: String?`、`fun diagnostics(): SkinSessionDiagnostics?` | §3.2 |
| 生命周期 | `onStart()`、`onStop()`、`onTrimMemory(level)`、`onLowMemory()`、`onDestroy()` | §2.1 |

### `abstract class LumenActivity : android.app.Activity`

- `val lumen: LumenActivityDelegate`：懒创建。
- `protected open fun resolvePalette(): LumenPalette`：默认返回 `LumenPalette.neutral(系统深浅色)`。
- `protected open fun resolveEffectTuning(): LumenEffectTuning`：默认 `LumenEffectTuning.DEFAULT`（自 1.1.0）。
- 已接好 §2.1 的全部转发。

## 3. 数据类型（`com.lumen.coacervation.engine.model`）

| 类型 | 说明 |
|---|---|
| `LumenPalette(primary, onPrimary, secondary, tertiary, surface, background, surfaceVariant, textPrimary, textSecondary)` | 宿主配色（ARGB Int） |
| `LumenPalette.modern(primary, onPrimary, secondary, tertiary, dark): LumenPalette` | 宿主给强调色，表面与文字用引擎推荐的中性色 |
| `LumenPalette.neutral(dark): LumenPalette` | 引擎自带的中性配色 |
| `LumenEffectTuning(edgeHighlightWidth = 1f, edgeHighlightIntensity = 1f, dragGlowIntensity = 1f, dragGlowRadius = 1f)` | 视效调参倍率（自 1.1.0，§2.5）；超范围构造抛 `IllegalArgumentException` |
| `LumenEffectTuning.DEFAULT`、`LumenEffectTuning.clamped(...)` | 引擎原样；把任意输入收进范围（非有限值按 1） |
| `LumenEffectTuning.MIN_EDGE_WIDTH` / `MAX_EDGE_WIDTH` / `MAX_EDGE_INTENSITY` / `MAX_DRAG_GLOW_INTENSITY` / `MIN_DRAG_GLOW_RADIUS` / `MAX_DRAG_GLOW_RADIUS` | 范围常量：0.25 / 4 / 3 / 4 / 0.5 / 2 |
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

## 11. 交互与动效（`lumen-motion`）

### 11.1 长按弹性（`com.lumen.coacervation.engine.interaction`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenElasticInteraction(activity, lumen, isExcluded = { tag == EXCLUDED_TAG }, effectTuning = { lumen.effectTuning })` | `dispatch(event, superDispatch)`、`clear()`、`installDialog(dialog): () -> Unit`、`dispose()`；`effectTuning` 自 1.1.0，每次长按开始时读取 | §12、§2.5 |
| `ElasticInteractionController.EXCLUDED_TAG` / `CONTAINER_TAG` | 不参与弹性 / 只承载、自己不形变 | §12.1 |
| `ElasticInteractionController(root, notifyPositionChanged, isExcluded, highlightColor, effectTuning = { LumenEffectTuning.DEFAULT })` | 底层控制器（一个窗口一个）；宿主通常用上面的封装。`effectTuning` 自 1.1.0 | §12 |

### 11.2 可打断动画内核（`com.lumen.coacervation.engine.motion`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `InterruptibleMotionSession` | `invalidate(): Long`、`owns(token)`、`reset(value, now)`、`sample(value, now)`、`velocity(now)` | §13.0 |
| `InterruptibleMotionContinuation(start, target, velocity, durationMs)` | `value(fraction)`：按当前速度续接，切线有界 | §13.0 |
| `InterruptibleMotionPolicy` / `InterruptibleMotionPhase` | `canNavigate`、`preserveFrame`、`remainingDuration`；阶段枚举 | §13.0 |
| `MotionRect(left, top, right, bottom)` | 与 Android 无关的矩形（屏幕或窗口坐标） | §13 |
| `MorphCornerPolicy.collapsedRadius(declared, width, height)` | 形变起点圆角：来源声明的圆角（≤ 短边一半），没有声明取短边一半 | §14.2 |
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
| `ContainerMorphHost(context, collapsedSurfaceColor, expandedSurfaceColor, titleColor, sourceTitle, surfaceHandoffExpansion = 0.1f, collapsedStrokeColor = TRANSPARENT, collapsedStrokeWidthPx = 0f)` | 目标页内容根：`installContentInsets()`、`setMotionSurfaceBackground(d)`、`replacePage(page, toolbarTitle)`、`liquidBackdropRoot()`、`registerNavigationBack(view)`、`val expansion` | §13.5 |
| `ContainerMorphController(activity, host, lumen, destination, launchOrigin, allowLaunchOriginForExit, destinationTitle, collapsedCornerRadiusDp = 12f, isBusinessBlocked = { false }, onMotionStarted = {}, onExpanded = {})` | `start(isFreshLaunch)`、`beginPredictiveBack()`、`progressPredictiveBack(p)`、`cancelPredictiveBack()`、`commitBack()`、`isSettledExpanded`、`isExpanded`、`onDestroy()`；`suppressSystemTransitions(activity)` | §13.5 |
| `ContainerMorphOrigin.from(intent): ContainerMorphOrigin?` | 目标页读取启动几何；`entryCornerRadiusPx` 是入口卡片声明的圆角（NaN 表示没有） | §13.5、§14.2 |
| `ContainerMorphEntrySpec` | 来源工程诊断入口的视觉参数（圆角、描边、表面色） | §13.5 |

### 11.5 翻页、手风琴、定位（`motion.pager` / `motion.expansion` / `motion.reveal`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenPagePager(context)` | 页数不限；`selectPage(index, animate = true)`、`selectedPage`、`pagePosition`、`isSettled`、`motionAnchorY`、`switchParts`；回调 `onPageSelected` / `onMotionStarted` / `onUserInteraction` / `onPositionChanged`；`LumenPagePager.frameworkSwitchParts(view)` | §13.6 |
| `PageTextChain(pager, headings)` | `onPositionChanged()`、`dispose()` | §13.6 |
| `LumenPageScrollView(context, onUserScroll, onContentTouch)` | 页内滚动容器（`NestedScrollView`，实现 `LiquidStretchGestureObserver`） | §13.6 |
| `SectionExpansionController(card, content, chevron, density, cornerRadiusDp = 12f, notifyPositionChanged)` | `setExpanded(target, animate = true)`、`expanded`、`cancel()` | §13.7 |
| `LumenReveal(accentColor, highlightDelayMs = 240, highlightDurationMs = 560)` | `reveal(scrollView, target, settling = [], topOffsetPx = 0, highlight = true, afterReveal = null)`（目标在横向轮播里时先横向滚到可见）、`highlight(target)`、`cancel()`、`isActive` | §13.8、§14.6 |

### 11.6 控件（`com.lumen.coacervation.engine.widget`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenNavigationBar(context, titles, icons, colors, backgroundFactory, onSelect, onUserInteraction, onVisualMovement)` | 胶囊底栏（1～6 项，超出报错）：`setPageProgress(v, notifyPositionChanged = true)`、`setSelectedPage(i)`、`setLegibility(boost, haloColor)`、`dispose()` | §12.4 |
| `NavigationBarColors(text, selectedText, highlight)`、`NavigationBarSurface { BAR, SELECTION }` | 底栏/档位条配色与表面 | §12.4 |
| `LumenSegmentScrubBar(context, attrs)` | 分段档位条（1～8 段，超出报错）：`configure(labels, colors, thumbBackground, trackBackground, selectedIndex, onSelect)` | §12.4、§15.1 |
| `LumenSpring(start, target, velocity = 0f)` | 解析阻尼弹簧：`value(seconds)`、`velocity(seconds)`；重定向保留位置与速度 | §13.0 |
| `CoverableRippleDrawable(baseColor, content, mask)`、`CoverableRippleDrawable.rounded(palette, cornerRadiusPx)`、`coverOpacity` | 自绘圆角涟漪；可被上层表面"盖住"而淡出 | §12.2、§13.2 |
| `BubbleDrawable(bubbleColor, arrowWidthPx, arrowHeightPx, cornerRadiusPx, arrowOffsetPx, strokeColor = 0, strokeWidthPx = 0f)` | 带箭头的气泡背景（矢量缩放） | — |

## 12. `lumen-controls` 补充

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenControls.switchParts(view): Pair<Drawable?, Drawable?>?` | `SwitchCompat` 的（轨道, 滑块），接到 `LumenPagePager.switchParts` | §13.6 |
| `LumenReorderCallback(canDrag, onMove, onDrop, directions = null)` | 长按拖拽排序（`ItemTouchHelper.Callback`）；`isDragging`；方向默认按布局自动判断 | §12.5、§14.5 |
| `LumenReorderCallback.directionsFor(layoutManager)`、`liftScale(longSidePx, density)`、`dropSettleDurationMs(px)` | 自动方向、按尺寸封顶的拾起放大、按距离的落位时长 | §14.5 |
