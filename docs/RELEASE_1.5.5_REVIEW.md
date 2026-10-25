# 1.5.5 通用滑块与手风琴形变复核

本轮新增可选通用滑块 `LumenSlider`（lumen-motion），并收录发布后对多级手风琴首级标题长按形变的两项修复。没有新增生产依赖，没有改变既有控件的默认视觉或手势判定。

## 采用项与逐步 review

1. **滑块形态与外观**：胶囊（亮度条式，填充即数值）与细轨两种形态；`Skin`（柔光 / Liquid，槽体是 `SELECTED_ITEM` 表面）、`Fluent`、`Mica`、`Prism`、`Plain` 五种外观，全部只经引擎公开入口取材质。Fluent 令牌与内点动画取自 microsoft-ui-xaml（commit 8463f451）`Slider_themeresources.xaml` 与 `Common_themeresources_any.xaml`，由 JVM 测试逐项守住。
2. **纯策略**：值映射、吸附与磁吸、RTL、有理橡皮筋、键盘步长、刻度可见性、HDR 增益、气泡位置、Prism 胶囊包裹与镜片收边都在不依赖 `android.graphics` 的 `LumenSliderPolicy` 中；手势判定为 `LumenSliderGesture`。
3. **手势归属**：横向超过 slop 才接管并禁止父级拦截；竖向先动让给滚动父级。引擎长按弹性与 `LumenPagePager` 按类型自动排除滑块（与 `SeekBar` 同等），宿主无需 `EXCLUDED_TAG`。
4. **HDR 高光**：`SliderHdrHighlightApi34` 隔离显示器余量读取与扩展范围渐变，只在按下时读取、增益或半径变化时重建 Shader。窗口 `COLOR_MODE_HDR` 由宿主决定，引擎不改窗口设置；未开启时平台按 SDR 钳制。柔光 / Liquid 与 Plain 默认开启，Fluent / Mica / Prism 默认关闭。
5. **Prism 镜片不采样背景**：引擎玻璃要求采样来源完全覆盖玻璃（回退色不透明），而滑块轨道层大部分透明，真机上采样结果在浅色主题为白块、深色为黑块。镜片改为把轨道内容画进滑钮、由滑钮缩放放大，轨道以外透出真实背景；亮边、内阴影、表面色、按压光与外阴影读 `LumenPrismOptions`（默认 `Prism.LENS`），外阴影用 `LumenControlShadow`。不做 AGSL 折射与色散，`Prism` 外观不需要会话。
6. **真机复核后的修正**：Prism 默认细轨、钮 30×20dp；胶囊形态钮约 1/4 露在填充外、满值时填充包住钮；按住放大时镜片中心随缩放向内收，两端不伸出槽外；柔光高光按填充圆头裁剪；Skin 空槽叠淡前景色；把手附近不画刻度。
7. **手风琴**：多级手风琴首级标题按可见段形变，朝被藏起一侧的行程不再被视口外邻居压短（见 CHANGELOG 修复项）。

## 工程规则核对

- §2：唯一的高版本引用在 `SliderHdrHighlightApi34`，调用处均有可被 lint 识别的 `SDK_INT >= 34` 判断；`ENGINEERING_RULES.md` §2.3 已登记。DEX 审计无滑块相关项。
- §3：无反射；§4：公开入口主线程；§5：`draw` / 帧回调 / `updateDrag` 零分配，静止时无帧回调，由 `LumenSliderContractTest` 守住。
- 值变化（事件路径，非逐帧）会格式化一次文案并测量宽度。

## 验证

- 本地完整门禁 `assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug` 通过；JVM / 源码契约测试 1067 项无失败；各模块 Lint 0 错误、滑块 0 警告；主机脚本测试通过。
- OPPO PMA110（Android 17 / API 37）：`LumenSliderIntegrationTest` 9 项全部通过；相关回归 `DemoSmokeTest`、`MotionOwnershipRegressionTest`、`PageGestureOwnershipTest`、`PrismIntegrationTest`、`FluentControlIntegrationTest`、`MicaIntegrationTest` 共 35 项通过。各外观在浅色、深色、RTL、禁用、按压、越界拉伸与满值按住下的画面经截图与录屏逐帧人工核对。
- `LumenSliderIntegrationTest` 的 9 项登记为各 API smoke 必测。远端 API 27/31/33/34 全量矩阵结果以发布流水线生成的 VERIFICATION 与 SHA256SUMS 为准。

## 边界

- HDR：PMA110 本次测得的 HDR/SDR 余量为 1.00，没有在真机上验证超过 SDR 白的实际亮度；模拟器不代表 HDR 显示。
- Prism 镜片是放大近似，不是物理折射；与卡片用的 `LumenPrismGlass` 观感接近但不等价。
- 其他 OEM 的触觉反馈强度、HDR 面板行为与功耗需在目标设备验收。

公开 API 的新增属于本次明确指定 1.5.5 的编号例外；CONTRACT_VERSION=1、minSdk=27、存储名、schema 与既有默认选项保持。
