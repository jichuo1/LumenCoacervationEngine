# 1.2.1：传感器、效果包与动画资产

本轮对应研究报告D14、D20、D21和用户选择的D22。所有功能可单独接入；本轮按用户指定编号1.2.1，所有所选模块须使用同一版本；编号例外见VERSIONING.md。

## 模块与依赖

| 模块 | 用途 | 额外播放依赖 |
|---|---|---|
| lumen-motion | 可选传感器光源、时钟 | 无 |
| lumen-effects | 薄膜、纸张、有限能量、粒子 | 无；依赖motion/core |
| lumen-assets | 资产来源、预算、生命周期、后台解析与播放会话 | 无；依赖motion/core |
| lumen-assets-lottie | JSON与嵌入位图的Lottie | com.airbnb.android:lottie:6.7.1 |
| lumen-assets-pag | PAG时间线 | com.tencent.tav:libpag:4.5.98-noffavc |
| lumen-assets-rive | Rive画板、时间线、状态机与输入 | app.rive:rive-android:11.14.0 |

core/motion不反向依赖这些模块。播放库是各自模块的implementation依赖，公开签名没有供应商类型；选了Rive的宿主会收到其Compose和原生传递依赖。PAG、Rive带原生运行时；本轮核对的PAG AAR含arm64-v8a、armeabi-v7a、armeabi、x86_64，不含x86。Demo同时演示三种格式，因此包含全部依赖；业务应用只选择需要的模块。

## D14传感器光源

`LumenSensorLightController(view, listener, options, input?)`是窗口局部生产者。默认关闭，只有显式`start()`且启用、可见、有焦点时注册；`stop()`、隐藏、失焦、detach、close解除监听。宿主将primitive光向量回调转发给自己选择的`LumenSurfaceBinding.setLightDirection()`，并自行管理传感器与手动光源的优先级。

| 参数 | 默认／范围 |
|---|---|
| enabled / allowAccelerometerFallback / invertX / invertY | false / true / false / false |
| sampleRateHz / minimumUpdateIntervalMs | 30（1–60）／32（16–1000ms） |
| smoothingTimeMs / influence | 150（0–2000ms）／0.5（0–1） |
| maximumTiltDegrees / maximumAngularSpeedDegrees | 45（0–60°）／180（10–360°/s） |
| changeThresholdDegrees / flatThreshold | 0.3（0–10°）／0.025（0–0.2） |
| baseAngleDegrees / baseAltitude | 145（0–360°）／0.65（0.05–1） |
| fixedRotation | null自动主显示器，或0/1/2/3 |

优先重力传感器，可关闭加速度计回退。时间滤波使用事件时间戳；首次有效样本初始化方向，后续样本以球面插值约束角速度，单次时间差最多0.25s。方向旋转映射、平放死区和发布节流独立。自动映射拒绝非主显示器，宿主可显式提供旋转。无传感器、注册失败、回调失败均有独立状态；失败不会每次preDraw重试，显式start/update后可恢复。诊断rejected包括无效与被死区/间隔抑制的事件。

## D20/D21独立装饰

`LumenEffectLayer`挂ViewOverlay，保留业务背景、层级和输入监听。默认生成器和粒子均关闭；生成器不需要背景来源，捕获计数恒为0。API33硬件绘制采用已编译的固定RuntimeShader程序；失败在该Drawable内粘性回退Canvas。薄膜为风格化虹彩，未宣称物理光学模拟。Canvas使用近似梯度／点粒／环形波，外观不保证与GPU逐像素相等。

| 组 | 可调内容 |
|---|---|
| layer | seed、framesPerSecond 1–60、maximumRenderPixels 16K–1M、reduceMotion、pauseWhenUnfocused、clearOnPause、clipToBounds |
| procedural | enabled、kind FILM/PAPER/ENERGY、opacity、color、radiusDp 0–128、gpuEnabled |
| film | thicknessNm 100–2000、iridescence、roughness 0.05–1、angleDegrees、intensity 0–2 |
| paper | grainEnabled、grainCount 16–512、grainSizeDp 0.5–12、contrast、relief |
| energy | durationMs 100–4000、speed 0.1–3、bands 1–8、wavelengthDp 8–96、intensity 0–2 |
| particles | enabled、shape ORB/STREAK、maximumParticles 4–128、burstCount 1–64、lifetimeMs 100–4000、speedDpPerSecond 0–800、gravityDpPerSecondSquared −300–300、radiusDp 1–12、spreadDegrees、directionDegrees、trailLengthDp 0–24、opacity、color、maximumParticlePixels 1K–256K |

`emitBurst(x,y)`是显式有限发射，未实现无限自动发射器。固定128槽数组池用解析年龄积分，不每帧扩容；种子和固定时钟使结果可复现。`triggerEnergy()`只产生有限瞬态，未触发和结束时透明。只在活粒子／能量存在时安排tick；隐藏、后台、关闭和减少动画停止动态工作。`clearOnPause=false`保留状态并冻结寿命，true清空瞬态。

`LumenEffectPreset`是schema1参数JSON；16KiB/深度/字段类型/范围校验沿用现有有界导入规范，已知错误字段拒绝整份配置。它不包含任意Shader、资产文件、传感器状态或播放器状态。

## D22资产会话

```kotlin
val session = LumenAssetSession(container, LumenAssetOptions(autoplay = false))
session.setListener { state, failure -> /* 异步主线程状态通知 */ }
session.load(LumenAssetSource { context.assets.open("animation.json") }, LumenLottieFactory())
// 用户开始播放时 session.play(); onStop时pause(); onDestroy时close()
```

会话每次只拥有一个子容器，不修改宿主container的裁剪与原有子View。它是资产展示层：阻止子播放器自动接收pointer事件，自己的子容器返回false把触摸交还宿主；Rive交互使用显式状态输入，不接管业务触摸。`clipToBounds`作用于自己的子容器，不能取消祖先已有的裁剪。enabled=false隐藏自己，停止提交。每会话单线程＋最多一个待处理替换；取消旧任务、代次隔离、关闭迟到句柄，后台解析后只在主线程挂载。固定时钟下须显式`advanceFrame()`；Rive原生时钟拒绝此模式。

| 预算／选项 | 默认／范围 |
|---|---|
| enabled / autoplay / reduceMotion / pauseWhenUnfocused / mute / videoEnabled / clipToBounds | true / false / false / true / true / false / true |
| speed / repeatCount / framesPerSecond | 1（0.1–4）／1（1–10）／30（1–60） |
| maximumFileBytes | 4MiB（1KiB–16MiB），读取中即拒绝超限 |
| maximumRenderPixels | 1M（16K–4M），分别检查元数据画布和宿主绘制面积 |
| maximumDurationMs | 60s（100ms–600s），限制元数据时长及一次可见播放会话的总时长，含重复 |
| maximumDecodedImagePixels | 1M（1K–4M），Lottie检查嵌入位图真实头部尺寸的总和 |
| opacity / fit | 1（0–1）／CONTAIN、COVER、FILL |

Lottie支持JSON时间线、绝对寻帧和有限重播；不自动解析网络图片、字体或dotLottie ZIP。PAG采用自有TextureView、PAGPlayer及PAGSurface，手动时间线提交，关闭明确释放player/surface，禁用磁盘缓存。PAGFile本身无公开release接口，结束后丢弃引用，由供应商GC/finalizer收尾。

Rive11.14.0的类型化View兼容层负责画板、命名时间线、状态机和number/boolean/trigger输入；没有把新版Compose API接进核心。解析时枚举至多1024个输入，metadata.inputs给出名称及类型；提交前验证，错误名称／类型返回false，避免错误请求排进原生渲染线程。只有可见、正在播放且未降低动态的会话才允许提交输入，暂停时返回false，防止输入唤醒后台渲染。它使用私有生命周期owner、默认不加载CDN资产、关闭销毁renderer并释放File。公开metadata明确nativeClock=true、seekable/speedControl/repeatControl/frameRateControl=false；速度1、单次播放，任意寻帧／速度／重复设置给出UNSUPPORTED_OPERATION。framesPerSecond用于可控时间线提交，不约束Rive原生时钟；Rive只注册一次剩余播放时长的截止任务，不安排周期监督tick。暂停／隐藏先结算已播放时间，再停用截止任务，恢复仅补上剩余时长；Android主线程阻塞可能延迟执行截止任务，不宣称实时调度硬截止。状态机无已知时长时由maximumDurationMs停止会话。

文件字节与已知画布预算是接入门禁，不是供应商内部原生解码内存的硬上限：PAG/Rive的内嵌图像像素数暂不可由适配器提前穷举，decodedImagePixels=0表示未计量。只加载宿主信任的资产；native parser无法强制中断且本轮没有进程隔离。宿主提供的网络InputStream必须自行设置连接／读取超时；有界队列防止请求堆积，不保证不响应中断的供应商解析立即取消。

## Demo与验收

Rive工厂默认`LumenRiveRenderer.CANVAS`，使用SDK仍提供的Canvas兼容后端；`GPU`显式选择Rive渲染器，Demo有下次加载生效的开关。解析File与View使用同一后端。首轮四版本SwiftShader环境均出现GPU depth/stencil回退后无可见像素，因此默认选择Canvas，不以成功解析冒充可见绘制。SDK的Canvas与View兼容API均已deprecated，GPU模式仍需在目标硬件验证，不自动把其能力等同Canvas。

入口：表面沙盒→P2效果／资产。中文控件覆盖上述参数、固定步进、诊断、效果JSON导入导出、本地文件导入和Rive输入。减少动画与播放预算必须由业务接入方保留。

验证细节见`P2_REVIEW.md`。模拟器覆盖解析、所有权、生命周期与实际窗口图像；重力事件模拟不能替代真实传感器轴向、多显示器、手机功耗和长时间原生内存测试。Compose接入层、NativeBackdrop和Mesh仍不在本轮范围。
