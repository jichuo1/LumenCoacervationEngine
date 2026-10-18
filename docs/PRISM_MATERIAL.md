# Prism 棱镜玻璃与液态选中框（自 1.5.0）

Prism 是一种可插拔的**控件级**液态玻璃材质，与 Fluent 亚克力、Mica 云母并列，配方取自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（commit `65ab177e`，Apache-2.0）。它有两个独立入口：

| 入口 | 模块 | 作用 |
|---|---|---|
| `LumenPrismGlass(view, palette, options, session, source)` | lumen-engine | 给任意 View 装上棱镜玻璃背景：饱和度 → 模糊 → 圆角矩形边缘折射（可选色散）→ 表面色 → 按压光晕 → 内阴影 → 边缘高光，外加外阴影 |
| `LumenNavigationBar.setPrismSelection(options)` | lumen-motion | 底栏选中框改用参考库 LiquidBottomTabs 的液态选中胶囊；`null` 回到经典选中框 |

**命名**：高级材质（`SkinId.LIQUID`，存储值 `liquid_v1`，界面"高级材质"）与 `LumenSurfaceMaterial.LIQUID` 已占用 "Liquid"。本材质统一叫 **Prism / 棱镜**，不新增 `SkinId`，不改变持久化协议，也不参与窗口材质的健康确认。两个入口互不依赖：棱镜选中框可以放在柔光、高级材质或棱镜玻璃的栏上。

## 1. 玻璃配方与默认值

默认值取自参考库底栏容器（`LiquidBottomTabs.kt`）。每一层都可单独关闭或调参，关闭某层只少画那一层。

| 层 | 选项 | 默认值 | 范围 | 平台 |
|---|---|---|---|---|
| 饱和度 | `blur.saturation` | 1.5（Rec.709 权重 0.213 / 0.715 / 0.072） | 0–2 | API 31+ |
| 模糊 | `blur.enabled` / `blur.radiusDp` | 开 / 8dp | 0–48dp | API 31+ GPU；27–30 走会话软件模糊 |
| 折射 | `lens.refractionHeightDp` / `lens.refractionAmountDp` | 24dp / 24dp | 0–64dp | API 33+ |
| 纵深 | `lens.depthEffect` | 0 | 0–1 | API 33+ |
| 色散 | `lens.dispersion` | 0（参考库底栏未用；选中框默认 1） | 0–1 | API 33+ |
| 表面色 | `surface.tintColor` / `surface.tintOpacity` | 浅色 #FAFAFA、深色 #121212 / 40% | 0–1 | 全部 |
| 回退色 | `surface.fallbackColor` / `surface.fallbackOpacity` | 中性底上叠 86% 表面色 | 0–1 | 全部 |
| 高光 | `highlight.widthDp` / `opacity` / `angleDegrees` / `falloff` | 0.5dp / 0.5 / 45° / 1 | 0–4dp / 0–1 / ±360° / 0–8 | 角度分布 API 33+，以下为均匀细边 |
| 内阴影 | `innerShadow.enabled` / `radiusDp` / `opacity` | 关 / 24dp / 15% | 0–48dp / 0–0.6 | 全部 |
| 外阴影 | `shadow.radiusDp` / `offsetYDp` / `opacity` | 24dp / 4dp / 10% 黑 | 0–48dp / ±16dp / 0–0.5 | API 28+ |
| 按压 | `press.glowOpacity` / `press.washOpacity` | 15% / 8% 白（加色） | 0–0.5 / 0–0.3 | 全部 |

折射剖面（与上游同式）：只在距边缘 `refractionHeight` 的带内偏移取样，`d = (1 − sqrt(1 − x²)) · amount`，`x = 1 − 距边缘 / height`，向内取样；法线来自圆角矩形距离场（圆角半径放大 1.5 倍以柔化）。色散按 `x·y / (半宽·半高)` 分布（轴线上为 0），七次取样合成。

**与上游的差异**：只用一个圆角半径并在 shader 内钳到短边一半（上游按未居中坐标选角，混合圆角会选错角）；零向量处不做 normalize（上游在中心线与拐点产生 NaN）；色散为 0–1 连续量，合成结果钳到合法预乘颜色；折射量钳到短边一半，取样不越出录制节点。

## 2. 深浅色

| 项 | 浅色 | 深色 | 说明 |
|---|---|---|---|
| 判定 | `palette.background` 亮度 ≥ 0.5 | < 0.5 | 与 Fluent、Mica、Activity 委托一致（高级材质按 `surface` 判定，注意自定义调色板两者不一致时的差异） |
| 表面色 | #FAFAFA @ 40% | #121212 @ 40% | 参考库值；`updatePalette` 即时生效，无需重建 |
| 回退 | #FAFAFA | #121212 | 不透明 |
| 高光 / 按压光 | 白色加色 | 同左 | 与参考库一致：浅色内容上几乎不可见，浅色主题里玻璃边缘主要靠外阴影 |
| 选中框静止底 | 宿主 SELECTION 背景 | 同左 | Demo 用 `NavigationBarSelectionMask`（浅色深罩、深色浅罩），强度仍由「选中遮罩强度」控制 |

## 3. 接入

```kotlin
val session = LumenSurfaceSession(activity, palette)
val glass = LumenPrismGlass(card, palette, LumenPrismOptions(cornerRadiusDp = 28f), session, content)
scroll.setOnScrollChangeListener { v, _, _, _, _ -> session.notifyScrollPositionChanged(v) }
// 主题切换：session.updatePalette(p); glass.updatePalette(p)
// 销毁：glass.close(); session.close()

navigationBar.setPrismSelection(LumenPrismSelectionOptions())   // null = 经典选中框
```

- `source` 不得包含玻璃 View（与 `LumenSurfaceSession.bind` 相同规则）；没有会话时显示不透明回退色，诊断原因为 `NO_BACKDROP`，会话表面数已满为 `BUDGET_EXCEEDED`。
- 外阴影画在玻璃边界之外：祖先**必须** `clipChildren = false`；**不得**再用 `LumenControlShadow` 包一层（阴影叠两次），也不要给同一 View 同时装 `LumenFluentControl`（同一背景只能有一个所有者）。
- 玻璃本身就是 View 背景（不包装层），`close()` 只在背景仍是自己时恢复原背景。按压反馈来自背景的 `state_pressed` 与热点，不安装触摸监听、不改 View 缩放；缩放交给引擎的长按弹性。
- 底栏用棱镜玻璃做栏体时，直接 `LumenPrismGlass(navigationBar, …)`：它替换栏的 BAR 背景，栏的手势与选中框不受影响。

## 4. 液态选中框（`LumenPrismSelectionOptions`）

| 选项 | 默认值 | 范围 | 来源 |
|---|---|---|---|
| `pressedScale` | 78 / 56 | 1–1.8 | 参考库 `pressedScale` |
| `contentMagnification` | 1.2 | 1–1.6 | 参考库按压时选中项放大 |
| `lensHeightDp` / `lensAmountDp` | 10 / 14（× 按压进度） | 0–32 | 参考库 `lens(10dp·p, 14dp·p)` |
| `dispersion` | 1 | 0–1 | 参考库 `chromaticAberration = true` |
| `velocityDeformation` | 1 | 0–2 | 横向 ÷(1 − 0.075v)、纵向 ×(1 − 0.025v)，各限 ±0.2 |
| `highlightOpacity` / `shadowOpacity` / `innerShadowOpacity` / `pressedWashOpacity` | 0.5 / 0.1 / 0.15 / 0.03（× 按压进度） | 见 KDoc | 参考库 `Highlight.Default` / `Shadow` / `InnerShadow` / 3% 暗色 |
| `scaleXSpring` / `scaleYSpring` | 刚度 250，阻尼比 0.6 / 0.7 | `LumenSpringSpec` | 两轴不同阻尼形成果冻感 |

行为：

- **静止**：选中框只显示宿主 SELECTION 背景；框内的图标与标签用选中色绘制，框外用常规色——选中色严格以选中框边缘为界，随滑动逐像素过渡（参考库"强调色副本"的效果）。
- **按下 / 拖动 / 轻点**：选中框膨胀并果冻回弹；框内内容放大、边缘折射与色散（API 33+）；出现边缘高光、外阴影、内阴影与 3% 暗色，静止背景随之淡出。只有用户拖动或回弹时的速度参与挤压拉伸，翻页器滑动不会挤压选中框。
- 实现：每一项的图标与标签按当前位置画进一张 ALPHA_8 遮罩（≤ 512 KiB，几何变化时重建，用不透明、无阴影的画笔，可读性光晕不进入副本），框内用选中色画遮罩；框外的真实项被 `clipOutPath` 裁掉。外阴影是预先模糊的胶囊纹理（≤ 256 KiB），逐帧只拉伸，不做模糊。
- 代价：选中框运动时栏会逐帧重录显示列表（副本与裁剪在栏的显示列表里），栏背景随之重画一次；静止时零工作。帧源只推进选中框，不更新触点光晕、不回调 `onVisualMovement`。

## 5. 诊断

`LumenPrismGlass.diagnostics()` 返回 `LumenPrismDiagnostics(closed, glassVisible, fallbackReason, backdropBackend, lensActive, dark, draws)`：`lensActive` 为 true 表示折射在 GPU 效果链里（API 33+、折射开启、采样走 GPU）。`LumenNavigationBar.isPrismSelectionEnabled` 报告选中框模式。

## 6. 验证

- JVM：`PrismPolicyTest`（深浅色默认值、回退色、饱和度矩阵、折射剖面、法线与高光强度、半径与预算）、`PrismContractTest`（隔离、绘制零分配、上游署名、会话链、阴影与背景所有权）、`NavigationBarPrismTest`（速度挤压、遮罩与阴影预算、经典路径不变、帧源不碰光晕与宿主、遮罩只取覆盖度）。
- 设备：`PrismIntegrationTest`——两种深浅色下的回退色与"背景 ⊕ 40% 表面色"像素合成、`close()` 恢复背景、经典选中框在棱镜往返后逐像素不变、选中色只出现在选中框内。
- Demo：「排布 → 局部视效调节实验室 → Prism 棱镜玻璃与液态选中框」提供全部开关与滑块、深浅色与浅色背景内容切换；设置页「棱镜选中框（重建）」把 Demo 底栏切到液态选中框。
