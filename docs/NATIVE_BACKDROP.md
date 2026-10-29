# 可选原生 backdrop

局部视效会话提供显式开关，在 Android 17 QPR2（API 37.2）上尝试使用 `RenderNode.setBackdropRenderEffect(RenderEffect)`。默认关闭，既有调用、材质配置及显式来源采样保持兼容。该能力只覆盖基础 FROSTED 圆角 View 背景，不替换 Mica、选择性内容来源或整个引擎。

## 输入语义和适用范围

原生接口处理当前渲染目标中在该节点之前已经绘制的内容，再绘制该节点。显式采样则录制绑定的来源 View；这两个输入是否相同取决于宿主页面结构。开启前必须确认背景已先行绘制，且当前渲染目标中的内容符合预期。来源 View 参数继续承担回退和安全校验，不能指定原生 backdrop 的来源。

后端选择保留以下边界：

| 条件 | 行为 |
|---|---|
| `session.bind(view, surface, source: View?)` 安装的基础 FROSTED 圆角背景，`clipBackground=true` | 可以请求原生后端，仍需满足以下门控 |
| 完整 SDK 版本 `>= 3700002`，实际 Canvas 硬件加速，GPU 采样允许，Session `gpuScale=1` | 可进入原生路径；软件、静态或缩小 GPU 采样配置不会被开关覆盖 |
| 折射关闭，或折射强度为 0；渐隐和改变视觉的增强效果关闭 | 保留基础模糊、既有色调/色罩和描边；不宣称原生 backdrop 等价表达折射或渐隐 |
| Drawable alpha、Surface opacity/backdropOpacity、相关材质背景/模糊权重均为 1 | 可进入原生路径；这些是效果强度条件，不要求色罩将背景完全遮住 |
| View 及祖先仅有整数像素平移，背景 View 自身没有 scroll 偏移 | 可进入原生路径；祖先滚动可用，仍按会话协议通知位置/滚动变化 |
| 小数像素平移，View 自身 scroll 偏移，View 或祖先缩放、旋转、alpha 非 1、独立图层或旧式 `Animation` | 保留显式来源路径 |
| 自定义 `LumenContentSource`、视口 wrapper、draw-only target 或复合 Acrylic/Mica/Liquid/Prism | 保留显式来源路径 |
| 来源不可用、不同窗口、自反馈、预算超限、暂停、隐藏或失焦 | 继续执行既有会话门控与回退，不绕过来源或预算约束 |

原生采样受绘制顺序、离屏图层、裁剪和窗口边界影响。祖先临时创建的离屏目标、窗口透明合成和重叠表面都可能改变可见输入；宿主应验证真实页面的像素。调用方必须避免自定义祖先 `saveLayer`、`RenderEffect` 等隐藏离屏语义：`View.layerType` 检查只能检测显式 View 图层，不能证明当前目标就是窗口背景。自定义祖先的 `Canvas.concat` / `scale` 或 `animationMatrix` 实际变换同样不能由常规 View 属性完整检测，接入方应排除这些状态。它不能选择任意内容源，不能假定读取独立视频 Surface，也不保证与旧路径的模糊边缘、采样分辨率或裁剪边界逐像素一致。窗口外的内容不属于本接口承诺的输入。

原生区域仍保守包含模糊 halo，计入 Session 的 `maxGpuSurfacePixels` 和 `maxGpuDimension`；启用质量配置时还受 `maxExecutionPixels` 约束。开关不能取消或扩大这些预算。

包含 halo 的完整矩形还必须位于 window/decor 边界内；靠近窗口边缘、矩形越界时主动回退显式来源。平台可能在窗口边界裁剪背景快照并移动其局部原点，现有颜色 Shader 无法据此保证坐标不偏移，因此不在该状态强行使用原生节点。整数平移限制同样避免背景快照取整引起的坐标差异。

## 接入示例

Sample 的“局部表面”页面提供默认关闭的“当前绘制背景”开关，“诊断”显示独立后端状态。可先选择基础 FROSTED、关闭折射/渐隐，模糊半径降至 6dp，保持透明度 1 和无增强效果，再比较开启/关闭；预览默认边距小于大半径模糊外沿时会回退，不能仅凭勾选状态断言原生已激活。

以下代码在主线程执行。`bar` 是覆盖在 `content` 上方的普通 View，两者位于同一窗口；`content` 不得包含任何由本会话注入的表面。宿主负责使内容先绘制、表面后绘制。

```kotlin
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession

val session = LumenSurfaceSession(context, palette)
val surface = LumenSurfaceOptions(
    material = LumenSurfaceMaterial.FROSTED,
    radiusDp = 24f,
    opacity = 1f,
    backdropOpacity = 1f,
    clipBackground = true,
    sampling = LumenSurfaceSampling(
        blurEnabled = true,
        blurRadiusDp = 10f,
        refractionEnabled = false,
        refractionStrength = 0f,
        fadeEnabled = false
    )
)
val binding = session.bind(bar, surface, content)
binding.setRenderTargetBackdropEnabled(true)

// 调用方已有的滚动/位置回调继续通知会话。
session.notifyScrollPositionChanged(scrollHost)
session.notifyPositionChanged()

// 主线程按需查询；不要把生成快照的诊断查询放进逐帧热路径。
val state = binding.renderTargetBackdropState()
val currentlyNative = state?.active == true

// 页面停止/恢复时转发生命周期。
session.pause()
session.resume()

// 可随时关闭请求，恢复显式来源采样。
binding.setRenderTargetBackdropEnabled(false)

// 退场时关闭；关闭后查询返回 null，迟到调用不会重新分配资源。
binding.close()
session.close()
```

低于 37.2 的设备和软件 Canvas 可以运行同一段接入代码，会使用原有 GPU、软件或静态回退。调用方无需直接接触 RenderNode。开关不持久化，宿主自行保存选择。动态透明度、渐隐或增强配置一旦不匹配即回到既有路径；不要依靠开关强制这些效果进入原生路径。

## 资源、切换和诊断

首次原生成功前仍录制一次旧来源，以便初始化失败时立即使用安全回退。原生成功后，若同组全部活跃表面都走原生路径，该组停止多余的旧来源录制和软件采样；混合组继续为显式采样表面工作。原生节点、模糊效果、颜色处理和几何对象按配置及尺寸变化复用，不通过逐帧重新分配维持更新。回到软件画布或其他不匹配状态时恢复显式来源工作，原有采样预算、来源排除和防反馈继续有效。

节点只在主线程使用；尺寸、位置及圆角裁剪更新遵守 View 本地坐标。节点保留模糊 halo，圆角仅限制输出区域。AOSP main 的录制代码在子节点前单独登记 `BackdropFilterDrawable`，因此空子节点显示列表也有 backdrop 指令；这一源码核对不等于 API 37.2 设备已通过像素验证。关闭请求、pause、releaseGraphics、解绑和 detach 清理原生效果及显示列表；清理中的厂商异常局部隔离，并锁存原生失败，不中断回退和会话关闭。原生后端异常将本绑定标为失败并回退；普通配置更新、pause/resume 或关闭再开启请求不清除失败，避免持续抛错和每帧重试。需要重新尝试时由宿主明确关闭并重建绑定。

`binding.renderTargetBackdropState()` 返回 `LumenRenderTargetBackdropState`，绑定不存在或会话关闭时为 `null`：

| 字段 | 含义 |
|---|---|
| `requested` | 宿主显式请求是否开启，不能据此断言支持或已使用 |
| `active` | 最近一次已选择并提交原生绘制命令；切换、暂停和释放资源后为 false，不代表实际像素验收 |
| `failed` | 本绑定曾发生原生失败，后续保持回退 |
| `draws` | 成功提交原生绘制的累计次数，不是实际显示帧数、GPU 时间或像素验收结果 |

既有 `LumenSurfaceBackend.GPU` 和 `gpuDraws` 包含显式采样 GPU 与原生 backdrop 两种实现，判断是否走原生必须结合 `active`。首次可见帧、GPU 计数或 API 调用成功都不能证明当前背景像素正确。

## 编译、验证和证据边界

编译需要 API 37.2 SDK，并设置 `compileSdk=37`、`compileSdkMinor=2`；最低支持版本仍为 API 27。完整版本判断先检查 `SDK_INT >= 36`，再读取 API 36 才提供的 `SDK_INT_FULL` 并与 `3700002` 比较。高版本平台调用隔离在 `RenderTargetBackdropApi37` 中，不通过反射访问。`setBackdropRenderEffect()` 的 boolean 返回值表示属性是否变化，`false` 不是后端不可用。

2026-10-28 使用 Google API 37.2 r06 x86_64、16 KB 页、软件 GPU 的独立模拟器验证：运行时 `SDK_INT_FULL=3700002`；原生像素／绘制顺序／圆角／内容更新／模糊边界和生命周期测试 7/7 通过。相同 `ScrollView` 场景的四个静态位置两条路径全图 RGB 差为零，移动场景在相同取样偏移下中心像素一致。测试使用独立包名的非混淆 APK；这不是物理设备或混淆后运行时的验证。

同一 60 Hz 模拟器以旧、原生、原生、旧的顺序各滚动 3 秒，四轮均有 180 帧且未丢失 FrameMetrics 回调。旧路径两轮 P95 分别为 12.970、17.597 ms，原生为 16.919、8.724 ms；按 `TOTAL_DURATION > DEADLINE` 判定，每轮均为 1/180 帧。旧路径每轮录制来源 180 次，原生为 0 次。整进程 CPU 时间、GPU 阶段时间、完整逐帧样本及计时外的进程 HWUI GPU 缓存快照均已记录；缓存受跨轮历史和释放影响，不等同设备或驱动的总显存。数据波动较大，不能从这四轮推出稳定性能收益。

单测、编译、R8、模拟器像素及性能结果分别证明各自条件；独立视频 Surface、不同裁剪／离屏目标、物理设备和完整 GPU 显存仍未覆盖。首次跨运行的动画截图落在颜色边界，采集时刻导致对比失败；保留了原始截图，后续将取样移到色带内部并保持原阈值，重测通过。

获得合适的物理设备后，仍需固定相同页面、分辨率、采样参数和动画脚本，分别关闭/开启原生请求，记录实际 `active` 状态并检查：

- 截图/PixelCopy 像素：模糊足迹、色调、圆角/父级裁剪、重叠绘制顺序、窗口边界及前景清晰度。
- 滚动与动画：背景跟随、整数平移、配置切换、失焦、pause/resume、releaseGraphics 和 detach 后恢复；小数平移、自身 scroll、窗口边界 halo 越界及不支持的缩放/旋转/透明度及时回退。
- 失败和混合组：原生失败后当前帧与后续帧正确回退，不逐帧重试；原生与显式来源表面共存时录制不丢失。
- P95 帧耗时、卡顿、CPU/GPU 时间与图形内存；保留原始跟踪和设备版本信息后再讨论差异。

本能力不承诺更快、零拷贝或减少系统总显存，不接入或修改宿主进程的官方内存预算。

## 官方参考

- [RenderNode.setBackdropRenderEffect](https://developer.android.com/reference/android/graphics/RenderNode#setBackdropRenderEffect(android.graphics.RenderEffect))：新增于 37.2，处理已绘制背景；返回值是属性变化，传 null 清除效果。
- [RenderNode](https://developer.android.com/reference/android/graphics/RenderNode)：硬件 Canvas 要求、同线程使用、录制/尺寸、Outline 拷贝和显示列表释放。
- [Build.VERSION.SDK_INT_FULL](https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT_FULL)：从 API 36 提供，包含主/次版本。
- [Build.VERSION_CODES_FULL](https://developer.android.com/reference/android/os/Build.VERSION_CODES_FULL)：`CINNAMON_BUN_2` 对应完整 SDK 版本。
- [AGP 9.4 CompileSdkSpec](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CompileSdkSpec)：编译主/次 SDK 版本配置。
- [AOSP SkiaRecordingCanvas.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/SkiaRecordingCanvas.cpp)：main 分支的 backdrop 指令与子节点录制顺序。
- [AOSP BackdropFilterDrawable.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/BackdropFilterDrawable.cpp)：main 分支的背景快照、设备边界裁剪与滤镜绘制；源码核对不能代替 API 37.2 设备证据。
