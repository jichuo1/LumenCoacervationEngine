# 公开 API 清单（1.2.2，含1.2.0/1.2.1兼容面）

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
| 通知 | `fun onDispatchTouchEvent(event: MotionEvent)`、`fun notifyPositionChanged()`、`fun notifyScrollPositionChanged(scrollHost: View)`、`fun bindContentSource(view: View)` | §4、§6.1 |
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

## 11. 交互与动效（`lumen-motion`）

### 11.1 长按弹性（`com.lumen.coacervation.engine.interaction`）

| 声明 | 说明 | 标准 |
|---|---|---|
| `LumenElasticInteraction(activity, lumen, isExcluded = { tag == EXCLUDED_TAG }, effectTuning = { lumen.effectTuning })` | `dispatch(event, superDispatch)`、`clear()`、`installDialog(dialog): () -> Unit`、`dispose()`；`effectTuning` 自 1.1.0，每次按下时读取 | §12、§2.5 |
| `ElasticInteractionController.EXCLUDED_TAG` / `CONTAINER_TAG` | 不参与弹性 / 只承载、自己不形变 | §12.1 |
| `ElasticInteractionController(root, notifyPositionChanged, isExcluded, highlightColor, effectTuning = { LumenEffectTuning.DEFAULT })` | 底层控制器（一个窗口一个）；宿主通常用上面的封装。`effectTuning` 自 1.1.0 | §12 |
| `ElasticTravelPolicy { AVOID_NEIGHBORS, PARENT_BOUNDS }`；`LumenElasticInteraction.travelPolicy` / `ElasticInteractionController.travelPolicy`（自 1.1.0） | 默认避让相邻控件；父容器模式保留原列表交叠行程。下次按下时生效，已开始的拖动/回弹不改变；Activity 封装同时传给其弹窗 | §14.7 |

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
| `ContainerMorphHost(context, collapsedSurfaceColor, expandedSurfaceColor, titleColor, sourceTitle, surfaceHandoffExpansion = 0.1f, collapsedStrokeColor = TRANSPARENT, collapsedStrokeWidthPx = 0f)` | 目标页内容根：`installContentInsets()`、`setMotionSurfaceBackground(d)`、`replacePage(page, toolbarTitle)`、`liquidBackdropRoot()`、`registerNavigationBack(view)`、`val expansion` | §13.5 |
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
| `LumenReveal(accentColor, highlightDelayMs = 240, highlightDurationMs = 560)` | `reveal(scrollView, target, settling = [], topOffsetPx = 0, highlight = true, afterReveal = null)`（目标在横向轮播里时先横向滚到可见）、`highlight(target)`、`cancel()`、`isActive` | §13.8、§14.6 |

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
| `LumenReorderCallback(canDrag, onMove, onDrop, directions = null)` | 长按拖拽排序（`ItemTouchHelper.Callback`）；`isDragging`；方向默认按布局自动判断 | §12.5、§14.5 |
| `LumenReorderCallback.directionsFor(layoutManager)`、`liftScale(longSidePx, density)`、`dropSettleDurationMs(px)` | 自动方向、按尺寸封顶的拾起放大、按距离的落位时长 | §14.5 |


## 12. 局部视效会话（host，自1.1.0）

规范与完整参数范围见 [SURFACE_SESSIONS.md](SURFACE_SESSIONS.md)。入口均为主线程。

- LumenSurfaceSession(context, palette, options)：不接管根/窗口/输入、不读写偏好的AutoCloseable会话。
- bind(view, surface, source: View?) / bindSource(view, surface, source: LumenContentSource?)：可逆背景绑定。
- setListener / updatePalette / updateOptions / notifyContentChanged / notifyPositionChanged / `notifyScrollPositionChanged(scrollHost: View)`。
- pause / resume / releaseGraphics / diagnostics / close；关闭后入口无副作用。
- LumenSurfaceBinding：id / update / diagnostics / close；旧绑定不能关闭新绑定。
- LumenContentSource：coordinateView / excludesSurfaces / drawContent(Canvas)。
- LumenSurfaceOptions / LumenSurfaceSampling / LumenSurfaceSessionOptions：不可变配置，所有字段受范围约束。
- LumenSurfaceMaterial / LumenSurfaceBackend / LumenSurfaceFadeDirection / LumenSurfaceFailure：公开枚举。
- LumenSurfaceState / LumenSurfaceDiagnostics：请求/实际材质、每绑定状态及会话计数。
- LumenSurfaceListener.onSurfaceState(id, backend, failure, firstVisibleDraw)：异步合并、异常隔离。
- LumenSurfacePresets.floating / fadingBand / staticPanel：可继续copy调节的配置。

## 13. 局部增强（自1.2.0）

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


## 16. 可选传感器光源（1.2.1，lumen-motion）

包`com.lumen.coacervation.engine.sensor`。参数全部字段、默认及范围见[P2配置](P2_EFFECTS_AND_ASSETS.md)。

- `LumenSensorLightOptions`及全部构造字段、`LumenSensorLightState`、`LumenSensorLightDiagnostics(state,sensorType,events,updates,rejected,registrations)`。
- `LumenLightVectorListener.onLight(x,y,z)`；`LumenGravityListener.onGravity(x,y,z,timestampNanos)`；`LumenGravityInput.sensorType/start(rateHz,allowAccelerometerFallback,listener)/stop()`。可注入输入须在主线程回调。
- `LumenSensorLightController(view,listener,options=LumenSensorLightOptions(),input=null)`：`start/stop/update/diagnostics/close`。回调只输出向量，宿主选择接入的binding。

## 17. 独立生成器及粒子（1.2.1，lumen-effects）

包`com.lumen.coacervation.engine.effects`。`LumenEffectOptions`、`LumenProceduralOptions`、`LumenFilmOptions`、`LumenPaperOptions`、`LumenEnergyOptions`、`LumenParticleOptions`全部构造字段受兼容承诺；参数清单与范围见[P2配置](P2_EFFECTS_AND_ASSETS.md)。

- `LumenProceduralKind { FILM,PAPER,ENERGY }`、`LumenParticleShape { ORB,STREAK }`、`LumenEffectState`和`LumenEffectDiagnostics`全部字段。
- `LumenEffectLayer(view,options=LumenEffectOptions(),clock=system)`：`update(value)`、`emitBurst(x,y):Int`、`triggerEnergy()`、`pause/resume/clear/advanceFrame/diagnostics/close`。x/y为宿主View内像素，关闭后调用为空操作。
- `LumenEffectPreset(options=LumenEffectOptions())`：`toJson():String`、`fromJson(text):LumenEffectPreset`，schema固定为1。导出只含参数，不含任意执行代码。

## 18. 统一动画资产（1.2.1，lumen-assets）

包`com.lumen.coacervation.engine.assets`。所有选项／预算见[P2配置](P2_EFFECTS_AND_ASSETS.md)。

- `LumenAssetOptions`全部构造字段；`LumenAssetSelection(artboard=null,animation=null,stateMachine=null)`；`LumenAssetFormat/Fit/State/Failure`枚举；`LumenAssetInput(name,kind)`、`LumenAssetInputKind { NUMBER,BOOLEAN,TRIGGER }`。
- `LumenAssetMetadata(format,width,height,durationMs,seekable,decodedImagePixels=0,nativeClock=false,speedControl=true,repeatControl=true,frameRateControl=true,inputs=emptyList())`；`LumenAssetDiagnostics(state,failure,generation,frames,dropped,inFlight,bytes,metadata)`。frames计量成功手动提交，无法统计供应商原生时钟帧。
- `LumenAssetException(failure)`供工厂明确拒绝原因；`LumenAssetSource.open():InputStream`、`LumenAssetFactory.format/decode(bytes,selection,options)`在会话后台执行，`attach(context,asset,options)`在主线程执行。
- `LumenDecodedAsset.metadata/close`：可在解析线程或主线程关闭，宿主实现须保证幂等、只释放自己的资源。`LumenAssetPlayer.view/metadata/update/render(progress):Boolean/setPlaying/close`及默认`setNumber/setBoolean/fire`在主线程执行；false表示不支持或未提交。
- `LumenAssetListener.onState(state,failure)`异步主线程通知；`LumenAssetPlayerFailureListener.onFailure(failure)`和默认`LumenAssetPlayer.setOnFailure(listener?)`供异步Surface回调失败上报，会话再次按代次过滤。
- `LumenAssetSession(container,options=LumenAssetOptions(),clock=system)`：`setListener`、`load(source,factory,selection=LumenAssetSelection())`、`play/pause/resume`、`seek(progress)`、`update(value)`、`setNumber(name,value):Boolean`、`setBoolean(name,value):Boolean`、`fire(name):Boolean`、`advanceFrame/diagnostics/close`。输入名称1–128字符，progress为0–1有限值。
- 独立工厂：`assets.lottie.LumenLottieFactory()`、`assets.pag.LumenPagFactory()`、`assets.rive.LumenRiveFactory(context,renderer=LumenRiveRenderer.CANVAS)`，各实现上述统一工厂。`assets.rive.LumenRiveRenderer { CANVAS,GPU }`为引擎自有枚举，默认SDK Canvas后端，GPU需显式选择。公开签名无播放库类型。

Rive原生时钟能力边界、PAGFile最终释放方式、供应商解析内存及中断边界属于[P2配置](P2_EFFECTS_AND_ASSETS.md)明确约束，不能把帧率或字节门禁解释为原生运行时的硬实时／内存保证。
