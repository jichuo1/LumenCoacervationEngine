# 原生 Compose 接入原型

本实现位于 `codex/native-compose-20261012`，以引擎 1.4.0 为基线，尚未发布。它是原生接入的第一阶段：局部材质、独立表面身份、背景采样、Lazy 生命周期和长按弹性。完整 Activity 视效、原生回弹的背景反采样、Modal 形变及预测返回尚未迁移；不能据此宣称整套 View 接入已具备 Compose 等价实现或全部性能相同。

## 接入

可选模块 `lumen-compose` 依赖 `lumen-engine`、`lumen-motion` 与 Compose runtime/ui/foundation。core 和 motion 不引用 Compose。使用已核实的 BOM `2026.09.00`（三项运行库为 `1.12.1`），Compose 编译插件沿用 Kotlin `2.4.21`。实际 AAR metadata 要求 compileSdk 37、AGP 至少 9.1.0；运行库 minSdk 23，本工程仍保留 minSdk 27。

```kotlin
val state = rememberLumenComposeState(palette)
LumenComposeHost(
    state = state,
    modifier = Modifier.fillMaxSize(),
    backdrop = { Image(painter, null, Modifier.fillMaxSize()) }
) {
    Box(
        Modifier
            .lumenElastic()
            .lumenSurface(state, options, enhancements, key = "card")
            .clickable(onClick = onClick)
    ) {
        BasicText("内容")
    }
}
```

宿主本身使用正常的 Compose 生命周期所有者。背景和前景保留在调用者的同一个 Composition 中，只有一个真实窗口宿主；每个材质表面是 `Modifier.Node`，没有单独的 AndroidView 或 ComposeView。

背景与前景必须是独立绘制层。背景在当前 Composition 的 draw 阶段录入一份可复用的 Picture，屏幕与采样使用同一份绘制指令。前景表面不进入该来源，因此不会递归采到自身。当前适用背景是可被软件 Canvas 记录的普通 Compose 图像、文字和形状；独立 Surface/Texture/WebView 及依赖硬件离屏效果的背景尚未验收。

`state` 应在当前窗口的 Composition 内创建，每个 `LumenComposeHost` 单独持有一份；Dialog 内另行调用 `rememberLumenComposeState`。`DisposableEffect` 关闭状态，节点完成布局并实际绘制后才占用表面预算，离开布局、Lazy 复用或 key 变化时释放旧绑定。`key` 用于生命周期和诊断，实际采样身份始终是节点；它不会把不同节点合并成一个表面。背景录制会显式略过本会话的材质表面，避免错误嵌入造成反馈。

## 交互和边界

顺序为 `.lumenElastic().lumenSurface(...)`，使材质和内容共享变换。手势复用引擎的 `ElasticGestureGate`、径向位移预算和阻尼弹簧；早期滑动超过 slop 后不再夺回，多指、取消、卸载和复用都会清理。只有已捕获的移动被消费，普通点击保持原事件流。关闭系统动画时不运行装饰性动画。

TextField、Slider 和业务长按控件不启用该弹性 modifier，它们的父包装也不应启用。减少动效的宿主设置可通过 `enabled = !reduceMotion` 显式接入。祖先主动设置的 clip 仍然生效；本原型没有声称完整复刻 View 的横向溢出门户、原生 stretch 或所有面板裁剪规则。

表面仅绘制材质背景，前景文字和业务状态仍由宿主控制。`LumenSurfaceOptions` 与 `LumenSurfaceEnhancements` 沿用现有局部会话的同一份材质、着色器、后端回退和资源预算。用 `state.session.diagnostics()`、`performanceDiagnostics()` 与 `bindingFor(key)` 检查实际后端和资源；诊断绑定的所有权属于节点，移除装饰通过 modifier/options 控制。

## 验证范围

`ComposeSandboxActivity` 包含移动卡片、输入框、选择器和稳定 key 列表。`NativeComposeIntegrationTest` 检查真实窗口像素、独立身份、位置采样、旧选中背景恢复、Lazy 释放和重新恢复、直接着色器缓存及手势归属。`NativeSurfaceTargetTest` 检查共享窗口锚点的软件采样和关闭所有权。测试中的 SDK 条件仅表达预期，不表示该 SDK 已实际执行。

正式发布前仍须完成 API 27/31/33/34 的实际运行、原生回弹与复杂背景、Modal/预测返回，以及相同视觉参数和场景的 View/Compose 成对 Release 帧耗时、内存、分配与静止功耗测量。当前的静止录制计数和着色器缓存测试不能替代这些比较。

参考：[Compose 自定义 Modifier](https://developer.android.com/develop/ui/compose/custom-modifiers)、[绘图 Modifier](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)、[指针消费](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)。依赖版本、最低 SDK 和编译要求另以本轮下载的 Google Maven POM/AAR 实物核验。
