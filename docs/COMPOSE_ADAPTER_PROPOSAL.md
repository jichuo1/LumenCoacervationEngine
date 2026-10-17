# Compose 接入能力强化方案

> 状态：研究提案，尚未实现、发布。文中的新模块和新 API 均为候选设计。
>
> 研究日期：2026-10-07。目的：把 SplashScreenAdvanced 已验证的 Compose 桥接沉淀为可复用能力，同时保留 View 宿主的现有接入契约。

## 1. 结论与基线

可行。建议分两步：先发布独立可选的 `lumen-compose`，封装 View 承载的材质、交互和弹窗；再按性能与兼容性验证结果，增加不依赖具体 View 的表面目标和动效驱动接口。

第一步就能让宿主使用声明式组件。第二步才解决直接在 Compose 绘制节点上呈现材质、减少嵌套 `ComposeView` 的问题。两步具有不同的验收条件。

本次核对的基线：

| 对象 | 版本或状态 | 用途 |
|---|---|---|
| 最新 GitHub Release | `1.0.0`，2026-09-27 发布 | SplashScreenAdvanced 实际依赖与桥接验证的基线 |
| 远端 `main` | `e078d05ff51bac4534e5dd24f6068e356da50df4` | 2026-10-07 经 GitHub API 核对的主线 |
| 引擎本地 HEAD | `9849dc0eb9370bdd39ad16c7729833ecaf1b41e9` | 含本地柔光自定义背景修复，与远端存在差异 |
| 主线文档 | `CHANGELOG.md` 的未发布节已有 `1.1.0` 增量 | 不等于这些 API 已存在于 `1.0.0` 产物 |
| 宿主桥接 | `D:\SplashScreenAdvanced\hyperx-compose\src\main\kotlin\dev\lackluster\hyperx\ui\lumen` | 现有实现与缺口的证据 |

本次分析所引用的 `GlowEngine`、`LumenActivityDelegate`、`LiquidSurfaceDrawables`、`LiquidActivityRenderer`、弹性交互和弹窗呈现器源码在本地与该远端提交一致；本地柔光背景修复不能作为已发布能力的依据。

## 2. 已验证的能力与证据边界

宿主当前通过 `AndroidView → FrameLayout → ComposeView` 承载一块完整表面。材质背景、文字与控件属于同一原生容器，弹性拖拽时整块移动；内层继承外层的 CompositionContext。

已有验证记录位于 `D:\SplashScreenAdvanced\build\lumen-refactor\validation.json`，对应 `final-instrumentation.log` 和 `final-gates.log`：

- Android 34 专用模拟器上 8 项仪器测试通过，涵盖 Compose 点击、原生表面归属、长按整块拖拽与回弹、复选框区域排除、高级材质与 Activity 重建。
- 同批测试覆盖 7 个主要目的地、10 个次级路由的恢复与渲染、材质选择弹窗、横竖屏切换。
- 宿主 66 项 JVM 测试、Debug 构建、Release R8 和 Lint 零错误通过。

这证明该宿主的桥接路线可运行。测试未证明任意 Compose 组件、其他导航库、Lazy 复用、旧系统、物理设备帧率或所有上游 View 控件已兼容。没有本次引擎新模块的编译或运行测试，因为模块尚未实现。

## 3. 为什么需要强化底层

### 3.1 材质需要独立的表面身份和几何

现有 [`GlowEngine.surface()`](../lumen-engine/src/main/java/com/lumen/coacervation/engine/glow/GlowEngine.kt) 返回 `Drawable`。[`LiquidSurfaceDrawable.draw()`](../lumen-engine/src/main/java/com/lumen/coacervation/engine/liquid/LiquidSurfaceDrawables.kt) 把 `callback` 转成 `View`，取得屏幕位置，并把真实几何登记进渲染器；没有 View 时使用 `(0, 0)`，也不登记表面。

[`LiquidActivityRenderer`](../lumen-engine/src/main/java/com/lumen/coacervation/engine/liquid/LiquidActivityRenderer.kt) 用 `WeakHashMap<View, LiquidSurfaceFootprint>` 保存表面，屏幕矩阵、位移失效和实时截图反馈抑制都依赖这个登记。

由此可推断：直接在每个 composable 的 `drawBehind` 中调用现有 Drawable，并不足以完成适配。即便统一把 `LocalView` 设置为 callback，多个 composable 仍会共用同一个登记键，而且每块表面的局部位置、父级变换和裁剪并不等于该 View 的几何。不能将这种写法公开为可靠能力。

### 3.2 Compose 手势归属不能由原生 pressed 状态完整表达

[`ElasticInteractionController`](../lumen-motion/src/main/java/com/lumen/coacervation/engine/interaction/ElasticInteractionController.kt) 遍历 View 树命中目标，并在捕获拖动前检查原生 `isPressed`；输入框、滑块、长按控件与标记排除节点会阻止祖先接管。

宿主用外层容器同步 DOWN 的处理结果到 `isPressed`，再用 Compose 屏幕坐标区域排除输入控件。这是有效桥接，但布尔处理结果无法表达 Compose 的全部事件消费、子控件接管和业务长按语义。普通滚动应一旦越过阈值就让出，不得在后续长按时重新抢回；原始事件也只能转发一次。

Compose 手势存在命中链、事件消费和不同传播阶段，应在适配层明确处理归属，而不能只加另一套全局拖动监听。[Android 官方手势文档](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)

### 3.3 会话与弹窗不能直接照搬宿主实现

当前桥接依赖 `HyperXActivity`、Miuix 主题和 `LocalPageActive`，不具备独立 SDK 的边界。正式模块需要宿主显式传入配色和生命周期所有者，并提供导航无关的活动场景标记。

[`LumenActivityDelegate`](../lumen-engine/src/main/java/com/lumen/coacervation/engine/host/LumenActivityDelegate.kt) 在 Activity 生命周期内缓存 palette 和 effect tuning。重组不能在每张卡片里创建会话；首版配色变更沿用重建约定，不应把每帧变化的参数作为创建会话的 key，也不能宣称已支持整个引擎无重建换色。

当前 Compose 弹窗桥接还有四个通用化缺口：

- 原生 Dialog 内的 ComposeView 需要 Lifecycle、SavedStateRegistry 和 ViewModelStore 所有者；不能假设任何 `Activity` 都实现这些接口。
- title 与 summary 在原生 TextView 创建时读取，后续重组未同步；动态正文已经更新，但标题与说明也应更新。
- 为保留输入语义，现实现排除了整个 Compose 正文；正式模块应支持按区域排除。
- 不可取消进度窗当前走单独的 Compose Dialog。现有呈现器自行处理系统返回并先退场，不能直接当作由 Compose `visible` 状态控制、可拒绝关闭的弹窗实现。

## 4. 模块与依赖设计

```text
View 宿主 ─────────────── lumen-engine
                             ▲
                             │
                         lumen-motion
                             ▲
                             │
Compose 宿主 ─────────── lumen-compose（新增，可选）

lumen-controls：继续负责 AppCompat / RecyclerView 适配
```

- `lumen-engine` 保持 Android 框架和 AndroidX core/annotation 的边界。
- `lumen-motion` 保持 Android、core 和引擎依赖，存放共享的纯动效策略与控制器。
- `lumen-compose` 才允许 Compose runtime、UI、foundation 以及必要的 lifecycle/savedstate 互操作依赖。Activity 集成便捷入口确有需要时再使用 `activity-compose`。
- 模块不依赖 Miuix、Material 3、Navigation 3、Koin 或宿主资源；这些由宿主选择。按钮、文字、开关等可以作为普通 Compose 内容传入。
- 不创建带偏好写入副作用的 composable。存储名仍由应用初始化时通过现有配置入口设置。

落地时需要明确扩展 [`ENGINEERING_RULES.md`](ENGINEERING_RULES.md) 的依赖与公开签名规则：Compose 类型只允许出现在可选模块公开 API 中，核心约束继续生效。不能把此例外扩散到 core/motion，也不能把当前提案当作规则已被修改。

候选依赖名称已按 [Android 官方设置文档](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) 核对：`androidx.compose.runtime:runtime`、`androidx.compose.ui:ui`、`androidx.compose.foundation:foundation`、`androidx.activity:activity-compose`。这不等于已经选定或加入依赖版本。

Compose compiler 插件 `org.jetbrains.kotlin.plugin.compose` 要与实际 Kotlin 编译器版本配套。引擎当前 catalog 为 Kotlin `2.3.10`，宿主为 `2.4.20`；不能把宿主 catalog 原样复制到引擎。模块应公布并实际测试最低 Compose/Kotlin/AGP 组合与升级组合，检查发布 POM、Gradle metadata 和下游依赖解析。核心 View 宿主不应因此被迫获得 Compose 运行时。

新增生产依赖在实现前按项目工作协议确认；本提案没有新增依赖。

## 5. 首版声明式 API 的范围

以下名称为候选，不是当前可调用 API：

| 候选入口 | 责任 | 首版边界 |
|---|---|---|
| `LumenHost(controller)` | 向子树提供会话与配色 | controller 由 Activity 持有，准备时机与授权门由宿主决定 |
| `LumenScene(active, visible)` | 区分可见背景页和可交互前景页 | 关闭自己的输入/可访问性/动画工作；不替宿主管理业务 BackHandler |
| `LumenSurface` / `LumenCard` | 按 SurfaceRole 声明材质，统一内容归属 | 先支持圆角矩形与明确约束的布局，内部采用已验证的 View 承载路径 |
| `Modifier.lumenInputExclusion()` | 输入框、滑块、业务长按等保留手势 | DOWN 确定整段手势归属；移除、停用和切页有撤销路径 |
| `LumenDialog` | Compose 状态控制的面板与关闭动画 | 必须先完成关闭请求、取消策略和一次性完成回调的设计 |

建议的宿主写法示意：

```kotlin
// 设计示意，尚不可编译；controller 在 Activity 中创建并转发生命周期和原始输入。
LumenHost(controller = lumenHost) {
    LumenScene(active = pageIsForeground, visible = pageIsVisible) {
        LumenSurface(
            role = SurfaceRole.CARD,
            fallbackColor = colors.surface,
            cornerRadius = 16.dp,
            elastic = LumenElasticMode.HoldAndDrag,
        ) {
            Column {
                Text("设置")
                Switch(
                    modifier = Modifier.lumenInputExclusion(),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
        }
    }
    LumenDialog(
        visible = showDialog,
        onDismissRequest = { showDialog = false },
        dismissPolicy = LumenDismissPolicy.UserCancelable,
    ) {
        SettingsContent()
    }
}
```

Activity 接入可提供组合式 controller 和可选的 ComponentActivity 便捷基类。组合式入口必须能与宿主已有基类共存，按现有顺序转发 `dispatchTouchEvent`、start/stop、内存和 destroy，原始输入每条路径只能派发一次。首版不以隐式替换 Window.Callback 作为唯一接入方式；如以后提供自动安装，必须验证回调链共存、身份校验与幂等恢复。

首版不承诺替换全部原生控件、任意 Compose Shape、Compose Multiplatform 或完整导航框架。上浮栏内容节点采样、气泡图标交接等需要额外原型，不能因基础 Card 已工作而被标注为支持。

## 6. 发布前必须补齐的桥接能力

| 项目 | 实现要求 | 验证重点 |
|---|---|---|
| Composition 继承 | 内层继承正确的父 context，重绑定时更新 | 主题、CompositionLocal、remember/saveable 状态不丢失 |
| Lazy 列表 | 正确实现或明确禁用 View 复用；不只添加空 `onReset` | 排序、替换、离屏再入，旧业务 state、焦点、手势与会话不串项 |
| 保存状态 | 每个 ComposeView 身份唯一，恢复与宿主稳定 key 对齐 | 多表面重建、进程恢复；不复用旧项目的保存状态 |
| 释放 | reset/release 清除 press、动画、排除区、回调和背景引用 | detach/重新 attach/池回收/窗口销毁后无迟到更新 |
| 手势 | 明确内外层滚动、点击、长按和多指优先级 | 快速滚动后长按不抢回；拖动捕获后业务点击不再发生 |
| 布局与取样 | 合并位移通知，在最终几何应用后通知，不持续空转 | Compose 滚动、祖先 graphicsLayer、IME、分屏、旋转、密度与 RTL |
| 弹窗 | 请求关闭与已关闭分开；外部 visible 是状态来源 | 业务拒绝关闭、不可取消、预测返回取消/提交、外部关闭、重建 |
| 可访问性 | 材质包装层不制造额外假点击节点，保留内容语义 | TalkBack、键盘焦点、隐藏但保留的页面 |
| 预览与诊断 | 编辑器提供静态外观，运行时暴露实际后端与降级原因 | Preview 不创建渲染会话；能力失败有明确回退 |

Android 官方互操作文档明确：Lazy 中的 View 复用需要非空 `onReset`，最终退出则用 `onRelease`。复用前的重置行为必须处理完整；先验证状态隔离再打开复用。[Views in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)

多个 ComposeView 的保存状态要求唯一 ID，销毁策略也应按具体宿主选择，而非所有场景固定一种。[Compose in Views](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views)

跨越 View/Compose 的嵌套滚动需要单独检查双方协作；普通包装容器不自动成为正确的嵌套滚动父节点。这里应保留测试场景，不能声称加一个 Modifier 就兼容所有父容器。[Nested scroll modifiers](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers)

## 7. 后续核心强化：表面目标与共享动效

### 7.1 以独立表面目标替代必须有 View 的绘制假设

候选设计是新增可选的表面目标能力，由内置渲染器实现。现有 `GlowEngine.surface()`、View 接入和第三方实现继续可用；新能力可以先走独立接口，不给原 SPI 增加没有默认实现的抽象成员。

每块表面的目标需要明确提供：

- 独立且稳定的身份，不以同一个 Compose 根 View 代替全部表面。
- 所属窗口、attach/visible/active 状态和当前会话代次。
- 局部 bounds、圆角、局部到窗口/屏幕的变换、裁剪与有效透明度。
- 可取消的失效请求，以及绘制后几何登记和反馈抑制信息。
- 幂等释放与过期结果屏障。表面移除后，采样缓冲或动画回调不能重新激活它。

核心签名使用引擎自有类型和现有低版本 Android Canvas/矩阵类型。Compose `LayoutCoordinates`、Modifier 和状态仅在适配模块出现。Geometry、采样、反馈遮罩和 invalidation 必须一起适配，不能只增加一个接受 Canvas 的 draw 方法。

第一轮限制为可逆二维变换及圆角矩形；无法正确表达的裁剪或三维变换有明确静态降级。验证后再扩展形状。已存在的 View 适配也应通过同一目标逻辑工作，以避免维护两套不同的位移和反馈算法。

支持该目标后，可研究使用 Compose 绘制节点承载材质，而内容仍处于原 Composition 中。高频状态优先在布局、绘制或 graphicsLayer 阶段读取；性能收益必须测量，不能因为减少 View 数量就断言更快。[Compose phases and performance](https://developer.android.com/develop/ui/compose/performance/phases)

### 7.2 共享纯策略，分别驱动 View 与 Compose

已有 `MotionRect`、`InterruptibleMotionPolicy/Session/Continuation` 是可复用的纯模型。`ElasticGestureGate`、弹性计算、`ContainerMorphSpec` 及帧缓冲等仍是 internal，新的独立模块不能直接调用。

建议提供窄范围、稳定的动效状态与驱动门面，内部保留这些策略的实现细节。View 驱动写 View 属性；Compose 驱动写布局/绘制状态，两者共用阈值、速度、打断、预测返回和 generation 规则。逐帧输出复用缓冲，不复制一份弹簧算法，也不按需公开全部 internal 类。

完整页面导航保留宿主责任：路由、保存状态、业务返回拦截、来源与目标标题语义。引擎只提供来源几何到目标几何的连续形变与输入门控协议；不把 SplashScreenAdvanced 的 `PageMotionHost` 或 Navigation 3 类型引入通用模块。

### 7.3 浮动材质要先证明采样拓扑

现有 `bindContentSource` 要求内容源是悬浮表面的兄弟，不能是会包含自身的祖先。Compose 适配应原型验证内容层、chrome 层的独立录制、相对矩阵与反馈排除，再决定 `LumenScaffold` 是否能统一提供这些能力。基础 TOP_BAR 材质工作并不等于 `GlowFloatingChrome` 的所有可读性与内容节点效果已成立。

## 8. 实施顺序与验收门禁

| 阶段 | 产出 | 通过后才能推进的条件 |
|---|---|---|
| A：提取最小适配 | 可选模块、Host、Surface、Scene、输入排除和 Compose 示例 | 无 HyperX/Miuix/导航耦合，Compose 下游能只依赖发布产物运行，View 宿主无 Compose 传递依赖 |
| B：补齐发布契约 | Lazy 复用、状态恢复、弹窗状态协调、预览和诊断 | 新的生命周期、输入、布局、弹窗用例全通过，文档声明与真实能力一致 |
| C：核心目标原型 | 独立几何/失效/反馈协议与直接 Compose 绘制实验 | 同场景画面/采样正确；相比桥接基线帧时、内存与分配达到预先记录的预算 |
| D：共享动效与拓展 | 双驱动模型、复杂形变与浮动采样 | 中断和预测返回一致，旧 View 回归通过，复杂采样拓扑无自采反馈 |

A/B 具备可发布价值，不必等待 C/D。每阶段都应保留降级能力与基线对比，不能把所有体系重构塞进一次发布。

测试矩阵至少覆盖：

1. API 27 的柔光/高级材质低档、API 31 的 BLUR、API 33 的折射，以及 API 34 的预测返回路径。现有 View Demo 冒烟测试继续运行。
2. Compose 多表面同时显示、嵌套表面、Lazy 重排/替换/回收、内部与外部滚动、祖先变换、隐藏保留页、disabled 状态、输入框/滑块/业务长按与多指取消。
3. Activity recreate、保存状态及进程恢复、Dialog 创建/关闭/重建、返回拒绝与取消、IME 整窗位移、横竖屏/分屏、RTL、动态文字尺寸。
4. 会话关闭后的迟到回调、内存压力、后端失败单向降级、不可用能力回退；高级材质仅由最新会话确认健康。
5. Release 下物理设备对比：长列表滚动、连续开关面板、弹性拖拽、静止页。记录 frame time 分布与掉帧、PSS/Java heap、View/Composition 数量、分配、PixelCopy 次数、静止时 GPU/线程工作；记录硬件、刷新率、材质档位和基线，不以模拟器或截图推断帧率。

常规门禁沿用 `assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug`，新增模块与 Compose 示例有对应的单测、仪器测试和 Release 混淆检查；涉及平台类型时继续跑 DEX 审计。性能原型可使用 Perfetto 与 Macrobenchmark 的 trace 结果，但测量工具依赖应留在测试模块。[Composition tracing](https://developer.android.com/develop/ui/compose/tooling/tracing)

## 9. 版本与宿主迁移

按 [`VERSIONING.md`](VERSIONING.md)，新增可选模块与兼容的公开 API 属次版本。当前主线已有 `1.1.0` 未发布计划，具体版本应与当时发布安排协调；本次不打 tag、不改变版本号。

保持现有 SPI、宿主义务与存储语义时，无需递增 `CONTRACT_VERSION`。如果后续改动已有方法语义、增加 View 宿主必须履行的条款，或破坏第三方 GlowEngine 实现，则需按现有规则升主版本与契约版本，不能以“只是 Compose 支持”绕过。

新增模块应加入与 core/motion 相同版本的发布、sources JAR 和 POM 验证；发布后再让 SplashScreenAdvanced 用该产物替换本地桥接。迁移时必须保留现有存储名、材质偏好、导航状态和逐项回归证据。

本次研究只新增本文件。实现建议是先完成 A/B，将“每个项目手写一次桥接”收敛成可复用声明式接入，再依据 C 阶段的数据决定是否推广直接 Compose 绘制。
