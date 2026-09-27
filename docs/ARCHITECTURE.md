# 凝光视效引擎架构

> 本文描述引擎内部如何组织、各层的职责与边界。§1～§10 是引擎本体（`lumen-engine`），§11 是交互与动效层（`lumen-motion`）。
>
> - 宿主接入的规范见 `INTEGRATION_STANDARD.md`；
> - 内部写法的规则见 `ENGINEERING_RULES.md`；
> - 公开 API 的清单见 `API.md`。

## 1. 总览

```
宿主 Activity（任意基类）
   │  组合持有
   ▼
LumenActivityDelegate ─────────────── host/      宿主唯一入口：生命周期、表面、控件、回弹、诊断
   │  prepare() 时创建
   ▼
ActivitySkinSession ───────────────── runtime/   一个 Activity 的视效会话：选材质、健康确认、失败回退
   │  只通过 GlowEngine 契约调用
   ▼
GlowEngine（契约 / SPI）─────────────── glow/
   ├─ FrostedMaterialRenderer ─────── material/  柔光：静态环境磨砂 + 悬浮表面软件透镜
   └─ LiquidActivityRenderer ──────── liquid/    高级材质：RuntimeShader 折射 + PixelCopy 实时取样
           └─ 后端 REFRACTION(33+) → BLUR(31+) → TRANSLUCENT

围绕契约的可组合组件：
   GlowFloatingChrome / GlowBackdropTarget ── glow/     悬浮栏可读性（溶解、自适应、内容节点玻璃）
   LiquidStretchViewport ──────────────────── liquid/   回弹视口（内部，经委托安装）
   TouchGlowRenderer / GlowState ──────────── touch/    触摸光晕
   LiquidBackgroundStore ──────────────────── background/ 自定义背景图

进程级：
   LumenEngine ── 配置、材质选择、实时取样开关、内存压力
   SkinRepository ── 材质状态机（持久化）   SkinRenderSessionRegistry ── 渲染会话登记
   LumenMemoryPressureHub ── 可丢弃图形资源的弱登记表
```

## 2. 包与职责

`lumen-engine` 共有 74 个源码文件，分布如下：

| 包 | 文件 | 职责 | 可见性 |
|---|---|---|---|
| （根） | 1 | `LumenEngine`：进程级入口与配置 | 公开 |
| `host` | 2 | `LumenActivityDelegate`、`LumenActivity`：宿主接入层 | 公开 |
| `runtime` | 10 | 会话、材质状态机、偏好编解码、内存压力、令牌解析 | 仅 `SkinSessionDiagnostics` 公开 |
| `glow` | 9 | `GlowEngine` 契约、悬浮栏可读性、内容探针、内容节点玻璃 | 契约与悬浮栏组件公开 |
| `liquid` | 30 | 高级材质：渲染器、后端、实时取样、反馈抑制、刷新率/热/ADPF、回弹视口、控件样式 | 少数宿主可实现的接口公开 |
| `material` | 7 | 柔光：渲染器、环境磨砂策略、软件透镜 | 内部 |
| `background` | 4 | 环境底图场景、自定义背景的导入/存储/解码 | 存储入口公开 |
| `geometry` | 2 | 视图之间的采样矩阵 | 内部 |
| `model` | 6 | `SkinId`、`SurfaceRole`、`LumenPalette`、令牌与参数 | 前三个公开 |
| `touch` | 2 | 触摸光晕：纯几何状态机 + 绘制器 | 公开 |
| `interaction` | 1 | `ElasticGestureClaim`：手势归属声明 | 公开 |

## 3. 分层规则

1. **宿主只接触 `host` 层与少数组件。** 宿主从不直接拿到渲染器、会话或 Drawable 的实现类。
2. **会话只认契约。** `ActivitySkinSession` 只通过 `GlowEngine` 调用材质，不按材质分支。

   两套材质的能力差异用契约方法的**默认空实现**表达。例如：
   - `bindContentSource` 只有柔光关心；
   - `notifyGestureActive` 只有高级材质关心。

   调用方永远不需要判断当前是哪种材质。
3. **策略与绘制分离。** 所有"算多少"的逻辑都写成不依赖 `android.graphics` 的 `*Policy` 对象，能在 JVM 上单测。这类逻辑包括：
   - 尺寸预算；
   - 降级顺序；
   - 可读性对比度；
   - 光晕几何；
   - 状态机。

   渲染器只负责"怎么画"。
4. **高版本平台类型只出现在 `*ApiNN` 隔离类里**（`ENGINEERING_RULES.md` §2）。例如 `LiquidBackendDriver` 这类通用接口不暴露任何高版本类型，所以 API 27 设备可以安全加载整个渲染器。

## 4. 会话生命周期

```
prepare()
  └ ActivitySkinSession.create(activity, palette)
      ├ SkinRepository.resolveRequestedSkin()          读取材质选择（首次读取时执行进程启动检查）
      ├ 若请求 LIQUID：SkinRepository.claimLiquidRenderSession() → LiquidActivityRenderer
      │     创建失败 → 立即回退柔光并记录失败
      └ 总是创建 FrostedMaterialRenderer               高级材质会话里它是休眠的回退引擎
bindRoot(root, onFailure)
  └ 活动引擎.bindRoot(root, callbacks)
      ├ onFirstVisibleDraw → SkinRepository.confirmLiquidHealthy()   两阶段激活的第二阶段
      └ onFatalFailure     → reportLiquidValidationFailure()
                              → 关闭高级材质、柔光接管根背景 → post { onFailure() }
onStart / onStop / onTrimMemory / onLowMemory → 两个引擎都转发（休眠的回退引擎同样跟随生命周期）
onDestroy → session.close()：注销内存压力监听、关闭两个渲染器、释放渲染会话登记
```

## 5. 材质状态机

材质选择由 `SkinRepository` 持久化，状态转移的判断集中在 `SkinRecoveryGuard`（纯函数，JVM 单测覆盖）。

| 事件 | 结果 |
|---|---|
| 用户选择 LIQUID | 写入"待确认"，附带新的激活尝试 ID 与当前渲染器版本 |
| 新会话首个成功的可见绘制 | 确认健康，写入"最近一次已知良好 = LIQUID" |
| 渲染器报告致命失败 | 回退到 MATERIAL_YOU 并持久化 |
| **新进程**首次读取时发现仍是"待确认" | 说明上次在确认前死亡，回退到 MATERIAL_YOU |
| 渲染器版本号递增 | 已确认的 LIQUID 需要重新走一次健康确认 |

两条关键约束：

- "发现待确认就回滚"只在新进程的首次读取时执行。同一进程里 `recreate()` 之后，必须继续看到待确认状态，否则两阶段激活永远无法完成。
- 同一进程同一时刻，只有最新的渲染会话有权确认或上报（`SkinRenderSessionRegistry`）。旧会话迟到的确认会被忽略，旧会话随即退回柔光。

## 6. 高级材质管线

```
稳定底图（LiquidBackdropSource）
   ├ 自动：AmbientBackdropScene 按配色生成（与柔光同一配方）
   └ 自定义：LiquidBackgroundStore 解码（后台线程 Lumen-LiquidBackground）
实时取样（可选，REALTIME_CAPTURE 档）
   PixelCopy → 三缓冲轮转（Lumen-LiquidCapture 线程后处理）
     ├ 反馈抑制：把引擎自己画的玻璃区域换回稳定底图，避免递归折射
     └ 静止门控：连续 2 张与基准逐像素相同 → 停止采集，窗口有新绘制再唤醒
后端（LiquidBackendSet，单向降级）
   REFRACTION：RuntimeShader 折射（API 33+，硬件加速）
   BLUR      ：RenderEffect 模糊（API 31+，硬件加速）
   TRANSLUCENT：半透明着色，零额外图形资源
表面（LiquidSurfaceDrawables）
   每个表面 Drawable 回调渲染器取几何与采样；只重录位置真正变化的表面
性能协调（LiquidPerformanceController / LiquidRefreshRateController）
   刷新率档位 = min(面板支持, 热降档上限, 吞吐降档上限)；ADPF 目标周期同步；只还原自己申请过的值
```

- **滚动抑制**：滚动期间，玻璃暂时只采样稳定底图，停顿不超过 160ms 仍算同一次滚动。这样做是因为滞后的截图会把旧位置的像素折射进来。
- **静态底图宿主**：实现了 `LiquidStaticBackdropHost` 的 Activity 保留实时档的光学参数，但从不发起 PixelCopy。
- **窗口失焦**：窗口失焦时（例如有弹窗盖在上面），不发起 PixelCopy；重新获焦后补做一次采集。

## 7. 柔光管线

- **静态环境磨砂**：`ModernMaterialPolicy` 以 0.20× 采样窗口底图，上限 16 万像素，在后台线程 `Lumen-SoftFrost` 上做预乘模糊。所有表面共用这一份结果。
- **悬浮表面透镜**：`LiveBackdropSampler` 在每次 pre-draw 时，把宿主指定的内容层录进一个低分辨率位图，然后做模糊与透镜重采样（`LensRefractionPolicy`）。
  - 采样上限 2.4 万像素，最小间隔 28ms，逐像素运算在 `Lumen-LiveLens` 线程上完成。
  - 任何一次采样抛异常，就永久退回静态磨砂。
- 柔光**不会失败**。它忽略健康回调，也是高级材质失败时的回退目标。

## 8. 悬浮栏可读性

`GlowFloatingChrome` 协调以下四件事：

1. **滚动边缘溶解**：在 `GlowBackdropTarget` 里加两层 `GlowScrollEdgeView`，按覆盖度把滚进栏下方的内容渐隐进窗口底图。这一步只需要一次带着色器的底图绘制，不需要离屏层。溶解高度取同一条边上所有栏的并集；`edge = null` 的表面（侧栏、悬浮按钮）不参与溶解，只做后三项。
2. **内容探针**：`GlowContentProbe` 的频率不超过 8Hz。主线程把栏下方的内容录进 `Picture`，由后台线程 `Lumen-GlowProbe` 光栅化，统计亮度与细节两个标量。
3. **可读性补偿**：`GlowLegibilityPolicy` 以 WCAG 对比度 4.5 为目标，算出表面加厚量与边缘清晰度。经过迟滞和 240ms 过渡后，分别交给引擎（表面着色）和宿主（前景色）。
4. **内容节点玻璃**（API 31+）：`GlowBackdropTarget` 把子 View 录进一个 RenderNode，悬浮栏的玻璃在自己的显示列表里引用同一个节点实时取样。屏幕上仍然只画一次。

溶解、探针、节点玻璃这三项都依赖同一条层级约束：栏是内容容器的兄弟（`INTEGRATION_STANDARD.md` §6.0）。

## 9. 进程级单例

| 单例 | 状态 | 线程安全 |
|---|---|---|
| `LumenEngine` | 配置（读取即冻结） | `synchronized` |
| `SkinRepository` | 材质状态缓存（不含 Android 对象） | `@Synchronized` |
| `SkinRenderSessionRegistry` | 当前有权的渲染会话 | 由 `SkinRepository` 保护 |
| `LumenMemoryPressureHub` | 会话的弱引用登记 | 主线程 |
| `LiquidBackgroundStore` | 无（每次读取偏好） | 内部锁 |

因为存在这些单例，同一进程只能有一份引擎（`INTEGRATION_STANDARD.md` §1.2）。

## 10. 扩展点

| 扩展点 | 现状 |
|---|---|
| 新材质（实现 `GlowEngine`） | 契约已公开；1.0 还没有注册入口，会话只会创建两种内置材质。以后加入注册入口属于新增 API，可以放在 1.x 的次版本里（见 `VERSIONING.md`）。 |
| 新表面角色 | 在 `SurfaceRole` 末尾追加。每个 `GlowEngine` 实现都必须处理新角色，属于契约变化。 |
| 新皮肤标识 | `SkinId.storageValue` 必须分配新的、永不复用的值。 |
| 存储命名 | `LumenStorageNames`，已开放。 |
| 控件换装 | 在 `lumen-controls` 这类可选模块里扩展，引擎本体不增加 AppCompat 依赖。 |

## 11. 交互与动效层（`lumen-motion`）

`lumen-motion` 依赖 `lumen-engine`，共 47 个源码文件。它不依赖 AppCompat 和 RecyclerView，也不使用反射。

```
宿主 Activity
   ├ LumenElasticInteraction ─── interaction/   包住 dispatchTouchEvent：长按 → 拖动形变 + 触点高光 → 弹簧回弹
   │     └ ElasticInteractionController（每个窗口一个；弹窗窗口经 installDialog 接入）
   ├ LumenModalPresenter ──────── motion/modal/  弹窗的打开与关闭
   │     ├ IconAnchoredMotion*     条目/图标 → 卡片的 outline 形变（持久表面 View 承载材质）
   │     ├ Bubble*                 锚定气泡（小角、逐行链式浮现、来源图标代理与图案守恒）
   │     ├ ModalTitleMotion        标题迁移 + ModalTitleDescriptions（描述原位渐隐）
   │     ├ ModalCardRoot           覆盖式子面板下，父面板按子面板覆盖区域让位
   │     ├ ModalBackdropBlur       平台跨窗口模糊（默认关）
   │     └ PredictiveBackApi33     API 33+ 返回回调的隔离层
   ├ ContainerMorph* ──────────── motion/morph/  条目 → 全屏 Activity（Launcher 在来源页，Host + Controller 在目标页）
   ├ LumenPagePager + PageTextChain ─ motion/pager/  可打断翻页 + 文字链
   ├ SectionExpansionController ─ motion/expansion/ 手风琴
   ├ LumenReveal ──────────────── motion/reveal/ 定位并高亮
   └ LumenNavigationBar / LumenSegmentScrubBar ─ widget/ 自带手势与高光的控件

共同内核（motion/）：InterruptibleMotionSession / Continuation / Policy / Phase、MotionRect、LumenEasing、MicroMotion
```

### 11.1 与引擎本体的边界

动效层只通过公开 API 使用引擎：

- **表面**：`LumenActivityDelegate` 的 `modalBackground` / `motionSurfaceBackground` / `floatingBackground` / `chromeOverlayBackground`；
- **位移通知**：`notifyPositionChanged()`。形变、翻页、文字链、手风琴、弹性位移的每一帧都要通知；
- **回弹**：`installStretch` / `finishStretch`；
- **触摸光晕**：`GlowState` / `GlowConfig` / `TouchGlowRenderer`；
- **形变表面**：实现 `LiquidMotionSurfaceFrameProvider`，让材质每帧读到当前矩形与圆角；
- **悬浮栏可读性**：`GlowLegibilityPolicy`，用于底栏前景色。

来源工程里有一处引擎侧配合（弹窗窗口里的运动不触发主窗口实时取样的抑制），写在 `lumen-engine` 的渲染器里，由 `ModalSkinPositionContractTest` 跨模块守住。

### 11.2 打断模型

所有形变都用同一套模型：一个 0..1 的进度，加一个 `InterruptibleMotionSession`。

- **打断**：反向、预测式返回、新手势接手时，不冻结在途动画，而是从当前值和当前速度用 `InterruptibleMotionContinuation` 续接到新目标。
- **失效**：用代次让所有 post 出去的入场、收尾工作失效。
- **保留画面参数**：`InterruptibleMotionPolicy.preserveFrame` 在入场中、取消返回中、预测式返回中保留同一套几何与内容时序，直到到达稳定端，这样打断不会让画面跳一下。

手风琴用逐帧推进的进度弹簧，翻页用速度匹配的交接时长，底栏和档位条用 `LumenSpring`。它们的打断方式各不相同，但都满足同一条：**反转只是换目标，位置和速度原样续跑**。

### 11.3 从来源工程抽出的编排

| 抽离后 | 来源 | 说明 |
|---|---|---|
| `LumenModalPresenter` | `MainActivity.presentSizedModalDialog` / `dismissWithAnimation` / `modalSurfaceBounds` 等，约 800 行 | 业务专属部分（发布亮点、更新检查）已去掉；覆盖式子面板从调用点约定升格为 `captureSubPanel` / `presentSubPanel` / `dismissParent` |
| `ContainerMorphController` / `ContainerMorphLauncher` | `DiagnosticsActivity` 的形变编排，约 350 行，以及 `MainActivity.launchDiagnostics` | 业务阻断条件改为 `isBusinessBlocked` 注入；来源登记表按目标页分开 |
| `LumenElasticInteraction` | `SkinnedActivity` 的弹性接线 | 行为不变 |
| `LumenReveal` | `MainActivity.revealSettingsSearchTarget` 与搜索高亮 | 分组展开改由宿主通过 `settling` 告知 |
| `MicroMotion` | 遥测摘要/亮色提示的文字切换、更新角标、过滤面板显隐、兼容提示条 | 提炼为通用过渡 |
| `LumenReorderCallback`（`lumen-controls`） | 「管理常用」面板的 `ItemTouchHelper` 回调 | 行为不变 |

### 11.4 尺寸与排布

各组件对尺寸与排布的适配全部落在纯几何函数里，JVM 单测按矩形覆盖网格、瀑布流、轮播、卡中卡（`LayoutAdaptationPolicyTest`）：

| 纯函数 | 用处 |
|---|---|
| `ElasticMotionGroupPolicy.neighborGaps` / `travelBound` | 长按弹性按同层兄弟限制行程 |
| `MorphCornerPolicy.collapsedRadius` | 两类形变的起点圆角 |
| `ExpansionFollowPolicy.rowShrink` / `isBelow` / `sharesColumn` / `sharesRow` | 手风琴在网格与横排里的行高换算 |
| `RevealPolicy.horizontalDelta` / `verticalDestination` | 定位时的横向、竖向滚动量 |
| `LumenReorderCallback.liftScale` / `directionsFor` | 排序的拾起放大与方向 |

View 侧只负责在按下、形变开始、定位开始时读一次几何（兄弟矩形、来源 outline 圆角、同行格子高度），逐帧路径不遍历视图树。
