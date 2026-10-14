# 1.4.4 修复与文档整理发布审阅

本次修正 1.4.3 真机复核中发现的两个既有缺陷、第一次 CI 在 API 31 模拟器上暴露的平台缺陷，并整理项目文档。源码基线为 `9320f40158dedfe61af35a86f1aadc02bfac1ef9`（1.4.3）；每项修复先复现（真机或 CI 模拟器），再修正、再验证，之后才推进下一步。

## 行为变化

- **以滚动容器为来源的采样**：会话以 `View.draw()` 录制来源，平台只在父级录制显示列表时施加 View 自身的滚动偏移，因此直接以 `ScrollView` 为来源时，模糊背景始终是滚动位置 0 的内容。新增 `geometry/LocalViewDrawing`，按父级显示列表的方式先平移再绘制；局部视效会话的来源录制、整页柔光透镜的内容录制、悬浮栏可读性探针统一使用它。未滚动的 View 走原来的 `draw()`，结果不变。
- **长按弹性资格**：命中控件能报告 `canScroll*` 时保留原生拖动。文字超出自身高度的普通 `TextView` / 按钮（高密度屏、大字号）也会这样报告，于是失去按压形变与高光。能通过触摸滚动的文本（`movementMethod`、可选中、输入框）此前已经排除，现在滚动检查对其余 `TextView` 不再生效。适配标准 §12.1 同步写明。
- **Android 12 的滤镜结果缓存**：API 31 的 HWUI 合成带 `RenderEffect` 的节点时，只在滤镜对象或局部裁剪变化时重算滤镜结果（AOSP `RenderNode::updateSnapshotIfRequired`），节点内容变了也继续合成第一次的结果；12L 起加入了层 generation 判断。引擎的模糊玻璃效果对象在几何不变时保持不变，所以在 API 31 上局部视效会话（含 Fluent 亚克力）的 GPU 路径、悬浮栏内容节点玻璃、Liquid 的 BLUR 后端都会停在第一次的模糊结果。新增 `glow/FilteredLayerRefresh`（只对 API 31 为真）：
  - `GlowChromeGlassApi31` 建效果时一并预建零偏移别名，之后每次重录在两者之间切换；
  - 会话共享内容重录后，在 API 31 上失效该来源的 GPU 代理，让玻璃重录；
  - `GlowBackdropTarget` 的后代失效与内容重录在 API 31 上通知 `GlowFloatingChrome` 让栏重录；
  - `LiquidBlurBackendApi31` 每次绑定新底图时切换，上次切换后尚未绘制则不再切换。
  
  别名只在建效果时创建，不逐帧新建 `RenderEffect`；其他 API 级别上新增分支不生效，行为与修正前相同。工程规则新增 §13。
- Fluent 沙盒恢复直接以 `ScrollView` 为来源。

## 复现与验证

| 问题 | 复现 | 修正后 |
|---|---|---|
| 滚动来源采样 | `ScrollSourceSamplingTest` 在未修正代码上 GPU 与强制软件两条路径均失败（滚动一段后仍为红色） | 两条路径通过（红 → 绿 → 蓝跟随滚动） |
| API 31 模糊玻璃不随内容更新 | 第一次 1.4.4 CI（`9594813`）的 API 31 冒烟中 `ScrollSourceSamplingTest` GPU 用例失败（滚动后仍为红色），软件用例通过。诊断运行在同一镜像（SE1A.220826.008）上确认：引擎已重录内容 2 次、玻璃 71 次而效果对象未变；换一个新玻璃立即变绿；平台探针中“同一模糊效果 + 重录”保持旧结果，“切换到零偏移别名”立即更新，API 33 / 34 全部更新 | 见下方“发布前 CI 诊断” |
| 文字溢出控件的弹性 | 既有 `MotionOwnershipRegressionTest` 在 1.4.2、1.4.3 与修正前均失败；逐级诊断确认 60px 高的 `TextView` 单行 66px，`canScrollVertically(1)` 为 true | 原测试未作修改即通过；同机 33 项交互/弹性设备测试全部通过 |

新增 `LocalViewDrawingContractTest`（3 项）、`ElasticTextOverflowContractTest`（2 项）与 `FilteredLayerRefreshContractTest`（5 项）守住写法；`ScrollSourceSamplingTest` 新增 `gpuGlassFollowsContentOnlyRecords`（API 31+，强制 GPU，轮询时不重绘目标，只靠引擎自身的内容更新路径），并改用既有的 `HardwareOutputRule`，让 ATD 镜像也提交真实像素。

## 文档整理

- 新增 `docs/README.md` 索引：现行规范、功能说明、参考与历史快照分开列出，写明维护规则；`VERSIONING.md` §5 引用它。
- `ENGINEERING_RULES.md` 补齐隔离类表（原表缺 `SurfaceCaptureApi31`、`DirectSurfaceProgramApi33`、`AnimatorDurationScaleApi33`、`ProceduralProgramApi33`）并给出核对命令；新增 §12“录制 View 内容”与 §13“带 RenderEffect 的节点（API 31）”。`SURFACE_SESSIONS.md` 与 `ARCHITECTURE.md` 写明 API 31 的重录例外。
- `API.md` 修正重复的 §12 与跳号，去掉过时的版本标题（只改节号与标题，声明不变）；`ARCHITECTURE.md` 去掉会过时的文件数，更新 `host`、`geometry`、`model` 的职责与可见性。
- 功能文档标题不再带版本号，改为“自 x.y 起提供”。`FLUENT_MATERIAL.md` 写明会话默认的失焦暂停会让应用内亚克力按背景不可用回退，以及关闭方式。
- 历史快照（各版发布审阅、计划与审查记录）未改写。

## 本地验证结果

| 检查 | 结果 |
|---|---|
| 全模块构建、JVM 测试、Sample release JVM | 904 项通过（1.4.3 为 894 项，新增 10 项），0 失败、0 错误、0 跳过 |
| 十模块 Lint | 0 错误、61 警告，与 1.4.3 相同 |
| Sample R8 | 通过 |
| 九模块 AAR、源码包与本地 Maven 发布 | 通过，全部 POM 版本为 1.4.4 |
| CI 指定七模块许可证与源码归档 | 通过 |
| API27 DEX | 52 处报告均在既有 `*ApiNN` 隔离类及其内部生成类；比 1.4.3 多出的 3 处是 `GlowChromeGlassApi31`、`LiquidBlurBackendApi31` 新增的 `RenderEffect` 字段，非隔离类新增代码 0 处 |
| Python 工具测试 | 通过 |
| 真机 sample 设备测试（OPPO PMA110，Android 17 / API 37） | 133 项中 131 项通过，余下 2 项见下节。该轮在加入 API 31 修正之前运行；修正的新增分支只在 API 31 上生效，API 37 上行为与修正前相同，新增的 GPU 用例由下方 CI 诊断在 API 33 / 34 上覆盖 |
| 真机 lumen-engine 设备测试 | 18 项通过 |

## 发布前 CI 诊断（API 31）

第一次 1.4.4 提交（`9594813`）的 CI 中，API 31 冒烟的 `ScrollSourceSamplingTest` GPU 用例失败，API 33 ATD 的两个用例因 PixelCopy 读不到真实像素而失败。在临时分支上做了以下诊断与验证（分支只含诊断工作流与诊断用例，验证后删除；引擎改动与本次提交相同）：

| 运行 | 内容 | 结果 |
|---|---|---|
| 38603642748 | 平台探针：同一视图里内容节点被玻璃节点引用，分别测试零偏移效果、模糊效果、重录、换新效果、切换零偏移别名；引擎计数与换新玻璃 | API 31：模糊效果在重录后仍为旧结果，切换别名后立即更新、切回原效果也更新；只含零偏移的效果不受影响。引擎计数为内容重录 2、玻璃重录 71、效果构建 0；换新玻璃立即变绿。API 33 / 34 所有情形都即时更新 |
| 38605633466 | 加入修正后的完整冒烟（API 27 / 31 / 33 ATD / 34） | 109 / 120 / 134 / 134 项全部通过，含 `gpuGlassFollowsContentOnlyRecords`（API 31+） |
| 38606203485、38607234472 | 示例主页（含悬浮栏）静止时的帧数，Material You 与 Liquid 两种材质 | API 31（Liquid 为 BLUR 后端，悬浮栏重录挂钩生效）、33、34 静止 3 秒均为 0 帧，滚动停止后仍为 0 帧，没有重录循环；对照：同一计数在滚动中的 250ms 内为 5–19 帧 |

## 同机仍失败的两项既有测试

两项在 1.4.2、1.4.3 上以相同数值失败，CI 模拟器通过；经诊断属于测试期望与该设备的差异，引擎行为正确，本次未修改测试或引擎：

- `LocalSurfaceIntegrationTest.linearGpuFadeHasUniformPixelCoverage`：渐隐剖面在顶部约 15% 保持 255，随后线性降到背景值 80。数值与“柔光既有色调增益（×1.08、+10）作用在预乘颜色上”逐点吻合（20% 处预测 244、实测 243；50% 处 182.7、实测 183）；测试期望按未预乘颜色计算。渐隐几何本身正确。
- `SurfacePixelContractTest.paddedExperimentCanReadTheOutsideMarkerAndReportsActualFiveBounds`：外扩实验确实读到了区域外的红色标记，但位置在 x≈10 而非断言的 x=1；标记与边距以 px 写死，折射位移以 dp 计，该设备密度为 4。

## 兼容与保留范围

没有新增或改变公开 API；`API.md` 只调整节号与标题。契约版本仍为 1，minSdk 27、构建与依赖钉点、shader、光学常数、默认材质与持久化协议保持不变。弹性资格的变化只让此前被误判的文字溢出控件参与；其他排除条件与所有既有测试期望不变。

## 验证边界

真机只覆盖一台 Android 17 设备；API 31 修正只在 CI 模拟器（SE1A.220826.008）上验证，其他 Android 12 构建若已带 12L 的修复，切换只多一次属性同步，滤镜结果本来就会重算。整页柔光透镜与悬浮栏探针改用 `LocalViewDrawing` 后，只在内容容器自身有滚动偏移时结果不同；该情形没有专门的设备测试，由与会话相同的写法和契约测试守住。悬浮栏玻璃与 Liquid BLUR 后端在 API 31 上的切换没有专门的像素测试，由契约测试和共享 `GlowChromeGlassApi31` 的 `ScrollSourceSamplingTest` 守住；只由 RenderThread 驱动、不经过 UI 线程失效的内容变化（例如水波纹动画）在 API 31 上要等到下一次 UI 线程失效才会反映到玻璃上。发布状态与实际产物校验以对应 Release 的 `VERIFICATION_1.4.4.json` 和 `SHA256SUMS.txt` 为准；本文不预先声明远端测试、附件上传或 JitPack 已成功。
