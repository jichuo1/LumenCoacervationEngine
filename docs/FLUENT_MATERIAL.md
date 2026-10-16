# Fluent 亚克力与层级（`LumenFluentControl`）

把 Windows Fluent 设计体系里的**应用内亚克力**（in-app acrylic）和**层级描边 + 阴影**（layering & elevation）做成一种可接到任意 View 上的控件效果。它属于引擎本体 `lumen-engine`，与局部视效会话（`LumenSurfaceSession`）配合使用，不进入持久化材质状态机。

## 1. 来源与对齐范围

数值全部取自微软的公开资料与开源实现，不来自二手博客：

| 来源 | 取用内容 |
|---|---|
| Microsoft Learn：Acrylic material、Mica material、Materials、Layering and elevation、System backdrops | 用途约束、回退条件、层级数值（Layer 1 / Control 2 / Card 8 / Tooltip 16 / Flyout 32 / Dialog 128）、按压降为 1 |
| microsoft-ui-xaml `controls/dev/Materials/Acrylic/AcrylicBrush.cpp/.h` | 配方顺序、模糊 30、噪点 2%、TintOpacity 修正、亮度色推导、167ms 交叉淡化与 cubic-bezier(0.5, 0, 0, 0.9) |
| microsoft-ui-xaml `AcrylicBrush_themeresources.xaml` | `AcrylicInAppFillColorDefault/Base` 与 Accent 变体的浅色、深色数值 |
| microsoft-ui-xaml `Common_themeresources_any.xaml`、`CornerRadius_themeresources.xaml` | `ControlElevationBorderBrush` 渐变、描边 / 轻填充 / 焦点颜色、4dp 与 8dp 圆角、83ms 状态过渡 |
| Avalonia `Avalonia.Skia/DrawingContextImpl.cs` | Skia 上的参照实现（平铺噪点 + 色调合成），用于核对 Android 侧写法 |

Mica 面向整个窗口的底层，使用独立可选入口 `LumenMicaBackdrop`，与这里的应用内亚克力分别接入。云母的设计依据、Android 适配配方与分层见 [MICA_MATERIAL.md](MICA_MATERIAL.md)；未公开的 Windows 默认合成参数不作为等价实现承诺。

## 2. 渲染配方

与 WinUI `AcrylicBrush` 的效果图一一对应：

```
不透明回退色（保证模糊不和透明混合）
  └ 模糊背景：宿主会话里的只绘制绑定（软件或 GPU 后端，按会话预算自动选择）
      └ 亮度层：Luminosity 混合（保留背景色相/饱和度，亮度取自色调色）
          └ 色调层：Color 混合（保留亮度，色相/饱和度取自色调色）
              └ 2% 平铺噪点
                  └ 与回退色交叉淡化（权重 0 = 全回退，1 = 全亚克力）
状态层（悬停 / 按下的 Subtle 填充，83ms）
层级描边（顶部或底部 3dp 渐变带）
键盘焦点双环（外 2dp、内 1dp）
平台阴影（View.elevation + 轮廓）
```

- **API 29+**：亮度层与色调层使用平台 `BlendMode.LUMINOSITY` / `BlendMode.COLOR`，与 W3C 非可分混合公式一致（`LumenAcrylicBlend.EXACT`）。WinUI 源码因 Composition 的枚举命名错误把两者对调，这里还原的是它的**视觉意图**。
- **API 27–28** 或 `exactBlend = false`：亮度层退化为源覆盖、透明度乘 0.82，色调层为源覆盖（`APPROXIMATE`）。色相保留比精确混合弱，不宣称等价。
- **模糊半径**：WinUI 的 30 是标准差；HWUI 把半径换算为 σ = 0.57735·r + 0.5，σ=30 需要约 51dp，超过会话上限 48dp，所以默认取 48dp（σ≈28.2）。
- **噪点**：进程共享一张 64×64 ARGB 灰噪图（16 KiB，常量预算，JVM 测试守住），引用计数，最后释放时只丢引用、不 `recycle()`，因为显示列表可能仍在引用像素。

自 1.4.6 起，复合亚克力的模糊底图在 GPU 与软件路径上均保留输入颜色，亮度只由上面的 Fluent 配方合成。此前先应用普通磨砂的预提亮、再混合 Fluent 亮度，会把粉色、薄荷绿等浅色底图冲淡。保色通过内部绑定和软件作业快照传递，主题更新、选项重建和回退后重新绑定继续采用同一规则；普通 `FROSTED` 表面保留既有的预提亮。公开 `LumenSurfaceSampling` 的构造、默认参数及 copy 签名未添加新参数。来源贡献和许可见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 3. 回退规则

| 原因（`LumenAcrylicFallbackReason`） | 触发 | 是否释放采样绑定 |
|---|---|---|
| `DISABLED` | `acrylic.enabled = false` | 是 |
| `REDUCE_TRANSPARENCY` | 宿主传入 `reduceTransparency = true`（Android 没有公开的系统开关） | 是 |
| `LOW_RAM_DEVICE` | `ActivityManager.isLowRamDevice()`，可用 `fallbackOnLowRamDevice` 关闭 | 是 |
| `POWER_SAVE` | `PowerManager.isPowerSaveMode()`，在挂载、窗口焦点变化、`update()` 时重读 | 是 |
| `WINDOW_INACTIVE` | 仅 `fallbackWhenWindowInactive = true`（背景亚克力语义） | 否（焦点频繁切换，不反复重建） |
| `NO_BACKDROP` | 没有会话 / 来源、会话已关闭，或会话表面数超出预算 | — |
| `BACKDROP_UNAVAILABLE` | 绑定连续 120ms 没有采到帧（预算超限、首帧未到、会话暂停等） | 否（继续探测，恢复后淡入） |

- 回退与恢复都走 167ms 交叉淡化；`reduceMotion` 或系统关闭动画时立即切换。
- 获得采样帧立即恢复，丢失需要 120ms 连续无帧，避免单帧节流造成闪烁；静止页面也会用一次定时重绘确认丢失。
- 这些都是**修饰性失败**：只影响本控件，不上报材质失败，不改变页面（`ENGINEERING_RULES.md` §6）。
- 窗口失焦时是否保留亚克力，还取决于宿主会话：`LumenSurfaceSessionOptions.pauseWhenWindowUnfocused` 默认开启，失焦时会话暂停采样，控件因此按 `BACKDROP_UNAVAILABLE` 回退，重新获焦后淡回。需要弹窗盖住时仍显示应用内亚克力，请把会话的该选项关闭。

## 4. 层级

| `LumenFluentElevation` | Fluent 值 | Android 阴影高度 | 默认圆角 |
|---|---|---|---|
| `LAYER` | 1 | 0.25dp | 4dp |
| `CONTROL` | 2（按下 1） | 0.5dp（按下 0.25dp） | 4dp |
| `CARD` | 8 | 2dp | 8dp |
| `TOOLTIP` | 16 | 4dp | 8dp |
| `FLYOUT` | 32 | 8dp | 8dp |
| `DIALOG` | 128 | 24dp（上限） | 8dp |

- 换算为 1 个 Fluent 单位 = 0.25dp，使 Card≈Material 卡片 2dp、Flyout≈Material 菜单 8dp；Dialog 封顶在 Material 对话框的 24dp。
- 阴影使用平台 `View.elevation` 与 Drawable 轮廓，不画模糊位图，也不需要父容器放开裁剪；阴影仍受父容器边界裁剪。
- API 28+ 按主题设置阴影颜色：深色不透明、浅色 0xB3，对应 Fluent“同一高度在深色主题下更明显”。
- 描边：浅色主题与 Accent 描边在**底部** 3dp 内由深到浅（`ScaleY=-1`），深色主题在**顶部** 3dp 内由亮到暗；禁用态为单色描边。

## 5. 接入

```kotlin
val session = LumenSurfaceSession(context, palette)               // 宿主持有
val flyout = LumenFluentControl(panel, palette,
    LumenFluentControlOptions(elevation = LumenFluentElevationOptions(level = LumenFluentElevation.FLYOUT)),
    session, content)                                              // content 是 panel 背后的内容，且不包含 panel
val button = LumenFluentControl(view, palette,
    LumenFluentControlOptions(acrylic = LumenAcrylicOptions(enabled = false),
        elevation = LumenFluentElevationOptions(level = LumenFluentElevation.CONTROL)))

// 主题变化
session.updatePalette(newPalette); flyout.updatePalette(newPalette)
// 控件在没有布局的情况下移动（动画、手动滚动）
flyout.notifyPositionChanged()
// onStart / onStop：会话的 resume / pause 由宿主负责
// onDestroy：先关控件，再关会话
flyout.close(); button.close(); session.close()
```

- 全部入口只能在主线程调用；`close()` 之后的迟到调用为空操作。
- `close()` 只恢复仍由自己持有的东西：背景仍是自己的才换回原背景（保留内边距），`elevation` 与阴影颜色未被宿主改过才还原。
- 来源规则与 `LumenSurfaceSession.bind` 相同：**来源不得包含控件本身**。多个控件采样同一来源 View 时共用一次录制。
- 背景会滚动时，在滚动回调里调用 `session.notifyScrollPositionChanged(scrollView)`。来源可以是滚动容器本身，也可以是它的父容器；1.4.3 及更早版本直接以滚动容器为来源时，模糊背景会停在滚动前的位置，升级前请改用父容器作为来源。
- 文字颜色由宿主负责。Fluent 不建议在亚克力上放强调色文字或超链接；`reduceTransparency` 时控件为不透明回退色。
- 焦点双环只在非触摸模式（键盘、方向键）下显示，画在控件边界内侧；输入框等自带焦点样式的控件可关闭 `focusVisualEnabled`。

## 6. 验证

- `FluentAcrylicPolicyTest`：配方数值逐项按 WinUI 公式手算；修正系数、亮度推导、默认值、回退优先级、过渡打断、可用性滞回、参数边界。
- `FluentControlContractTest`：噪点预算与确定性、不回收共享位图、`BlendMode` 只出现在 API ≥ 29 的隔离类、绘制路径零分配、先画不透明底再画背景、录制期间不画绑定、只恢复自己持有的状态、监听都有移除路径、预算超限局部降级、同来源共用录制。
- `AcrylicBackdropContractTest` 和 `AcrylicBackdropColorTest`：守住内部保色接线与旧公开采样 ABI；设备像素对照关闭额外色调／亮度／噪点的亚克力和普通 FROSTED，检查两种浅色、主题／选项更新和重绑。软件在所有 smoke API 必测，GPU 在 API 31+ 必测；运行结果以对应版本发布审阅和验证附件为准。
- `sample` 的“表面沙盒 → Fluent 亚克力与层级”页面可以手动切换全部开关并查看诊断。
- `FluentControlIntegrationTest`（sample 设备测试，CI 在 API 27/31/33/34 模拟器运行）：安装/关闭只恢复自己持有的背景、内边距与高度；按压降低高度；回退原因与混合模式选择；降低透明度时为不透明回退色；背景采样后淡入亚克力；单色描边保持令牌透明度。
- 1.4.3 发布前在 OPPO PMA110（Android 17 / API 37，Adreno）上运行以上 6 项全部通过，并人工核对浅色、深色、降低透明度、BASE/ACCENT 与滚动跟随画面；后端为 GPU、混合为 EXACT。
- 其他厂商 GPU 的高级混合模式、阴影观感与真实功耗仍需在目标设备上验收；混合异常时可对该控件设置 `exactBlend = false`。
