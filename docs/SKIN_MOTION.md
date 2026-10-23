# 皮肤动画与屏幕圆角

自 1.5.2，柔光与 Liquid 在同一自定义背景、配色、密度和窗口尺寸下复用已经准备并显示过的背景像素。新 Activity 或恢复中的底页可以立即画出相同皮肤，避免开场及退场末尾短暂出现默认主题背景。面板继续使用其所属会话的稳定底图；标题代理共享来源文字的颜色所有权，最后一个代理释放后才恢复原始 `ColorStateList`。

## 可选屏幕四角

全屏形变目标页在创建 host 后设置：

```kotlin
val host = ContainerMorphHost(
    context = this,
    collapsedSurfaceColor = entryColor,
    expandedSurfaceColor = pageColor,
    titleColor = textColor,
    sourceTitle = title
)
host.followScreenCorners = true
```

默认值为 `false`，原有构造及控制器回调保持兼容。启用后，引擎读取 API 31+ `WindowInsets.getRoundedCorner()` 的半径与中心，只对当前 host 真正覆盖到的窗口角应用对应半径。四个角分别连续插值，来源条目的圆角保持原值，展开端到达系统报告的四角；背景、表面及内容使用同一轮廓。系统未提供数据、旧系统、分屏或 host 没有覆盖到对应角时，该角沿用原展开几何。窗口尺寸和 insets 改变时重新读取，动画逐帧不查询系统、不分配 Path 或数组。

这是系统公开信息能提供的形状近似，不读取厂商私有资源。Android 的圆角接口自 API 31 提供，并按当前应用窗口坐标返回信息，见 [Android 圆角适配说明](https://developer.android.com/develop/ui/views/layout/insets/rounded-corners)与 [WindowInsets API](https://developer.android.com/reference/android/view/WindowInsets#getRoundedCorner(int))。

## 背景准备与交接

正常接入仍是 `bindRoot(host.liquidBackdropRoot())`。内置背景 Drawable 通过 `LumenBackdropReadiness.isReadyForMotion` 告诉 motion 是否已准备好。新的自定义图没有可复用结果时，控制器在透明准备态最多等候 250 ms，再开始既有形变；它不在主线程解码，也不改变入场时长、插值或返回手势。普通背景和缓存命中不增加等候。取消、销毁与新的动画会使旧准备回调失效。

第三方背景 Drawable 可以选择实现该接口；不实现时保持原来的立即开始行为。控制器先调用默认空实现的 `prepareForMotion()`，再查询 ready；柔光的自定义图在这次形变里直接准备完整皮肤首帧，避免把普通渐显的主题色开场叠进形变。普通页面的首次渐显保留；已显示过的同背景结果不再重复从主题色渐显。解码失败应返回稳定回退，并报告 ready，不能用永久 false 阻止页面打开。

缓存按完整配色、背景配置、窗口尺寸、密度及 Liquid 光学档位精确匹配，不保存 Activity、View、Context 或渲染会话。每种材质只保留一对像素，单种最多 4 MiB，两种材质合计最多 8 MiB；这是缓存持有的像素上限，不是整个进程、GPU 或活动页面的内存上限。Liquid 每个缓冲仍遵守原有 2 MiB 的采样上限，一对包含显示图和光学图。复用不会复制像素，Liquid 的 Shader 和矩阵按会话独立创建。

低内存、图形释放或不匹配的新结果会丢弃缓存引用。已发布给显示列表的像素不强制 recycle，仍由运行时安全回收。缓存不随页面数量增长，不改变采样尺寸、模糊、光学强度、亮暗配方或原始图片。

宿主仍保留本地 renderer 时，可以用公开 `LumenPreparedBackdropCache<K, V>` 接入同一个淘汰与图形压力机制，而无需复制缓存实现。每种 renderer 只建立一个实例、按完整像素对的真实 `allocationByteCount` 计量；每实例一个条目、最多 4 MiB，额外宿主实例的预算须另行计入。匹配键包含宿主自己的完整背景配置和配色，绘制值只能包含已发布像素与独立绘制状态，不能保存 View、Context、Activity 或捕获它们的闭包。宿主的低内存回调还须 `clear()`；关闭后不可重新放入条目。

## 验证口径

`CustomSkinBackdropContinuityTest` 覆盖两种材质和亮暗配色的恢复、新会话首帧、居中／锚定／气泡／覆盖子面板，以及真实 sample 二级 Activity 的往返。`ScreenCornerMorphTest` 检查不同半径的四角、默认关闭与关闭恢复、冷背景准备态；JVM 策略检查坐标、缺失数据和插值边界。发布所用 APK 还须经过固定的四版本全量门禁，具体证据见对应 Release 的审阅与验证附件。

窗口 PixelCopy 和产品断言在全部版本执行；如需同时保留包含底页的系统合成截图，在本地 instrumentation 中显式传 `-e lumen.captureMotionReview true`。整屏截图用于人工取证，不是门禁的成功判据；ATD 的最终合成输出与真实设备不同，不能让额外截图导致整轮产品测试的设备连接中断。
