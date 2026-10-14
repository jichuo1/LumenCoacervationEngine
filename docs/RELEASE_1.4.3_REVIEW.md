# 1.4.3 Fluent 亚克力与层级发布审阅

本次新增可选的 Fluent 亚克力与层级控件效果 `LumenFluentControl`。源码基线为 `fbeaf254e439e089a009eceb7a9bee00d87f2512`（1.4.2 发布后的 main）；调研、策略、绘制、控制器、文档与设备验证各步完成后独立 review，再推进下一步。

## 行为变化

- 新增 `host.LumenFluentControl` 及选项类型：按 WinUI `AcrylicBrush` 配方为任意 View 绘制应用内亚克力（不透明回退底 → 模糊背景 → 亮度混合 → 色调混合 → 2% 噪点 → 167ms 回退交叉淡化），叠加 Fluent 层级描边、悬停/按下轻填充、键盘焦点双环与平台阴影。
- 模糊背景复用宿主的 `LumenSurfaceSession`：新增内部只绘制 View 绑定与无分配后端读取，同一来源 View 共用一次录制；未改动任何既有公开入口或既有绑定路径。
- 回退：禁用、降低透明度、低内存设备、省电、可选的窗口失焦，以及背景 120ms 内无帧；均只影响本控件。会话表面数超出预算时本控件显示回退色，页面不受影响。
- API 29+ 使用平台 `BlendMode.LUMINOSITY` / `COLOR`（隔离在 `AcrylicBlendApi29`）；API 27–28 或 `exactBlend = false` 使用源覆盖近似。噪点为进程共享 64×64、16 KiB 常量预算位图，从不 `recycle()`。
- Sample 新增“表面沙盒 → Fluent 亚克力与层级”页面与 6 项设备回归测试。

## 数值来源

配方常量、主题默认值、描边与填充令牌、圆角和过渡时长取自 microsoft-ui-xaml 源码（`AcrylicBrush.cpp/.h`、`AcrylicBrush_themeresources.xaml`、`Common_themeresources_any.xaml`、`CornerRadius_themeresources.xaml`）；层级数值与回退条件取自 Microsoft Learn 的 Acrylic、Mica、Materials、Layering、System backdrops 页面；Avalonia 的 Skia 实现用于交叉核对。Mica 默认参数未公开，本轮不实现。完整对照见 `FLUENT_MATERIAL.md`。

## 逐步复审

- 策略层 17 项 JVM 测试，期望值按 WinUI 公式逐项手算（修正系数、亮度推导、主题默认值、模糊半径换算、缓动、回退优先级、过渡打断、可用性滞回、参数边界）。
- 契约层 12 项：噪点预算与确定性、不回收共享位图、`BlendMode` 只出现在 API ≥ 29 的隔离类、绘制路径零分配、先画不透明底再画背景、录制期间不画绑定、只恢复自己持有的状态、监听移除路径、预算拒绝局部降级、同来源共用录制、单色描边透明度。
- 复审中修正：每帧无条件 `unscheduleSelf` 改为只在确有待确认丢失时调度；首版契约测试把既有 `SurfaceCaptureApi31` 误判为违规，改为按规则校验 `*ApiNN`（NN ≥ 29）。
- 真机复核发现并修正：单色描边（禁用态）被 `Paint.setAlpha` 覆盖成不透明黑边，已改为保留令牌透明度，并新增 JVM 契约与设备像素回归；沙盒页在 targetSdk 37 强制全屏下按系统栏让位，文字随调色板切换。
- 真机复核还发现：把 `ScrollView` 本身作为来源时，滚动后模糊背景停在旧位置。原因是会话以 `View.draw()` 录制来源，不含来源自身滚动偏移（平台只在父级录制显示列表时施加）。这是会话录制的既有行为，本次未改变；沙盒改为以滚动容器的父容器为来源并调用 `notifyScrollPositionChanged`，`FLUENT_MATERIAL.md` 写明接入方式。

## 本地验证结果

| 检查 | 结果 |
|---|---|
| 全模块构建、JVM 测试、Sample release JVM | 894 项通过（1.4.2 为 865 项，新增 29 项），0 失败、0 错误、0 跳过 |
| 十模块 Lint | 0 错误、61 警告，与 1.4.2 相同；新增代码无 Lint 结果 |
| Sample R8 | 通过 |
| 九模块 AAR、源码包与本地 Maven 发布 | 通过，全部 POM 版本为 1.4.3 |
| CI 指定七模块许可证与源码归档 | 通过 |
| API27 DEX | 49 处报告均在既有 `*ApiNN` 隔离类及其内部生成类，新增代码 0 处 |
| Python 工具测试 | 26 项通过 |
| 真机（OPPO PMA110，Android 17 / API 37，Adreno） | `FluentControlIntegrationTest` 6 项通过；后端 GPU、混合 EXACT；人工核对浅色、深色、降低透明度、BASE/ACCENT 与滚动跟随画面 |

同机运行既有 `LocalSurfaceIntegrationTest` 时，线性渐隐像素断言在第 1/10 处得到 255（期望约 237.6）。未修改的 1.4.2 基线在同一设备上以相同数值失败，属于该设备既有差异，与本次改动无关；CI 模拟器结果以发布验证附件为准。

## 兼容与保留范围

只新增公开类型，未删除或改变既有公开声明；`LumenSurfaceSession` / `LumenSurfaceBinding` 只增加 `internal` 成员。契约版本仍为 1，minSdk 27、构建与依赖钉点、shader、光学常数、默认材质与持久化协议保持不变。新增公开 API 按规则属于次版本，本次按用户明确指定编号为 1.4.3（见 `VERSIONING.md`）。

## 验证边界

真机只覆盖一台 Android 17 设备；其他厂商 GPU 的高级混合模式、阴影观感、长时间功耗与原生内存未验证。API 27–28 的近似混合不宣称与精确混合等价。省电模式只在挂载、窗口焦点变化与 `update()` 时重读。Android 没有公开的“降低透明度”系统开关，需宿主传入。

发布状态与实际产物校验以对应 Release 的 `VERIFICATION_1.4.3.json` 和 `SHA256SUMS.txt` 为准；本文不预先声明远端测试、附件上传或 JitPack 已成功。
