# 1.5.0 Prism 棱镜玻璃与液态选中框发布审阅

源码基线为 `7fe96e1`（1.4.6 之后的 main，含已合入但未发布的控件软阴影、长按弹簧规格与橡皮筋曲线、液态形变磁贴）。本次新增 Prism 棱镜玻璃与底栏液态选中框，并把上述未发布能力一起发布为次版本 1.5.0。

## 行为、来源与兼容性

- **Prism 棱镜玻璃**（`LumenPrismGlass`，lumen-engine）：控件级液态玻璃，配方取自 Kyant0/AndroidLiquidGlass（`65ab177e`，Apache-2.0）——饱和度 → 模糊 → 圆角矩形边缘折射（可选纵深、色散）→ 表面色 → 按压光晕 → 内阴影 → 角度分布高光，外加高斯外阴影；每层独立开关与调参（`LumenPrismOptions` 及 8 个子选项）。背景复用 `LumenSurfaceSession`：绘制专用绑定新增内部棱镜效果链，配方为 null 时 Fluent 与既有材质的链逐字不变。
- **底栏液态选中框**（`LumenNavigationBar.setPrismSelection`，lumen-motion）：选中色副本（ALPHA_8 遮罩）在框内绘制、框外真实项被裁掉，选中色以框边为界；按下膨胀与 X/Y 不同阻尼的果冻弹簧、拖动速度挤压、框内放大与折射/色散、高光、内外阴影。`null`（默认）时经典路径与此前逐帧一致。
- **命名**：高级材质占用 `SkinId.LIQUID`（`liquid_v1`）与 `LumenSurfaceMaterial.LIQUID`；本材质统一叫 Prism，不新增 `SkinId`，不改变持久化协议。
- **深浅色**：按 `palette.background` 亮度判定（与 Fluent、Mica、委托一致），默认表面色 #FAFAFA / #121212 @ 40%，`updatePalette` 即时生效；选中框静止底沿用宿主 SELECTION 背景（选中遮罩强度）。
- **与上游的差异**：单一半径钳到短边一半（上游按未居中坐标选角）；零向量不 normalize（上游中心线 NaN）；色散 0–1 连续、合成结果钳为合法预乘颜色；折射量钳到短边一半。
- 新增公开 API 按 `VERSIONING.md` 升次版本，无编号例外；`GlowEngine`、持久化协议、契约版本 1、minSdk 27 保持。

## 审阅与修正

每一步都做了独立审阅（三视角：渲染正确性、会话与既有材质耦合、API 与可调性 / 性能与生命周期），审出并在发布前修正：

- 玻璃若被控件软阴影包装，`invalidateSelf`/`scheduleSelf` 会被 `View.verifyDrawable` 丢弃（按压光、回退淡入、背景丢失复检全部失效）——改为玻璃自己就是 View 背景、自绘高斯外阴影（只画轮廓外）。
- `fallbackOpacity` 原先无效；改为中性底上叠表面色，并新增 `fallbackColor`。
- 回退过渡中表面色被衰减两次；高光颜色 alpha 在 API 33 未预乘；色散合成可能产生非法预乘颜色；大折射量可越出录制节点——均已修正。
- 选中框：标签在副本中缺失（单行居中 TextView 通过自身 `scrollX` 定位，设备截图发现）；可读性光晕与常规色透明度混入副本；可读性变化不重绘副本；遮罩每次布局都重建并在绘制中分配；模糊遮罩阴影逐帧重新模糊；shader 在绘制中编译且无失败隔离；帧源触发宿主 `onVisualMovement` 并重复更新触点光晕；翻页器滑动也会挤压选中框——均已修正。
- 设备首轮发现 `ValueAnimator.start()` 同步回调导致帧源递归栈溢出——发布帧源后再启动，并以源码契约守住。
- **既有缺陷（1.4.6 已存在）**：API 31 模拟器（约 20fps）上 Demo 手风琴冒烟用例挂起约 35 分钟后以 `Cannot round NaN value` 崩溃，干净的 1.4.6 源码同样复现。诊断日志显示进度弹簧速度每 240 帧翻倍：步长钳到 0.05s 时半隐式欧拉的迭代矩阵特征值约 −1.006，发散。改为 1/60s 子步积分；≤ 1/60s 的帧仍恰好一步（逐位一致，有 JVM 测试守住），长帧收敛。修正后同一用例 13 秒通过。

## 验证

- JVM：`PrismPolicyTest`（10）、`PrismContractTest`（8）、`NavigationBarPrismTest`（11），另有手风琴弹簧 2 项，合计新增 31 项；本地完整门禁 1002 项 JVM／源码契约，0 失败、0 错误、0 跳过；十模块 Lint 0 错误，警告与基线相同（差异仅为依赖新版本提示，取决于联网状态）。
- 冒烟必测：`PrismIntegrationTest` 三项登记为全 API 必测，GPU 表面色合成登记为 API 31+ 必测（`verify-smoke-results.py`，29 项脚本测试通过）。
- DEX（API 27）：高版本类型只出现在 `*ApiNN` 隔离类；新增 `PrismLensApi33`、`PrismHighlightApi33`、`PrismSelectionShaderApi33`，非隔离类新增代码 0 处。
- 本地模拟器：
  - API 31（Android 12，无折射路径）：`PrismIntegrationTest` 4/4 通过；截图核对浅色/深色的静止与按下（玻璃、表面色、选中色以框边为界、按下膨胀与放大、阴影）。
  - API 35（Android 15，完整路径）：`PrismIntegrationTest` 4/4 通过；诊断 `backdropBackend=GPU`、`lensActive=true`（卡片与底栏，浅色与深色），截图可见边缘折射与按下选中框的折射放大。
- 本地完整设备套件（release 构建，逐项 XML）：
  - API 35：168 项，166 通过；`DrawableSceneInstrumentedTest.pre29…` 为 API 29 以下专用的预期 assumption 跳过；`MotionOwnershipRegressionTest` 的横向揭示取消用例在本模拟器上时序不稳（复跑通过，干净 1.4.6 同样失败）。
  - API 31：153 项，149 通过；除同一 assumption 跳过外，`MotionOwnershipRegressionTest` 两项与 `EnhancedSurfaceIntegrationTest.fusedSelector…` 一项在本模拟器上失败，干净 1.4.6 源码在同一模拟器上同样失败（API 35 与 CI 冒烟通过），不是本次回归。
  - 手风琴修正前 API 31 套件在第 20 项崩溃中止；修正后完整执行。
- 第一次 1.5.0 提交（`728737a`）的 CI 冒烟（模拟器 320×640、160 dpi）发现两处并修正：
  - API 27（OpenGL 渲染管线）上棱镜选中框框内副本未着色：ALPHA_8 `BitmapShader` 在该管线不取画笔颜色。API 33 以下改为按选中框裁剪后 `drawBitmap` 遮罩（各管线都取画笔颜色）。
  - `ControlShadowPixelTest.shadowFadesOutside…` 自 `2009057` 起在全部 CI API 上失败（main 的 Demo 冒烟已连续失败）：约 1dp 的贴边接触带在 mdpi 上只有 1 像素，连续性阈值按像素写死为 8。改为按 dp 计（`max(8, 16 / density)`），高密度设备上阈值不变，阴影实现未改。
- 发布状态与产物校验以对应 Release 的 `VERIFICATION_1.5.0.json` 与 `SHA256SUMS.txt` 为准；本文不预先声明远端测试、附件上传或 JitPack 已成功。

## 验证边界

- 真机未参与本轮（手机未连接）；API 33+ 的折射与色散在 API 35 模拟器（SwiftShader）上验证，未覆盖厂商 GPU 驱动差异。AGSL 编译失败时玻璃退回会话失败路径、选中框只关闭折射，不影响页面。
- 选中框运动时栏会逐帧重录显示列表（副本与裁剪在栏的显示列表里），栏背景随之重画；静止时零工作。
- 浅色主题里加色高光与按压光在浅色内容上几乎不可见（与参考库一致），玻璃边缘主要靠外阴影区分。
