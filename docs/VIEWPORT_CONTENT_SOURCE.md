# 小视口内容源

自 1.4.6 起提供，公开声明见 [API.md](API.md#21-小视口来源与共享录制host自-146)。与共享 Drawable 录制场的组合接入见 [SURFACE_SESSIONS.md](SURFACE_SESSIONS.md#小视口来源与共享录制)。

`LumenViewportContentSource(source, regions)` 把已有内容源裁为所有取样区域的并集；
`LumenViewportContentSource(contentView, regions)` 则沿用引擎普通 View 源的自身滚动语义。
`regions` 同步提供 `(surface, insetX, insetY, padding)`，单位均是 surface 本地像素。
调用方应在 session 的 pre-draw 前调用 `sync()`，以便同帧更新移动区域及模糊余量。

源为 ViewGroup 时，小视口通过透明 overlay View 暴露纹理尺寸，宿主布局、可见性、alpha、
触摸及原内容均不改动。普通 View 保留原坐标与尺寸，只裁剪 Canvas。没有有效相交区域时
退回完整源区域。`close()` 只移除代理，可重复调用；关闭后同步及绘制不会使其复活。
原内容源由调用方拥有，关闭 wrapper 不会关闭它。

代理的 `isShown()` 委托真实 owner。Android 34 源码中 `ViewOverlay.OverlayViewGroup`
仅保存 `mHostView` 与 `mAttachInfo`，没有普通 `mParent`；`View.isShown()` 遇到空 parent
返回 false，因此普通 overlay 坐标 View 会被引擎的 hidden 门禁错误暂停。
同样，`View.transformFromViewToWindowSpace()` 只沿 `mParent` 遍历，故 overlay 的屏幕
坐标也没有 owner 的布局及祖先变换。这里只为坐标代理覆写 `View.getMatrix()`（该方法
在 Android 34 为可覆写的 public 方法），通过引擎现有 `ViewSamplingMatrix` 补回这一段。

设 owner 到屏幕矩阵为 O，overlay group 到屏幕矩阵为 G，裁剪原点平移为 C。
正常矩阵遍历会把代理的 left/top 作为 C 加到其 matrix 前。代理返回的矩阵为
`C^-1 · (G^-1 · O) · C`，所以最终全局矩阵为
`G · C · C^-1 · G^-1 · O · C = O · C`。
根部 `getLocationOnScreen` 校准仅恢复 G，未抵消 O。代理使用独立 mapper，避免外层
mapper 读取代理 matrix 时递归复用同一组 scratch 数组。屏幕上的代理仍完全透明；
硬件 onDraw 不回录 owner，软件 onDraw 才通过 `LocalViewDrawing` 读原生前景探针。

`dependencies` 保留原 source，epoch、availability、excludesSurfaces 均沿用原契约。
原 source 的 coordinateView 在 wrapper 生命周期内须固定；更换 anchor 后需重建 wrapper。
每个可能进入源录制的注入 surface 都必须列入 regions，源未声明排除 surfaces 且 region
在其子树内时，会报告 PROHIBITED，防止代理使原有自采样检查失效。
版本化且排除 surfaces 的源以原 contentVersion 为权威，避免 surface 重绘污染 owner dirty
而形成常驻录制循环；普通源保留 dirty/drawingTime 及自身滚动签名。代理 dirty 不参与版本，
相同 geometry 不重复 layout，也不安装或投递常驻帧回调。

JVM 回归覆盖区域并集、边界裁剪、向外取整、连续移动及上述矩阵等式；源码契约保护 overlay
可见性、软件探针、依赖和关闭出口。sample Android 回归覆盖 owner 可见性与恢复、带自身滚动
及偏移/缩放/旋转的像素等价、使用引擎矩阵把裁剪源画回 owner、原生探针与自定义源分离、
连续移动、source 版本/availability、反馈拒绝、provider/绘制异常恢复以及关闭恢复。
由于 sample 与 engine 分属不同模块，回归通过反射只访问 internal ViewSamplingMatrix，
生产代码无反射。

本能力吸收 LiJiaHua1024 在来源工程 `next` 分支的视口裁剪贡献；具体提交与本次 Apache-2.0 纳入授权事实见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 和 [NOTICE](../NOTICE)。宿主的页面、壁纸配方、Hook 和业务 View 识别继续由调用方持有。

建议验证：

```powershell
.\gradlew.bat :lumen-engine:testDebugUnitTest :sample:assembleReleaseAndroidTest :sample:assembleRelease --console=plain --no-daemon
```

存在设备时再运行 `:sample:connectedReleaseAndroidTest`，或按类筛选
`com.lumen.coacervation.sample.ViewportContentSourceTest`。编译 Android 测试不代表像素、
生命周期、实际采样后端或宿主页面已获得运行验收。
