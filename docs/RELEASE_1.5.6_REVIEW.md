# 1.5.6 材质浓度与功耗优化复核

本轮新增可选的材质浓度（`docs/MATERIAL_OPACITY.md`），并收录 1.5.5 之后的性能与功耗优化：透镜与模糊内核提速、Baseline Profile、主线程采样开销，以及高级材质实时档的静止功耗与玻璃区域判定（REFRACTION、BLUR）。没有新增生产依赖；不调用材质浓度的宿主画面与 1.5.5 相同。

## 发布前审阅

审阅范围是材质浓度的 17 个提交（`a0bcfaf`…`2d18211`，48 个文件），重点核对默认路径、性能与功耗、兼容性，以及它与实时档玻璃区域判定的关系。

### 默认路径（p = 1）

- `MaterialOpacityState.frame` 在 p = 1 时返回 null，各 Drawable 走原路径；Liquid 渲染器在没有逐表面浓度时使用全 1 的默认帧，新增的乘数全是 `× 1f`，浮点上精确。
- 折射后端只在乘数变化时重写共享 shader 的 uniform；默认帧不写任何 uniform。
- GPU 节点玻璃：默认档位仍是原来那一条效果链，档位缓存只在宿主用过材质浓度后才建立。
- 柔光渲染器与软件透镜：p = 1 时的整数 alpha 运算与之前相同，模糊比例为 1 时不触发重模糊。
- **审阅中修正**：局部表面的静态回退色调写成 `a + (b − a)·w`，w = 1 时浮点上不一定等于 b；软件与原始采样层的 alpha 由 `(255·α)·opacity` 变成了 `255·(α·opacity)`。两者都可能让个别取值的取整差 1。已改为 w = 1 时直接取回退色调、alpha 保持原求值次序。

### 性能与功耗

- 逐帧只写浮点与 uniform；setter 路径没有 `RenderEffect` / `RuntimeShader` / 位图 / `Paint` 构造（`MaterialOpacityContractTest` 守住）。
- 模糊档位预热只对调用过材质浓度的玻璃启动，每次 GPU 绘制编译一档、约 7 帧后结束；离屏表面不续帧。停手计时只投递一次、到点自行补等。
- 背景权重为 0 的局部表面停止录制与采样；回升后经现有的来源恢复路径补采。
- 与实时档玻璃区域判定（`719e182`）：所有乘数都在 [0, 1] 内，折射位移只会变小，`LiquidGlassFootprintPolicy` 的上界仍然成立；BLUR 与节点玻璃只按背景权重改 alpha，不改变读取范围。
- 新增设备测试 `MaterialOpacityRegressionTest`（登记为各 API smoke 必测）：回到 1 逐像素恢复默认外观；在 0.35 停手后与回到 1 后窗口零帧；透明的装饰性表面滚动时录制计数为 0，回升后恢复。

### 兼容性

- **公开 ABI**：下载 1.5.5 Release 的九个 AAR，与本版构建逐类比对 `javap -public -s` 描述符：lumen-engine 新增 346 个成员、lumen-motion 新增 34 个，其余七个模块不变；没有删除或改变任何公开成员（列出的 15 处差异全部在 `internal` / `private` 类中）。
- **`GlowEngine.setMaterialOpacity`**：带默认实现；Kotlin 2.4 默认生成 JVM 默认方法，旧的 Java / Kotlin 实现无需重编。契约版本仍为 1。
- **API 级**：DEX 审计（`tools/audit_checkcast.py`，minSdk 27）的全部结果都在 `*Api31/33/34` 隔离类里，没有普通类无条件引用高版本类型。
- **审阅中修正**：`LumenSurfaceBinding.setMaterialOpacity(value)` 原先把可读性重置为 `AUTO`，作用域驱动时会抹掉宿主声明的 `DECORATIVE` / `TEXT`；现在与 Fluent、Prism 一致保留最近一次声明。`MaterialOpacityContractTest.scopeDrivenUpdatesKeepTheDeclaredLegibility` 与上述设备测试守住（换回旧实现时设备测试失败）。

## 验证

- 本地完整 CI 任务图 `assembleDebug testDebugUnitTest :sample:testReleaseUnitTest lintDebug bundleReleaseAar sourceReleaseJar :sample:minifyReleaseWithR8 -Plumen.verifySampleR8=true` 通过；七组许可证检查通过。
- 主机脚本测试 54 项中 49 项本地通过；其余 5 项需要 Python 3.11 的 `hashlib.file_digest`，本机为 3.9，由 CI 执行。
- 设备（本机模拟器）：API 35 引擎 25/25、示例 217 例；API 31 引擎 12/12、示例 201 例。`MaterialOpacityRegressionTest` 3 项在两个 API 上都通过。示例中的失败都在基线上同样出现：两个 API 都有 API 29 以下才满足的 `DrawableSceneInstrumentedTest` 假设跳过；API 35 另有两例横向展开归属用例（`MotionOwnershipRegressionTest`、`RevealHorizontalOwnershipTest`，间歇）；API 31 另有 `EnhancedSurfaceIntegrationTest.fusedSelectorKeepsBusinessSelectionAndRestoresOnlyOwnedResources`（在 `2d18211` 基线上复跑仍失败，远端 API 31 矩阵中通过）。
- 远端 `2d18211` 的 API 31 smoke 曾出现一次 `NativeComposeIntegrationTest.heldHorizontalDragMovesMaterialAndContentAndReleasesWithoutClicking` 失败（220ms 按住后才移动的时序用例）；本机 API 31 上通过，`340c749` 的远端矩阵也通过。
- 远端 API 27/31/33/34 全量矩阵结果以发布流水线生成的 VERIFICATION 与 SHA256SUMS 为准。

## 边界

- 材质浓度的设备像素与功耗验证只在模拟器上进行；真机 GPU 上的档位切换耗时与预热帧耗时（PMA110）未测，模拟器帧时间没有参考价值。
- 悬浮栏的内容节点玻璃与液态 BLUR 后端仍是固定半径淡出（`MATERIAL_OPACITY.md` §13）。
- 液态实时档在 p = 0 时仍继续截图：清玻璃的折射边缘需要背景，与 1.5.5 持平。

公开 API 的新增属于本次明确指定 1.5.6 的编号例外；CONTRACT_VERSION=1、minSdk=27、存储名、schema 与既有默认选项保持。
