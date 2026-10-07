# 局部视效会话（1.1.0）

LumenSurfaceSession 用于已有应用里的顶底栏、局部浮层和独立 Dialog 窗口。不修改根背景、Window、层级、输入或偏好。
完整 Activity 继续使用 LumenActivityDelegate；调用方保留自己的 Activity 基类、授权门和业务回调。

```kotlin
val session = LumenSurfaceSession(context, palette)
val binding = session.bind(bar, LumenSurfacePresets.floating(), content)
session.setListener { id, backend, failure, firstVisible -> /* 绘制栈之外，主线程 */ }
binding.update(LumenSurfaceOptions(opacity = 0.8f))
session.updatePalette(nextPalette)
session.notifyPositionChanged()
binding.close()
session.close()
```

## 来源和窗口

source 应当是表面下面的现有内容，不能包含会话注入的任何表面。引擎检查窗口 token 与祖先关系，拒绝不同窗口和反馈回路。
同一来源的多个表面共享 GPU 录制；软件路径沿用后台单飞、有界的区域透镜采样。
Dialog/Story 浮层要传入本窗口的来源。来源或目标 detach 后会解绑；重新附着需要重建绑定。
可实现 LumenContentSource；coordinateView 在绑定期间必须稳定，drawContent 在其局部坐标录制。
只有真正排除了全部注入表面时，才能声明 excludesSurfaces=true。录制不得改变 View 的可见性/层级，不得分配逐帧绘制对象。
引擎录制时也会抑制自身背景，作为最后反馈防线。不同来源窗口独立监听、校验与释放，不共享纹理坐标。

## 配置与调节

配置显式传入、不自动持久化。宿主保存自己的用户意图，并提交不可变配置。构造器拒绝非有限值和越界参数。

| 对象 | 参数 | 默认 / 范围 |
|---|---|---|
| Surface | enabled / material / role | 开启；FROSTED；FLOATING |
| Surface | radiusDp / opacity / color | 24dp，0–128；1，0–1；null=palette.surface |
| Surface | tintEnabled / tintOpacity / fallbackTintOpacity | 开启；0.20 / 0.80，均 0–1 |
| Surface | edgeEnabled / edgeWidthDp / edgeIntensity | 开启；1dp，0–8；1，0–3 |
| Surface | clipBackground | 开启；不裁剪子 View |
| Sampling | enabled / backend / softwareFallback | 开启；AUTO；开启 |
| Sampling | blurEnabled / blurRadiusDp | 开启；10dp，0–48；0 也关闭模糊 |
| Sampling | refractionEnabled / refractionStrength | 开启；1，0–2 |
| Sampling | minIntervalMs / softwareScale | 28ms，0–1000；1/3，0.05–1 |
| Sampling | maxSoftwarePixels | 24000，1024–96000（含外沿） |
| Sampling | fadeEnabled / fadeHold / fadeEnd / fadeDirection | 关闭；0 / 1，0–1且end≥hold；从上到下 |
| Session | enabled / maxSurfaces | 开启；16，1–32；降低前需解绑多余表面 |
| Session | maxSoftwareBytes | 8MiB，256KiB–16MiB；所有来源活跃软件缓冲合计 |
| Session | maxGpuContentPixels / maxGpuSurfacePixels | 各4194304，16384–8388608；录制和效果层分别计量 |
| Session | maxGpuDimension / gpuScale | 4096，128–8192；1，0.1–1 |
| Session | autoPauseWhenHidden / pauseWhenWindowUnfocused | 开启 / 开启 |
| Session | restoreBackgroundOnDetach / registerMemoryCallbacks | 开启 / 开启 |
| Session | diagnosticsEnabled | 开启；控制状态通知，仍可显式查询快照 |

预算约束活跃软件缓冲和逻辑节点像素，不代表驱动总显存或 GC 尚未释放的旧显示列表大小。
旧纹理可能仍被显示列表引用，不主动 recycle；越界降级，不无限扩容。
fadingBand 关闭折射和描边、无圆角、启用渐隐与0ms间隔；staticPanel 关闭采样。预设可用 copy 调整。

## 生命周期、失败和诊断

AUTO 优先硬件节点（31+）；LIQUID 折射需33+。GPU不可用/失败按开关退到软件柔光，最后是静态。
binding.diagnostics().effectiveMaterial 区分请求和真实材质。失败后不逐帧重试；显式重建会话才重新尝试。
pause/resume 服务宿主生命周期，隐藏/失焦可自动暂停。releaseGraphics 不清内容和设置；显式resume或内容通知恢复资源。
Trim20普通切后台不作为严重内存告急。图形预算或采样配置变化释放旧资源并更新代次，迟到软件结果被丢弃。

调节采样/效果参数建议滑块松手提交，不把模糊或 Shader 配置变化作为逐帧动画；普通动画用 View transform 和位置通知。
AGSL透镜在配置变化时复用，不反复编译。动态配色不recreate Activity，文字和点击仍由调用方管理。
解绑只在背景仍由引擎持有时恢复原背景，保留当前padding，不覆盖别人的后续背景修改。

firstVisibleDraw 表示绑定首次实际可见绘制，也可能是静态回退；判断APPLIED必须结合backend/failure。
软件截图不冒充硬件窗口可见帧。通知合并后在draw/layout栈外投递，关闭后丢弃；客户端异常记录CALLBACK_FAILED。
诊断仅含绑定ID、计数、后端、失败枚举和配色代次，不含用户内容、路径、Uri或View引用。

## 示例和验证

“排布”页进入“局部视效调节实验室”，包含开关、数值调节、暂停/恢复、资源释放、诊断和独立Dialog演示。
示例不保存参数，不修改完整Activity的材质选择。
JVM覆盖非有限值、渐隐、极端几何预算、失败路由、共享预算和无折射像素；源码契约守住非侵入、所有权、反馈与注销。
LocalSurfaceIntegrationTest随sample设备测试执行，验证背景/层级/padding、软件帧、动态主题、无来源静态后端、跨窗口拒绝和关闭。
31+还用PixelCopy检查GPU渐隐的实际像素。ATD镜像默认不提交最终硬件图像，测试在33+临时启用输出并在finally恢复；生产引擎不控制该开关。
示例验证不等于第三方应用的Hook、Compose或灰度路径已经覆盖。
