# 材质浓度：所有控件通用的透明度无极调节

> 状态：**已实现，随 1.5.6 发布**。§0–§11 是设计；实现时的取舍与设计稿的差异见 **§12**，模糊半径随浓度变化的混合方案见 **§13**（它取代 §12 第一行的"固定半径淡出"），公开声明以 `API.md` §23 为准。
>
> 相关档案：`ARCHITECTURE.md`（分层）、`ENGINEERING_RULES.md`（§2 隔离类、§5 分配、§8 测试）、`VISUAL_EFFECTS.md`（局部增强与三档输入）、`FLUENT_MATERIAL.md`、`MICA_MATERIAL.md`、`PRISM_MATERIAL.md`。

## 0. 结论

- 新增一个跨材质的连续参数 **材质浓度 `p ∈ [0, 1]`**：调节材质本身的厚薄，而不是把整个控件淡掉。
- **`p = 1` 与当前默认外观逐位相同**，默认值就是 1，现有宿主不受影响。
- 每种材质把 `p` 映射到自己的各个通道（模糊、色调、亮度、散射、折射、高光、阴影），曲线写在纯策略 `MaterialOpacityPolicy` 里，JVM 单测覆盖。
- 逐帧改变 `p` 只更新 uniform 和 `Paint.alpha`，不重建 `RuntimeShader` / `RenderEffect` / 位图，不走 `update(options)`。
- 文字和图标永远不跟着 `p` 变淡；带文字的表面受可读性下限保护；降低透明度时强制为不透明。

## 1. 背景：现状为什么"调不了透明度"

| 现象 | 位置 | 后果 |
|---|---|---|
| 各 Drawable 的 `setAlpha` 都是把每一层颜色等比乘 alpha | `FluentControlDrawable`、`PrismGlassDrawable`、`MicaBackdropDrawable`、`LiquidSurfaceDrawables`、`FrostedMaterialRenderer`、`LumenSurfaceSession` 内部 Drawable | 只能把整个控件淡掉，是"可见度"，不是"透明度" |
| Fluent 的模糊背景不跟随 alpha | `FluentControlDrawable.draw`：回退色乘了 `alphaValue`，`binding.draw(canvas)` 照常满强度绘制 | alpha 只调淡色调，控件始终是一块不透明的模糊 |
| 就算全部等比乘上，也会出现重影 | 所有采样型材质 | 模糊副本变成半透明后叠在真实清晰内容上，形成双重影像，文字一起变淡 |
| `update(options)` 代价高 | `LumenPrismGlass.update` 会经 `updatePrism` 重建 GPU 玻璃；`LumenFluentControl.update` 会重配方，模糊半径变化时还会更新绑定 | 拖滑块时逐帧调用违反 `ENGINEERING_RULES.md` §5 |
| 参数分散、语义不一 | `LumenSurfaceOptions.opacity / backdropOpacity / tintOpacity`；Fluent、Mica、Prism 各自的 `tintOpacity`；`LumenSlider`、`LumenNavigationBar`、`LumenSegmentScrubBar` 没有；`ElasticInteractionController` 的 `setAlpha` 是空实现 | 宿主没有一个统一入口 |

## 2. 设计原则

### 2.1 三条互不干扰的透明度通道

| 通道 | 归属 | 语义 | 本设计是否改动 |
|---|---|---|---|
| 材质浓度 `p` | 引擎 | 材质的厚薄：0 接近清玻璃（只剩边缘），1 为当前默认外观 | 新增 |
| `View.alpha` / `Drawable.setAlpha` | 动效与宿主 | 整个控件出现、消失 | 不改语义 |
| 内容（文字、图标、子 View） | 宿主 | 前景 | 引擎不触碰 |

最终画面 = 内容 + `setAlpha × 材质(p)`。两者相乘但职责分开：动效淡出用 `setAlpha`，用户调"透明度"用 `p`。

### 2.2 统一的消退顺序

`p` 从 1 降到 0 时，所有材质按同一顺序消退：

1. **填充**：色调层、亮度层、表面色；
2. **漫射**：模糊、散射、噪点；
3. **投影**：外阴影、内阴影、层级阴影；
4. **轮廓**（最后，并设下限）：边缘高光、折射边、色散描边、层级描边。

这样 `p` 很低时控件依然看得出是个控件。按压、悬停、焦点等**交互反馈不随 `p` 衰减**。

### 2.3 兼容与确定性

- 所有映射满足 `f(1) = 1`（乘到现有默认值上即原值），`p = 1` 时短路，直接走现有绘制路径，保证逐位相同。
- 所有映射在 `[0, 1]` 上单调不减、连续，端点精确。
- `p` 拒绝 NaN、无穷，超出范围的值截断到 `[0, 1]`（与现有 options 的 `require` 风格不同：`p` 是逐帧输入，截断比抛异常更安全；options 中的静态默认值仍用 `require`）。

## 3. 曲线库

`MaterialOpacityPolicy` 只用以下几种形状，常量逐材质配置：

| 名称 | 公式 | 用途 |
|---|---|---|
| `linear(p)` | `p` | 色调等线性量 |
| `pow(p, γ)` | `p^γ` | γ > 1 前段快退（阴影、散射）；γ < 1 后段快退 |
| `haze(p, k)` | `(1 − e^(−k·p)) / (1 − e^(−k))` | 雾气浓度（Beer-Lambert），低浓度时仍有可感知的雾 |
| `floorMix(p, f)` | `f + (1 − f)·p` | 轮廓类通道，保底 `f` |
| `gate(p, a, b)` | `smoothstep(a, b, p)` | 某个通道只在后段才开始退（亚克力的模糊） |
| `clearing(p)` | `1 − (1 − p)⁴` | 固定半径模糊样本的背景权重：中段保持浓厚、接近 0 时才散开（实现时新增，见 §12） |

`p` 本身对用户是"感知线性"的：在 `MaterialOpacityPolicy.perceived` 中再做一次可选的感知校正（以背景平均亮度与材质色调的 OKLab ΔL 为度量），首版先用恒等映射，校正作为后续调参项（§11）。

## 4. 各材质映射表

表中"乘数"乘在该通道当前默认值上；"现有来源"指现在的默认值从哪里来。

### 4.1 柔光（Frosted）——雾气散开

| 通道 | 现有来源 | 乘数 `f(p)` |
|---|---|---|
| 色调（`palette.background` 着色） | `FrostedMaterialRenderer`、`LumenSurfaceOptions.tintOpacity` | `haze(p, 2.4)` |
| 模糊权重 | 静态磨砂 / 软件透镜采样 | `clearing(p)` |
| 边缘（1px） | `edgeIntensity` | `0.4 + 0.6·clearing(p)`（始终不低于雾与磨砂，见 §12） |
| 投影 | 表面阴影 | `pow(p, 1.5)` |

雾本身就是"浓度"，所以柔光是唯一可以**直接降低采样层 alpha** 的材质：采样层是低分辨率的模糊结果，淡出时看起来像雾散开，不会形成锐利的重影。

### 4.2 液态（Liquid，高级材质）——磨砂玻璃变成清玻璃

| 通道 | 现有来源 | 乘数 `f(p)` |
|---|---|---|
| 色调 | `LiquidLegibilityTuning.tintAlpha` 的结果 | `linear(p)`（叠加可读性下限，见 §6） |
| `scatteringStrength` | `LiquidTokenResolver`（实时档 0.18，标清档 0） | `pow(p, 2)` |
| `scatteringRadiusDp` | 同上（实时档 4dp） | `linear(p)` |
| `refractionAmount` | 令牌 | `floorMix(p, 0.5)` |
| `specularStrength` / `fresnelStrength` | 令牌 | `floorMix(p, 0.6)` |
| `chromaticShift` | 令牌 | `floorMix(p, 0.5)` |
| 投影 | 表面阴影 | `pow(p, 1.5)` |

清玻璃在边缘仍然会弯折光线，所以折射和菲涅尔只降到一半左右。标清档的散射原本就是 0，`p` 只影响色调、折射和高光。

### 4.3 亚克力（Fluent Acrylic）——沿用 WinUI 的两个旋钮

| 通道 | 现有来源 | 乘数 `f(p)` |
|---|---|---|
| 亮度层（Luminosity） | `FluentAcrylicPolicy.luminosityColor` | `linear(p)` |
| 色调层（Color） | `FluentAcrylicPolicy.effectiveTint`（含 TintOpacity 修正） | `pow(p, 1.2)` |
| 模糊权重 | 会话只绘制绑定（默认 48dp） | `gate(p, 0, 0.35)` |
| 噪点（2%） | `noiseOpacity` | `pow(p, 0.5)` |
| 不透明回退底 | `recipe.fallback` | 随模糊权重一起退，见下 |
| 层级描边 | `ControlElevationBorderBrush` 令牌 | 不变 |
| 层级（`View.elevation`） | `FluentElevationPolicy` | `floorMix(p, 0.5)` |

关键改动：现在"不透明回退底 → 模糊背景"的叠法保证模糊不和透明混合；`p < 1` 时，回退底与模糊背景要**一起**按模糊权重让位给"不画任何东西"（控件背景本来就透明，真实内容自然可见），而不是只降回退底的 alpha（现状的 bug）。亮度层和色调层在 `p ≥ 0.35` 区间先退，模糊在 `p < 0.35` 后才开始退。

### 4.4 云母（Mica）——壁纸透出程度，始终不透明

| 通道 | 现有来源 | 乘数 `f(p)` |
|---|---|---|
| 静态材质图权重 | `MicaBackdropDrawable` 的 `imagePaint.alpha = weight × alpha` | `linear(p)` |
| 不透明底（`recipe.fallback`） | 同上 | 不变，始终满不透明 |

云母按定义是窗口基础层，**`p` 不允许透出窗口后面的内容**：`p = 0` 为纯色，`p = 1` 为当前配置（`BASE` 或 `BASE_ALT`）的默认外观，输出永远不透明，`getOpacity` 在 `setAlpha = 255` 时继续返回 `OPAQUE`。"从 Base 推到 Alt"不在本设计范围（会破坏 `p = 1` 兼容），见 §11。

### 4.5 棱镜（Prism）——玻璃厚度

| 通道 | 现有来源（`PRISM_MATERIAL.md` §1） | 乘数 `f(p)` |
|---|---|---|
| 表面色 `surface.tintOpacity` | 默认 40% | `linear(p)` |
| 饱和度 `blur.saturation` | 默认 1.5 | `1 + (s − 1)·p`（向 1 收敛，不是乘数） |
| 模糊 `blur.radiusDp` | 默认 8dp | `clearing(p)`：采样玻璃按权重淡出（阶梯未采用，见 §12） |
| 折射 `lens.refractionAmountDp` | 默认 24dp | `floorMix(p, 0.5)` |
| 色散 `lens.dispersion` | 默认 0 | 不变 |
| 高光 `highlight.opacity` | 默认 0.5 | `floorMix(p, 0.6)` |
| 内阴影 `innerShadow.opacity` | 默认关 / 15% | `linear(p)` |
| 外阴影 `shadow.opacity` | 默认 10% | `pow(p, 1.5)` |
| 按压 `press.glowOpacity` / `washOpacity` | 15% / 8% | 不变（交互反馈） |

棱镜选中框（`LumenPrismSelectionOptions`）只跟随外阴影、内阴影和高光三项，选中色遮罩不变。

### 4.6 普通外观与控件阴影

| 对象 | 通道 | 乘数 `f(p)` |
|---|---|---|
| `LumenSlider` 的 `PLAIN` 外观、底栏与档位条的经典选中框 | 填充 alpha | `linear(p)` |
| 同上 | 描边 | `floorMix(p, 0.4)` |
| `LumenControlShadow` | `opacity` / `contactOpacity`（`ControlShadowPolicy.adaptAlphas` 之后） | `pow(p, 1.5)` |

阴影强度本来就是逐帧乘在 `Paint.alpha` 上（`ControlShadowDrawable` 已有 `gain`），纹理不含透明度，所以改 `p` 不会让 `ControlShadowTextureCache` 换出纹理。控件阴影的 `p` 默认跟随所附控件的材质浓度，见 §7.3。

### 4.7 滑块的五种外观

`LumenSlider` 内部按 `SliderKind { SKIN, PLAIN, FLUENT, MICA, PRISM }` 分派，`p` 直接复用 4.1～4.6 对应材质的映射，`SKIN` 跟随当前 Activity 的皮肤（柔光或液态）。HDR 高光（`SliderHdrHighlightApi34`）属于交互反馈，不随 `p` 衰减。

## 5. 后端与性能

**总原则：`p` 的逐帧变化只写 uniform 与 `Paint` 字段。** 下表是每条后端上"模糊程度连续变化"的实现方式：

| 后端 | 模糊的连续变化 | 其他通道 |
|---|---|---|
| API 33+ 直接 AGSL（`DirectSurfaceProgramApi33`） | 已有 `clearInput / weakInput / strongInput` 三档输入的权重混合；新增一个 `materialBlur` uniform，按权重在三档间插值 | uniform |
| API 33+ 液态折射（`LiquidRefractionBackendApi33`） | `scatteringStrength` / `scatteringRadius` 已是 uniform | uniform |
| API 33+ 棱镜（`PrismLensApi33` / `PrismHighlightApi33`） | 模糊在折射之前由 `RenderEffect` 完成，走下面的阶梯 | uniform |
| API 31–32 `RenderEffect` 模糊（`LiquidBlurBackendApi31`、`GlowChromeGlassApi31`、Prism 31+） | ~~阶梯~~（未采用，见 §12）：固定半径的模糊样本按背景权重淡出 | `Paint` |
| API 27–30 / 软件透镜 | 软件路径只有一档模糊结果；模糊权重低于 1 时，在模糊样本与"不绘制"之间按权重淡化（柔光天然合适；其他材质此时以色调和轮廓为主） | `Paint` |
| `TRANSLUCENT` / `STATIC` | 无模糊 | `Paint` |

预建阶梯要计入现有的 `RenderEffect` 数量预算，并与 `glow/FilteredLayerRefresh`（`ENGINEERING_RULES.md` §13）共用"预建别名、切换不新建"的写法：4 档 × 2（零偏移别名）= 每个绑定最多 8 个效果对象，只在建立或半径配置变化时创建。

**低浓度时省掉采样（省电）**：当某个表面的模糊权重为 0、色调乘数低于 1/255 时，它已经不需要背景样本——控件背景透明，真实内容本来就可见。这时：

- 局部视效会话：该绑定不再请求录制或节点采样，来源组按现有的依赖与代次机制处理；
- 液态实时档：该表面从反馈抑制与截图区域判定中移除（与 `dc2d9f5` 的"按玻璃区域判定截图变化"一致）；
- 浓度回升时按"来源暂时不可用 → 恢复"的现有路径补一次采样，期间显示上一帧样本或仅轮廓。

**拖动期间不分配**：`setMaterialOpacity` 只写一个 `Float` 并 `invalidateSelf()`；`p` 相同则直接返回。

## 6. 可读性与无障碍

1. **可读性下限**：表面声明"承载文字"（默认：`SurfaceRole` 中的 `CARD / MODAL / TOP_BAR / FLOATING / CHIP / FILLED_BUTTON / TEXT_BUTTON`，以及 Fluent、Prism 控件；`WINDOW / SELECTED_ITEM / MOTION_SURFACE` 默认不受下限约束）时，实际使用的浓度为 `max(p, floor)`。`floor` 由 `GlowLegibilityPolicy.target` 的同一套对比度模型反推：给定内容探针的亮度样本、宿主前景色和 WCAG 4.5 目标，求出满足对比度所需的最小色调，再换算成 `p`。没有探针时使用逐材质的静态下限（实现值：柔光 0.30、液态 0.12、亚克力 0.30、棱镜 0.30、普通 0.20、云母 0）。
2. **装饰性表面**可以通过 `legibility = DECORATIVE` 关闭下限。
3. **降低透明度**（各 options 的 `reduceTransparency`、`LumenMaterialRecipe` 中的 `OPAQUE_ACCESSIBLE`）优先级最高：忽略 `p`，按现有规则显示不透明回退色。
4. **高对比度**：沿用 Mica、Fluent 的现有回退，`p` 不生效。
5. 下限只保证色调足够，**不保证**文字颜色正确；前景色仍由宿主根据 `GlowLegibilityPolicy.foreground` 等自己处理（与现有分工一致）。
6. 诊断中报告"请求值"与"实际值"，方便宿主判断下限是否生效。

## 7. API 草案

### 7.1 统一接口

```kotlin
package com.lumen.coacervation.engine.host

/** 材质浓度：0 接近清玻璃，1 为默认外观。只改变材质，不改变内容与整体可见度（View.alpha）。 */
public interface LumenMaterialOpacityTarget {
    /** 主线程；逐帧可调用，不分配、不重建着色器。超出 [0, 1] 截断，NaN 忽略。 */
    @MainThread public fun setMaterialOpacity(value: Float)
    @MainThread public fun materialOpacity(): Float
    /** 可读性下限与降低透明度生效后的实际值。 */
    @MainThread public fun effectiveMaterialOpacity(): Float
}

public enum class LumenMaterialLegibility { AUTO, TEXT, DECORATIVE }
```

实现者（均为现有公开类型，新增方法，属于 1.x 次版本的兼容新增）：

| 类型 | 模块 |
|---|---|
| `LumenFluentControl`、`LumenPrismGlass`、`LumenMicaBackdrop`、`LumenControlShadow` | lumen-engine |
| `LumenSurfaceBinding` | lumen-engine |
| `LumenSlider`、`LumenNavigationBar`、`LumenSegmentScrubBar`、`LumenFusedSelection` | lumen-motion |

~~各 options 增加一个静态初值~~：未采用。给公开 data class 追加字段会改变构造与 `copy` 的二进制签名，因此只提供 `setMaterialOpacity` 方法，Fluent、Prism 与表面绑定另有 `setMaterialOpacity(value, legibility)` 重载（§12）。

`LumenSurfaceOptions` 已有的 `opacity` 与 `backdropOpacity` 保持原义不变（等比 alpha 与采样层 alpha），文档中标注"连续调节请使用 `setMaterialOpacity`"，不做破坏性改名。

### 7.2 Activity 皮肤表面

宿主拿到的 `modalBackground` / `motionSurfaceBackground` / `floatingBackground` 是不公开实现类的 Drawable，所以由委托提供按角色设置的入口：

```kotlin
public class LumenActivityDelegate {
    @MainThread public fun setMaterialOpacity(role: SurfaceRole, value: Float)
    @MainThread public fun setMaterialOpacity(value: Float)   // 全部角色
}
```

会话把它转交给 `GlowEngine` 的一个默认空实现方法（`ARCHITECTURE.md` §3 第 2 条：能力差异用默认空实现表达）：

```kotlin
public interface GlowEngine : AutoCloseable {
    // ……现有方法……
    public fun setMaterialOpacity(role: SurfaceRole, value: Float) {}
}
```

柔光与液态两个渲染器各自实现；第三方 `GlowEngine` 不实现时等同于恒为 1。`GlowEngine` 是公开契约，给它加带默认实现的方法时要确认 Kotlin 接口默认方法的编译方式（`-Xjvm-default`）对 Java 实现者也是二进制兼容的，否则就需要按 `VERSIONING.md` 评估（见 §11）。

### 7.3 作用域：一个值驱动一组控件

```kotlin
package com.lumen.coacervation.engine.motion   // lumen-motion

public class LumenMaterialOpacityScope(initial: Float = 1f, stiffness: Float = 260f, dampingRatio: Float = 0.92f)
    : LumenMaterialOpacityTarget, AutoCloseable {
    /** 立即设定，取消进行中的过渡。 */
    @MainThread public fun set(value: Float)
    /** 用 LumenSpring 过渡；反转从当前值和当前速度续跑（ARCHITECTURE.md §11.2）。 */
    @MainThread public fun animateTo(value: Float)
    @MainThread public fun register(target: LumenMaterialOpacityTarget, base: Float = 1f)
    @MainThread public fun unregister(target: LumenMaterialOpacityTarget, restore: Float = 1f)
    override fun close()
}
```

- 实际浓度 = 作用域值 × 目标自身值，作用域可以嵌套（相乘）。
- 不遍历视图树：目标显式注册，逐帧只遍历注册表。
- `LumenControlShadow` 默认跟随它所附着控件的有效浓度；宿主也可以给它单独设值。
- 降低动态效果或系统关闭动画时，`animateTo` 等同于 `set`。

## 8. 内部结构

```
MaterialOpacityPolicy（纯 Kotlin，model/ 或 runtime/）
   ├ curves：linear / pow / haze / floorMix / gate
   ├ frame(kind, p, defaults) → MaterialOpacityFrame（各通道乘数，原始 float，不分配：写入调用方持有的可复用对象）
   ├ legibilityFloor(kind, sample?, foreground, target) → Float
   └ blurStep(weight, previousStep) → Int（阶梯与滞回）
各 Drawable / 渲染器
   └ 持有一个 MaterialOpacityFrame；setMaterialOpacity 时重算一次，draw 只读
```

- 策略对象不引用 `android.graphics`（`ARCHITECTURE.md` §3 第 3 条、`ENGINEERING_RULES.md` §8），颜色以 `Int` 表示。
- `BlendMode`、`RenderEffect`、`RuntimeShader` 的使用仍只在现有隔离类里，不新增高版本类型；如需新增 uniform，只改既有 `*Api33` 类的着色器源码与 `setFloatUniform` 调用。

## 9. 测试计划

### 9.1 JVM 策略测试（`MaterialOpacityPolicyTest`）

| 用例 | 断言 |
|---|---|
| 端点 | 每种材质、每个通道 `f(1) = 1`，`f(0)` 等于表中下限 |
| 单调 | 在 `[0, 1]` 上取 1001 个点，各通道单调不减 |
| 连续 | 相邻采样点的差值不超过阈值（防止分段曲线断崖） |
| 消退顺序 | 对任一 `p`：轮廓乘数（边缘、高光中较小者）≥ 填充、背景、投影、内阴影与散射乘数，即轮廓最后消失（实现时放宽为这一条，见 §12） |
| 输入清洗 | NaN 不改变状态，±∞ 与越界值截断 |
| 云母不透明 | 任意 `p` 下合成结果 alpha = 255 |
| 交互反馈 | 按压、悬停、焦点、HDR 高光的乘数恒为 1 |
| 可读性下限 | 给定亮度样本和前景色，`max(p, floor)` 达到对比度 4.5；`DECORATIVE` 时不抬升；降低透明度时返回不透明 |
| 阶梯滞回 | 在两档边界附近来回抖动 ±0.03 时档位不变 |
| 预算 | 每个绑定预建的 `RenderEffect` 数量 ≤ 8；阶梯不引入新位图 |

### 9.2 源码契约测试（`contract/SourceContract`、`contract/MotionSource`）

- `setMaterialOpacity` 的实现体内不出现 `RenderEffect.create`、`RuntimeShader(`、`Bitmap.createBitmap`、`saveLayer`、`Paint(`、`Matrix(`；
- `draw()` 中只读取 `MaterialOpacityFrame`，不调用 `MaterialOpacityPolicy.frame`；
- Fluent：`p < 1` 时回退底与绑定绘制共用同一个模糊权重（守住 §1 中的现状 bug 不复活）；
- Mica：`getOpacity` 的 `OPAQUE` 分支不依赖 `p`；
- 新增锚点缺失时直接失败；读取的生产文件登记到 `sourceContractInputs`。

### 9.3 设备测试（sample，CI 在 API 27 / 31 / 33 / 34 模拟器）

| 测试 | 内容 |
|---|---|
| `MaterialOpacityPixelTest` | 每种材质在 `p = 1` 时与现有基线截图逐像素相同；`p = 0` 时，在高对比条纹背景上测量控件内的锐度，接近无控件时的锐度（证明没有模糊副本叠加，即无重影） |
| `MaterialOpacityBackendTest` | GPU、软件、`STATIC` 三条路径各跑一遍；API 31 上验证阶梯切换后滤镜结果确实刷新（`FilteredLayerRefresh`） |
| `MaterialOpacitySamplingTest` | `p` 降到采样阈值以下后，诊断中的录制和采样计数停止增长；回升后恢复一次采样 |
| `MaterialOpacityAccessibilityTest` | 降低透明度、高对比度、`DECORATIVE`、可读性下限四种情况的实际值 |
| 既有回归 | `FluentControlIntegrationTest`、`ScrollSourceSamplingTest`、Prism 与 Mica 现有设备测试全部保持通过 |

### 9.4 性能

- 固定场景基准：一屏 5 种材质各 2 个控件，用 `LumenFixedFrameClock` 逐帧把 `p` 从 1 扫到 0 再回到 1。记录 FrameMetrics 的 P50/P95/P99、`RenderEffect` / `RuntimeShader` 构造计数（应为 0 增量）、分配计数（`draw` 与 `setMaterialOpacity` 应为 0）。
- 静止门控：`p` 停止变化后 GPU 帧数回到接近零。
- 真机复核（PMA110 / API 37）：与模拟器数据分开记录，模拟器数字只用于同环境比较。

### 9.5 构建门禁

按 `ENGINEERING_RULES.md` §11 跑完整门禁；只要改动了 `*Api33` 着色器或新增平台类型引用，加跑 §2.4 的 DEX 审计。

## 10. 分阶段落地

| 阶段 | 内容 | 验收 |
|---|---|---|
| 1 | `MaterialOpacityPolicy` 与 JVM 测试；Fluent 修复回退底与绑定绘制的 alpha 不一致（与 `p` 无关，可单独发布） | 9.1 全绿；Fluent 现有设备测试通过 |
| 2 | `LumenMaterialOpacityTarget` 与 lumen-engine 控件：Fluent、Prism、Mica、`LumenControlShadow`、`LumenSurfaceBinding`（AGSL 三档权重 + 软件淡化） | 9.2、9.3 中对应条目 |
| 3 | Activity 皮肤：`GlowEngine.setMaterialOpacity`，柔光与液态渲染器；低浓度停止采样 | `MaterialOpacitySamplingTest`；液态实时档功耗不回退 |
| 4 | API 31 阶梯；lumen-motion 控件与 `LumenMaterialOpacityScope`；Demo 页 | 9.4 基准；API 31 模拟器像素测试 |
| 5 | `API.md`、`INTEGRATION_STANDARD.md`、各材质文档同步；`CHANGELOG.md` | 文档评审 |

## 11. 未决问题

1. **感知校正**：首版 `p` 直接作为曲线输入。是否按背景亮度做 OKLab 校正，让不同背景上"同一个 `p` 看起来一样透"，需要在 Demo 里对比后决定。
2. **云母推到 Alt**：允许 `p > 1`（或单独的 `vibrancy` 参数）把 Base 推向 Alt，会与"`p = 1` 等于默认"的约定冲突，暂不纳入。
3. **作用域与动效淡出叠加**：弹窗入场时 `setAlpha` 与作用域动画同时进行是否需要协调时序，留到第 4 阶段与 `LumenModalPresenter` 一起评估。
4. **软件路径的模糊淡化**：除柔光外，其他材质在 API 27–30 上淡化模糊样本是否可接受（可能有轻微重影），需要在真机上看过再决定，备选方案是软件路径直接用 `gate` 让模糊整段保持到 `p` 很低再一次性退出。
5. **`GlowEngine` 契约扩展的兼容性**：确认当前构建的接口默认方法编译方式；如果不能对 Java 实现者保持二进制兼容，就改为一个独立的可选接口（如 `GlowMaterialOpacityCapable`），由会话做 `is` 判断。
6. **下限静态初值**：§6 的逐材质静态下限来自效果图上的目测，需要用 `GlowLegibilityPolicy` 在典型背景上实际算一遍再定。

## 12. 实现记录与设计稿的差异

实现分五步提交，每步跑完 `:lumen-engine:testDebugUnitTest`（含新增策略与契约测试）后再进入下一步。下表列出与 §0–§11 不同的地方及原因。

| 设计稿 | 实际实现 | 原因 |
|---|---|---|
| §5：API 31–32 预建 4 档模糊阶梯；AGSL 路径在三档输入间插值半径 | 所有路径都在**固定半径**下按背景权重淡出对齐的模糊样本；AGSL 直接路径只把权重乘进现有 `backdropOpacity` uniform，着色器源码不变 | 每档一个 `Glass` 意味着每个表面多 3 个 GPU 图层，显存与功耗都不划算。模糊样本与真实内容逐像素对齐，淡出时读作雾气变薄，不会出现双重影像；双重影像只在样本错位（折射位移）时出现，而折射玻璃保持不透明（见下一行） |
| §4.2：液态的散射、折射、高光按曲线变化 | 折射后端（`LiquidRefractionBackendApi33`）每次 `drawBackdrop` 前写入该表面的散射、折射、色散、镜面与菲涅尔乘数，**玻璃内容不透明度保持不变**；`BLUR` 后端、悬浮栏内容节点玻璃和弹窗的光学直采副本按背景权重淡出 | 清玻璃的观感就是"内部原样、边缘折射"：散射为 0 时着色器跳过散射取样（更省），折射边缘与镜面保留。节点玻璃和光学副本的光学参数烘焙在平台效果里，只能整体淡出 |
| §5：低浓度时停止采样 | 局部表面、Fluent、Prism、柔光的静态磨砂与实时透镜在背景权重为 0 时停止录制与采样；**液态实时档在 p = 0 时仍继续截图** | 液态清玻璃的折射边缘需要背景；这与现状持平，不增加功耗 |
| §4.5：棱镜的折射、饱和度随 p 变化 | GPU 折射与饱和度保持 `LumenPrismOptions` 的配置；色调、采样玻璃、内阴影、外阴影与高光连续变化 | 它们属于平台效果链，只能由 `update()` 重建，不能逐帧改 |
| §3 / §4：柔光模糊权重 `pow(p, 0.7)`，液态、棱镜的背景权重 `linear(p)` | 统一为 `clearing(p) = 1 − (1 − p)⁴`；轮廓改为随背景权重保底（柔光 `0.4 + 0.6·clearing`，液态边缘与棱镜高光 `0.6 + 0.4·clearing`）；柔光、棱镜的文字下限由 0.22 / 0.20 提高到 0.30 | API 34 模拟器实拍：按 `pow(p, 0.7)` 淡出时，p = 0.5 的柔光卡片后面背景正文已清晰透出、压住标签，与效果图出入大。换成 `clearing` 后 0.66 仍是浓厚磨砂，0.33 只隐约透出 |
| §7.1：options 增加 `materialOpacity` / `legibility` 字段 | 只加方法 | 公开 data class 追加字段会破坏二进制兼容（`VERSIONING.md`） |
| §7.1 实现者包含 `LumenFusedSelection`；§4.6 导航栏整体随 p 变化 | 导航栏与档位条只作用于**栏或轨道背景**；选中框、滑块与 `LumenFusedSelection` 不实现 | 选中框表达的是状态，属于内容；把它调到透明会丢失"当前选中哪一项" |
| — | 新增 `LumenMaterialOpacity.set(drawable, value)`：委托生成的柔光、液态表面背景与 `LumenControlShadow` 包裹层可逐个设置，与角色浓度相乘（`MaterialOpacityState` 只在乘积变化时重算） | 导航栏、档位条与滑块的背景来自宿主传入的工厂，按角色统一调节不够细 |
| §6：有内容探针时按 `GlowLegibilityPolicy` 反推下限 | 只实现逐材质静态下限（柔光 0.22、液态 0.12、亚克力 0.30、棱镜 0.20、普通 0.20、云母 0） | 控件没有统一的内容探针来源；按探针反推留作后续（§11 第 6 条仍未决） |
| §4.7：滑块整体随 p 变化 | 槽体与滑钮材质随 p 变化，数值填充、刻度、把手与 HDR 高光不变；普通槽体保留 0.35 的下限 | 填充就是数值本身；空槽是可拖动范围的提示 |
| §9.3 / §9.4：设备像素测试与性能基准 | 尚未编写；本轮只在 API 34 模拟器（google_apis x86_64，SwiftShader）上用 Demo 截图核对 p = 1 / 0.66 / 0.33 / 0 与装饰性 0 | 需要单独排期，在 CI 设备矩阵上跑 |
| — | Fluent 的轮廓 alpha 乘以背景权重，`setMaterialOpacity` 时 `invalidateOutline()` | 实拍发现：亚克力变透明后，平台 elevation 阴影从控件下方整块透出，形成深色方框 |

**与效果图仍有的差距**：

- ~~中低浓度时内容隐约透出清晰轮廓~~：已由 §13 的混合方案解决（GPU 节点玻璃、AGSL 直接路径实时改半径，软件路径停手后改半径）。
- 云母在模拟器上从 1 到 0 变化很小：云母配方本身以不透明底色为主，壁纸透出度只占一小部分。这是云母的设计，不是映射失效（诊断为 `NONE`，材质图已生效）。

`GlowEngine.setMaterialOpacity` 沿用仓库已有的做法（带默认实现的接口方法，1.2.2 起多次使用），契约版本不变；§11 第 5 条按此结论关闭。

## 13. 模糊半径随浓度变化（混合方案）

§12 的固定半径淡出在中低浓度会透出清晰轮廓。先评估了显存与功耗（Demo 页五块玻璃在用的渲染目标约 11.3 MB；原设计"每档一个 `Glass` + 相邻交叉淡化"会让它翻倍），再按路径分别实现：

| 路径 | 做法 | 显存 | 计算 |
|---|---|---|---|
| GPU 节点玻璃（局部表面、Fluent、Prism；`SurfaceCaptureApi31.Glass`） | 半径比例量化为档位（现为半八度 8 档，见 §14）；`GlowChromeGlassApi31` 在几何不变时按档位缓存效果链与 API 31 别名，几何变化才清空；`p = 1` 仍是原来那条单链 | **+0**：同一个节点、同一个图层，外扩按配置的最大半径 | 每块玻璃第一次扫过全程新建至多 17 条效果链（只是 `RenderEffect` 小对象），之后切换档位零分配 |
| AGSL 直接路径（局部增强） | 有弱档输入（渐进模糊）时按清晰 → 弱 → 强分段插值；没有弱档时在着色器里对清晰输入做环形模糊作中间档（两圈 17 点、限幅 12px，见 §14） | +0 | 只在 `p < 1` 时多 17 次取样；`p = 1` 走原 `background()` |
| 软件采样（局部表面软件后端、柔光实时透镜） | 拖动中保持完整半径并淡出；停手 150ms（`SETTLE_MILLIS`）后 `LiveBackdropSampler.setBlurScale` 按真实半径补采一次，新样本发布后改用半径权重 | +0（复用现有缓冲，不释放） | 每次停手一次模糊作业（耗时与半径无关，96k 像素约 1.5 ms，桌面 JVM） |
| 柔光静态磨砂（所有柔光表面共用） | 停手后在 `Lumen-SoftFrost` 线程从未模糊原图按真实半径重算一张"薄磨砂"；只保留一个槽位，每次停手最多算一次 | 至多 +640 KiB | 每次停手一次（160k 像素约 2.5 ms，桌面 JVM） |

每种材质的半径比例沿用效果图：柔光 `p^0.7`、液态 `p²`、亚克力 `smoothstep(p / 0.35)`、棱镜 `p`。半径路径的背景权重 `radiusBackdrop` 在 `p ≥ 0.1` 时为 1，只在最后一段淡出（那时样本已与下方内容几乎相同），`p = 0` 时照常停止采样。

**实测（API 34 模拟器，1080×2340，440dpi，Demo 页八块玻璃）**：

- 渲染目标池在 `p = 1`、`0.35`、拖动后、回到 `1` 四种状态下都是 22.38 MB，可回收部分都是 12.29 MB，即在用部分不变，半径变化没有新增图层。
- `0.35` 时柔光、棱镜、增强（AGSL）、软件四种玻璃后面的正文都是半径变小的模糊，不再是清晰字叠一层雾；AGSL 着色器在设备上编译通过（诊断后端为 `GPU`），软件玻璃诊断为 `SOFTWARE`。

**仍未覆盖**：悬浮栏的内容节点玻璃（`FrostedChromeGlassApi31`、`LiquidChromeBackdropApi31`）与液态 `BLUR` 后端（API 31–32）仍是固定半径淡出；软件路径停手时样本从"完整半径淡出"切到"真实半径不透明"会有一次轻微的观感跳变；GPU 耗时未在真机（PMA110）上测。

## 14. 性能检查后的优化

对 §12–§13 做了一次只读性能检查（静止零帧、显存不变，但有下列问题），随后逐项修复：

| # | 问题 | 修复 |
|---|---|---|
| P1 | 首次扫过档位时渲染线程卡顿：Skia 每种模糊半径要编译一个卷积程序 | 档位改为 8 档、相邻半八度，按主模糊 σ 精确缩放（`GlowChromeBlurPolicy.level`）。Skia 把 σ > 4 按 2 的幂降采样，半八度阶梯降采样后只会反复落在两种核宽上；10/24/48dp × 4 种密度下，一次扫过全程用到的核宽都不超过 5 种（`GlowChromeBlurLevelTest`）。宿主调用过材质浓度 API 的玻璃，会在空闲时每帧用一个 24px、alpha 1/255 的离屏节点预热一档（`drawWarm`），全部做完后不再续帧 |
| P2 | AGSL 合成中间档在大半径时 8 个取样点相距几十像素，清晰边缘会裂成多份 | 改为中心加两圈交错环形，共 17 点（内圈 0.35r、外圈 0.8r），半径限幅 12px；中间档也不超过 12px，更大的部分由强档混合补足 |
| P3 | 停手改半径会在主线程重录一次来源内容 | 内容未变且几何一致时，直接复用已发布的输入回放（`reference`）在后台重新模糊（`reblur`），已发布输入保持不变 |
| P4 | 角色浓度变化会重绘所有角色的表面；全角色设置每帧要走 20 遍 | 表面登记时带上角色，只重绘变化的角色；`delegate.setMaterialOpacity(value)` 合并成一遍（`setMaterialOpacities`） |
| P5 | Fluent 每帧改 `elevation` 并 `invalidateOutline()`，平台阴影每帧重建 | 高度与轮廓 alpha 按 1/32 量化，只在档位变化时更新 |
| P6 | 每次权重变化都 `removeCallbacks` 加 `postDelayed` | 时间戳防抖：只投递一次，到点后自行补等剩余时间 |
| P7 | 停手时纯 GPU 绑定也会多画一帧 | 只有软件后端或仍在预热的绑定才重绘 |
| P8 | 薄磨砂每次停手产生约 2 MB 临时数组 | 后台线程复用像素与 scratch 缓冲，改用 `blurInto` |

**复测（API 34 模拟器，已 `pm clear` 清空着色器缓存）**：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 首次 1 → 0.35（约 2 秒） | 10 帧，p50 150ms，p90 800ms | 25 帧，p50 21ms，p99 200ms |
| 动画结束后静止 4 秒 | 0 帧 | 0 帧 |
| 启动后的预热 | — | 7 帧（模拟器 SwiftShader 上单帧最长约 2 秒），之后静止 0 帧 |

32dp 大半径的增强玻璃在 `0.35` 时为平滑模糊，没有错位虚影；新的 AGSL 着色器在设备上编译通过。

**未验证**：预热帧和档位切换在真机 GPU 上的耗时（PMA110）。模拟器的绝对帧时间没有参考价值；预热把编译开销挪到了空闲期，如果用户恰好在预热的那几帧开始操作，仍可能遇到一次较慢的帧。
