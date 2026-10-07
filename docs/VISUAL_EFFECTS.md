# 局部视效增强（1.2 候选）

新增配置使用独立的 `LumenSurfaceEnhancements`，旧 `LumenSurfaceOptions`、采样配置及会话构造保持兼容。
增强默认关闭；旧材质ID、持久化选择、热策略和Activity接管方式不变。

## 最小接线

```kotlin
val effects = LumenSurfaceEnhancements(
    geometry = LumenSurfaceGeometryOptions(cornersEnabled = true,
        corners = LumenSurfaceCorners(24f, 8f, 24f, 8f), fusionEnabled = true),
    press = LumenLocalPressOptions(enabled = true),
    light = LumenSurfaceLightOptions(enabled = true)
)
val session = LumenSurfaceSession(context, palette)
val binding = session.bind(target, LumenSurfaceOptions(), contentView, effects)
val input = LumenSurfaceInteraction(target, binding, effects.press, effects.light)
// 在宿主已有触摸处理内转发，不用它替换业务监听，不消费业务点击。
input.observeTouch(event)
// 单位为目标View局部像素；第三、第四参数是宽、高。
binding.setShapesPixels(ax, ay, aw, ah, bx, by, bw, bh, secondShape = true)
binding.setLightDirection(x, y, z) // 非零向量，z>=0，内部归一化。
// 生命周期由宿主显式调用；先关闭交互，再关闭绑定和会话。
input.pause(); session.pause()
input.resume(); session.resume()
input.close(); binding.close(); session.close()
```

以上公开渲染/交互入口在主线程调用；close后的迟到调用为空操作。
几何和光源状态使用原始数值，连续更新不重新编译Shader、不创建RenderEffect。
涉及输入层数、滤波半径、面积预算的配置建议在滑块松手时提交。

## 平台与执行计划

| 路径 | 输入和表现 | 资源与限制 |
|---|---|---|
| 未启用增强 | 31+共享内容RenderNode；失败/低版本按原开关软件回退 | 保留1.1节点路径 |
| 33+增强、硬件Canvas、AUTO/GPU | 有界软件Picture录制/后台滤波 → 清晰/弱/强BitmapShader → 直接AGSL绘制 | 输入来自软件采样，效果在GPU执行；不称为全GPU捕获 |
| SOFTWARE、27–32或Shader失败 | 四角路径/两矩形并集、固定强模糊、着色与描边 | 不模拟AGSL的平滑桥接、局部位移与动态光照 |
| STATIC/关闭采样/降低透明度 | 轮廓和静态易读着色 | 不创建背景采样资源 |

`softwareFallback=false`也限制新位图输入路径；GPU不能取得允许的输入时显示静态回退。
Shader失败在当前绑定中保持淘汰；来源暂时不可用可恢复，不淘汰全局材质。
曾使模拟器退出的三分支RenderEffect原型已移除，不进入执行计划。
内部工厂声明背景/滤波层数、外扩、触点和时间需求；Shader实例及复用缓存属于绑定，来源缓冲属于来源组，关闭分别释放。

## 参数、开关与单位

所有配置拒绝NaN、无穷及越界。dp只在渲染时按密度换算；比例均为0–1范围中的相对位置或范围，另有说明除外。

| 配置组 | 参数和有效范围 |
|---|---|
| geometry | `cornersEnabled`；TL/TR/BR/BL四角0–128dp；`mirrorCornersInRtl`；`fusionEnabled`；融合半径0–64dp；AA宽0.25–2dp；阴影开关、半径0–24dp、透明度0–0.8 |
| progressiveBlur | 开关；弱/强半径0–48dp且强≥弱；各自开始/结束位置0–1且结束≥开始；正/反方向；强度0–1；最大条带高度24–256dp |
| press | 开关；位移−8–8dp；作用半径为短边0.05–0.75；高光0–3；移出取消；释放时长50–1000ms；波纹开关、振幅0–4dp、速度20–400dp/s、宽2–48dp、寿命100–2000ms、池容量1–4 |
| light | 开关；角度0–360°；高度0.05–1；光照/镜面强度0–3；镜面指数2–64；边缘宽0.25–12dp；法线跟随变换开关；手势影响0–1；平滑0–1000ms |
| material | 沿用/阅读/通透/不透明易读/装饰意图；意图默认值开关；尺寸归一化开关；边缘比例上限0.02–0.4；折射比例上限0–0.25；回退着色下限0.5–1；降低透明度/降低动效；色散0–2；饱和度0–2 |
| quality | 开关；低/均衡/高；自适应开关；压力阈值下0.05–0.9、上为下阈值–0.95；滞回0–0.2；驻留250–10000ms；绘制像素16384–4194304；位图像素1024–96000；间隔0–1000ms |
| debug | 计数开关、耗时开关、五范围显示；线宽0.5–4dp、透明度0.1–1；保守内区或外扩实验 |

默认没有传感器、无限波纹或持续时钟任务。交互在弹簧、光源滤波和有限波纹结束后停止调度；手势取消/窗口失焦/解绑清空瞬态状态。
压力输入由宿主通过 `binding.setQualityPressure(0f..1f)` 提供，引擎不猜测GPU负载。
自适应每次只降低/恢复一级，并满足滞回和驻留。高模式包含色散和最多4个波纹；均衡去色散、最多2个波纹；低模式再去镜面高光/阴影/波纹，将输入像素上限减半且间隔至少56ms。
质量开关不关闭硬预算；位图、绘制和原会话总预算仍共同生效。

## 几何与光源

四角是物理TL/TR/BR/BL顺序；选择RTL镜像时交换左右。半径按所有相邻边长统一缩放，保持比例，允许单角超过短边一半。
覆盖、融合和法线使用同一个角区距离场；融合使用多项式smooth-min，包围盒保守增加k/4。交叉点梯度近零时用稳定平面法线。
两个形状属于一个装饰背景；业务View、点击区和无障碍节点不融合。
显式setShapesPixels会启用自定义实体；`clearCustomShapes()`恢复以View边界为实体的默认状态，未启用其他增强时回到旧节点路径。
`LumenFusedSelection`接入真实`LumenSlidingSelection`的当前/目标几何，沿用位置和速度续接；终态关闭第二形状。
装饰器转发原几何监听，关闭时只恢复仍由自己持有的背景和监听；宿主后续替换的资源保留。
`setSharedNodeBaseline(true)`将同一选择器的原indicator接入旧共享内容节点路径作A/B，忽略增强配置；关闭基线后恢复融合装饰。`diagnostics()`可检查真实后端。单独关闭融合仍保持当前自定义实体轮廓。
直接宿主容器要显式设置 `clipChildren=false`、`clipToPadding=false`，并为外扩留出空间。Shader输出仍受绑定View边界限制。
非均匀缩放/旋转的法线用完整祖先矩阵的逆转置映射；奇异矩阵或透视退到稳定本地法线。

## 材质优先级与颜色

默认UNCHANGED保持旧外观。启用意图默认值后，阅读的CARD/MODAL/TOP_BAR建议着色0.38，其余0.32；通透0.12；装饰0.16。
这些只替换旧默认0.20/0.80着色值，宿主其他覆盖保持；若宿主明确需要默认数值本身，也可关闭 `useIntentDefaults`。
尺度限制在最后施加，折射/边缘/色散/按压/阴影不会无限按大面板放大。
降低透明度或OPAQUE_ACCESSIBLE拥有最高安全优先级：不透明着色、STATIC且不捕获背景。`contrastFloor`只是回退着色下限，不是WCAG对比度保证；文字颜色仍由宿主管理。
降低动态效果关闭按压、融合过渡的第二实体和波纹，不控制业务页面状态。

输入契约为**sRGB、SDR、ARGB8888、采样Shader预乘颜色**。CPU滤波先预乘，再滤波，再按Bitmap API要求解预乘写回；Shader的非线性饱和度先安全反预乘，最后恢复预乘。
渐进混合同时混合RGB和alpha；覆盖率/渐隐/背景透明度同时乘两者。透明度接近零返回零颜色。
固定两个模糊层加清晰层，属于权重混合，不是每像素可变σ的Gaussian。
扩展色域/HDR属于单独能力组：本实现先把来源光栅化到sRGB ARGB8888，无法保留HDR亮度；参数导入明确拒绝伪称LINEAR_HDR的契约。不能把SDR的RGB≤A约束外推为HDR结论。

## 来源、范围与诊断

需要主动控制可用性时实现 `LumenVersionedContentSource`。getter是主线程纯快照，不做I/O；代次和版本非负；依赖身份/拓扑在自己的epoch变化前保持稳定。
READY允许取样；TEMPORARILY_UNAVAILABLE暂停并可恢复；PROHIBITED和INDEPENDENT_SURFACE停止捕获。宿主负责据授权/受保护内容/独立Surface填入真实状态，引擎不绕过保护、也不自动抓SurfaceView。
绑定前检查环；来源或依赖换代重新校验32节点/32依赖硬上限并丢弃旧输入。根来源的contentVersion或显式内容通知负责表示组合内容变化。
`notifyContentChanged`更新内容代次；`notifyPositionChanged`只更新映射（软件路径需要重新采样，节点路径不强制重录来源）。

`samplingRegions()`返回保守矩形包围盒，旋转后并非每个包围盒像素都属于来源：

| 范围 | 坐标空间 / 调试颜色 | 含义 |
|---|---|---|
| shapeBounds | 目标局部 / 绿 | 当前两实体及融合保守外扩 |
| requiredSampleBounds | 目标局部 / 红 | 按执行计划声明的输入外扩 |
| recordedBounds | 来源局部 / 品红 | 实际最近软件组录制范围，或共享来源节点范围 |
| materializedBounds | 执行像素 / 青 | 可用位图范围；旧节点的轮廓物化内区 |
| outputClip | 目标局部 / 蓝 | 输出View限制；圆角实际轮廓由几何定义 |

外扩实验只改变新直接Shader的采样clamp，默认SAFE_INTERIOR；没有删除旧RenderNode的outline裁剪。
性能诊断区分内容录制、代理录制、效果链构建、RuntimeShader构造、软件请求/完成/过期和可选CPU耗时。
活跃软件字节和逻辑GPU像素属于引擎所有资源，不等于驱动物理显存、GPU pass、真实编译或GC滞留。
来源年龄从录制请求时间估算，含排队/处理时间；不代表合成器实际生产像素时间。释放或换代后年龄未知。
区域快照中的epoch/version是会话归一化计数，不直接回显发布方任意版本号。

## 确定性与复现

`LumenFixedFrameClock(initialNanos)`通过seekNanos/advanceMillis由调用方推进；固定时钟不自动投递帧。回退或切换时间轴时先清空瞬态效果。
`LumenEffectPreset(surface, enhancements, seed).toJson()`输出schema1；`fromJson`整份校验，已知类型错误/越界/未知schema拒绝，未来未知字段忽略。平台解析前限制16384字符、16层嵌套和单一根对象，避免异常嵌套进入递归解析。
不包含像素、Uri、文件路径和业务数据；seed为固定重放元数据，本轮效果没有随机生成阶段。
Demo提供全部开关/数值、手动16ms步进、JSON复制和导入。选择器、触点和来源的实时输入仍须由调用方录制/提供；JSON是参数预设，不是完整业务事件日志。
固定场景基准记录FrameMetrics原始样本、P50/P95/P99及测量区间计数增量。模拟器数字只用于同环境比较，不能作为手机帧率、电量或厂商图形兼容验收。
