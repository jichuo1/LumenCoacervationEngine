# 云母材质与内容分层（`LumenMicaBackdrop`）

Mica 是窗口的基础层：将背景图的低频色彩融入不透明表面，并通过明暗主题、失焦回退和内容分层维持可读性。引擎提供可选的 `BASE` / `BASE_ALT`，与已有应用内亚克力配合：云母用于长期存在的窗口背景，亚克力可继续用于临时菜单与浮层。

## 1. 资料与核对结论

2026-10-15 查阅、交叉核对以下官方文档与实现。适用平台是 Android View，Windows API 用于确认设计和行为，不能直接移植系统合成器。

| 来源 | 核对内容与实现决定 |
|---|---|
| [Microsoft Learn：Mica material](https://learn.microsoft.com/zh-cn/windows/apps/design/style/mica) | 不透明、壁纸而非窗口后方应用内容、只采样一次；窗口底层只铺一次；失焦、省电、低端设备和降低透明度回退。Alt 的壁纸色彩更鲜明，并增加命令层。 |
| [Materials in Windows](https://learn.microsoft.com/en-us/windows/apps/design/signature-experiences/materials) | Mica 支持明暗与活动状态；Acrylic 用于临时浮层；不为 Mica 额外添加流动高光、折射或实时背景采样。 |
| [Apply Mica or Acrylic materials](https://learn.microsoft.com/en-us/windows/apps/develop/ui/system-backdrops) | `MicaKind.Base` / `BaseAlt`；可定制 FallbackColor、TintColor、TintOpacity、LuminosityOpacity；定制后主题跟随由调用方承担。 |
| [Apply Mica in Win32](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-mica-win32) | 控制器持有背景和策略状态；失焦转为中性底色。 |
| [WinUI Mica/Desktop Acrylic 设计说明](https://github.com/microsoft/microsoft-ui-xaml/blob/main/docs/design-notes/mica-desktop-acrylic.md) | Windows Mica 依赖系统合成器访问壁纸，不能用模糊应用内内容来等同替代。 |
| [Elevation in Windows](https://learn.microsoft.com/en-us/windows/apps/design/signature-experiences/layering) | 基础层、内容层及卡片的层级关系；描边和阴影按需使用。 |
| [WinUI `Common_themeresources_any.xaml`](https://github.com/microsoft/microsoft-ui-xaml/blob/main/controls/dev/CommonStyles/Common_themeresources_any.xaml) | 直接核对 Base、BaseAlt 的回退色，以及内容层、Alt 命令层的 ARGB 令牌，见下表。 |
| [Android `WallpaperManager`](https://developer.android.com/reference/android/app/WallpaperManager) | Android 13 普通应用不能可靠取得当前壁纸，Android 14 起 `getDrawable()` 会抛权限异常；引擎不读取壁纸、不请求存储或所有文件权限，由宿主明确提供背景。 |

公开材料不足以证明 Windows Mica 的完整默认合成配方。因此本实现是**遵循 Mica 用途与行为的 Android 适配**：保留静态背景的空间色彩、不透明输出、主题、分层及策略回退；不宣称与 DWM 逐像素等价，也不宣称具有 Windows 多屏壁纸坐标跟随能力。

## 2. 配方与预算

1. 宿主在后台调用 `LumenMicaImage.prepare(readableBitmap)`，等比缩至最长边 96 像素，透明像素先铺在不透明灰底上，再复用既有三轮双向盒式模糊。半径为采样短边的 1/6，限制为 1–16 像素。该步骤不读取文件、Uri 或当前页面。
2. 窗口接收不可变快照，按主题对低频 RGB 做亮度偏移和色调源覆盖。着色只在背景、颜色参数或主题变化时重建；所有 Android 版本使用相同 CPU 配方，不需要高版本混合模式。
3. 绘制先铺不透明底，再按中心裁剪绘制静态材质。调整窗口尺寸只更新采样矩阵，普通绘制和滚动不重新模糊或采集。
4. 使用引擎已有的可打断 Fluent 过渡：活动／失焦回退在 167ms 内淡化，反转从当前权重继续。这个时长是引擎适配选择，不是微软公开的 Mica 常量。降低动态效果或系统关闭动画时立即切换；高对比度和降低透明度立即显示纯色。

| 令牌 | 浅色 | 深色 | 来源 |
|---|---|---|---|
| Base 回退 | `#FFF3F3F3` | `#FF202020` | WinUI `SolidBackgroundFillColorBase` |
| BaseAlt 回退 | `#FFDADADA` | `#FF0A0A0A` | WinUI `SolidBackgroundFillColorBaseAlt` |
| 内容／卡片层 | `#80FFFFFF` | `#4C3A3A3A` | WinUI `LayerFillColorDefault` |
| Alt 命令层 | `#B3FFFFFF` | `#733A3A3A` | WinUI `LayerOnMicaBaseAltFillColorDefault` |
| Base 色调覆盖 | 0.88 | 0.80 | 引擎适配值 |
| BaseAlt 色调覆盖 | 0.72 | 0.60 | 引擎适配值；保留更强背景色彩 |
| 亮度偏移权重 | 0.65 | 0.65 | 引擎适配值 |

- 单张快照不超过 **36 KiB**；预处理期间图像和像素缓冲累计不超过 **144 KiB**（不含宿主输入和图形驱动开销）。每个窗口正常持有原始模糊快照和着色副本，合计不超过 **72 KiB**，快照可在多个窗口共享。
- 切换主题时旧显示列表可能仍引用上一张着色位图，因此只丢引用，不能主动 `recycle()` 已发布的像素；上述 72 KiB 是当前持有量，不是切换瞬间的进程峰值或显存承诺。
- 无新增生产依赖、逐帧分配、离屏层、程序化动画或轮询。同步 CPU 预处理由宿主管理；可选 `LumenMicaPreparer`（自 1.5.2） 使用随 owner 关闭的独立工作线程，示例已接入该接口。
- `LumenEngine.releaseGraphics()` 清除该窗口对快照和着色副本的引用，回退为 `MEMORY_PRESSURE`。之后 `updatePalette()` 不会自动恢复背景；宿主用 `setImage()` 显式恢复。宿主自己缓存的 `LumenMicaImage` 由宿主释放引用。

## 3. 接入与可选性

```kotlin
// 后台线程：一次准备；输入必须是可读的软件 Bitmap。
val image = LumenMicaImage.prepare(backgroundBitmap)
// 返回后可由宿主按自己的归属规则归还输入，image 不持有 backgroundBitmap。

// 主线程：在窗口底层安装一次，子容器透明处可看到云母。
val mica = LumenMicaBackdrop(root, palette,
    LumenMicaOptions(kind = LumenMicaKind.BASE_ALT), image)
commands.background = mica.layerBackground(LumenMicaLayer.COMMAND, radiusDp = 0f)
content.background = mica.layerBackground(LumenMicaLayer.CONTENT, radiusDp = 0f)
card.background = mica.layerBackground(LumenMicaLayer.CARD)

// 主题／辅助功能变化：宿主显式转发，并重新生成上面的子层背景、更新文字颜色。
mica.update(options.copy(reduceTransparency = reduceTransparency, highContrast = highContrast))
mica.updatePalette(newPalette)
// 背景更换：后台准备新的 image，只把最新代次结果投递回主线程。
mica.setImage(newImage)

// onDestroy：关闭监听并恢复仍由本实例持有的原始背景。
mica.close()
```

- 这是与 `LumenFluentControl` 一致的独立可选入口，**不新增或更改 `SkinId` 持久化协议**，不自动给现有宿主换材质。`enabled = false` 使用不透明回退，`close()` 恢复原背景。
- 每窗口选择一个根容器作为云母基础层。不要同时让 `LumenActivityDelegate.bindRoot()` 和 `LumenMicaBackdrop` 接管同一 View 的背景；需要原有控件材质时，使用局部表面／Fluent 控件，或让它们位于独立的内层容器。
- 同一根 View 重复安装会被拒绝；先关闭旧实例，再安装新的。不要给每张卡片重复安装 Mica，卡片使用 `layerBackground()`，其中仅卡片附带 1dp 内侧描边。
- 没有图像时显示 `NO_BACKGROUND` 纯色回退。示例使用程序生成的原创渐变背景，便于对比静态色彩；真实宿主应传入已获授权的壁纸／背景图，避免传入不断变化的页面截图。
- `prepare()` 不是解码接口：输入图像尺寸、解码和调用方并发由宿主管理。不要为动态内容每帧调用；输入在调用期间不得被修改或回收。后台取消通过线程中断传播；关闭后投递的 setter 为空操作，较早背景的迟到结果仍需由宿主代次门控。
- `layerBackground()` 是创建宿主所有的 Drawable 的工厂；主题、kind 和辅助功能变更不会自动修改已经返回的对象。setter、刷新和 `close()` 在关闭后为空操作；诊断仍可读取。
- 文字、图标、点击、焦点及无障碍语义仍由宿主负责。高对比度使用 `palette.background`，不让自定义回退色覆盖辅助功能配色；Android 的高对比度与降低透明度偏好由宿主显式转发。
- `fallbackColor` 只改变回退底色，不改变完全活动时的背景色调或亮度；活动材质使用独立的 `tintColor`、`tintOpacity` 和 `luminosityOpacity`。

### 3.1 可选异步预处理（自 1.5.2）

```kotlin
// 在主线程创建，随本页面/窗口关闭；创建对象本身不启动工作线程。
val preparer = LumenMicaPreparer()
preparer.prepare(
    source = LumenMicaImageSource {
        // 示例为自行生成的独占输入。实际静态背景的有界解码也应放在此工厂中。
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(backgroundColor) }
    },
    listener = LumenMicaPreparationListener { image, failure ->
        // 总是异步主线程执行；image 为 null 时由现有 Mica 控制器显示安全回退。
        mica.setImage(image)
    }
)
// 不再需要当前结果，但仍会继续使用本 preparer。
preparer.cancel()
// onDestroy：丢弃待处理来源/结果，抑制迟到回调，并中断、关闭自己的工作线程。
preparer.close()
```

- **同步接口借用输入，异步接口接收独占输入的所有权。** `load()` 只在真正开始工作时调用；被替换或取消的待处理工厂不会执行。不要在工厂外提前打开文件流、创建需要关闭的资源或解码位图。工厂抛异常前仍拥有已获取的资源，负责自行清理；成功返回后由引擎在 finally 中回收输入。
- 每实例至多一个执行中的预处理、一个最新待处理工厂和一个最新未交付结果；结果通知同样合并，避免主线程暂时繁忙时积压快照。预处理复用原 `prepare()` 的 96px、模糊配方和工作集上限，不改像素效果；待交付快照不超过 36 KiB，源图解码大小仍须由宿主有界控制。
- 新 `prepare()` 抑制旧代次回调；`cancel()` 清空待处理请求和未交付结果，允许之后重用。正在执行的工厂不会被强行回收输入，可以完成后释放；`close()` 另外中断工作线程。工厂必须有界且响应中断，不能承诺停止一个自行忽略中断的阻塞宿主操作。
- 主线程通知使用 Handler，不依赖目标 View 的 attach 状态。supersede、cancel、close 后，旧工厂不再持有旧宿主回调；只有尚未交付的快照允许回收，已经交给 listener 的快照永不由 preparer 回收。
- listener 抛出的普通异常隔离在该次通知；后续准备仍可使用。来源和预处理失败分别为 SOURCE_FAILED / PREPARATION_FAILED，执行器拒绝工作为 WORKER_UNAVAILABLE；不把异常原文、背景路径或 Uri 传入诊断。
- 这是 1.5.2 的兼容新增 API；现有同步接口和 `CONTRACT_VERSION` 保持不变，版本编号按用户明确指定记录在 `VERSIONING.md`。

## 4. 状态回退

按优先级依次为：禁用 → 高对比度 → 降低透明度 → 低内存设备 → 省电 → 内存释放 → 窗口失焦 → 无图像 → 渲染不可用。`NONE` 表示可显示云母。所有失败只影响本背景，不更改皮肤健康状态、不通知宿主重建。

窗口焦点监听和省电广播只在 View 挂载期间注册，detach／close 时移除，reattach 后重新注册并立即读取状态。低内存设备使用 `ActivityManager.isLowRamDevice`。静态快照无需因为一次失焦反复释放／重建。背景着色或 Shader 分配失败时局部回退为 `RENDER_UNAVAILABLE`，普通重绘不会重试分配。

## 5. 验证与演示

- `MicaPolicyTest` 覆盖官方颜色、输入边界、极端纵横比预算、纯色合成端点、Alt 的色彩差异、始终不透明及回退优先级。
- `MicaContractTest` 覆盖绘制路径无采集／模糊／分配、后台准备、输入归属、监听释放、内存回收和无隐藏 API。
- `MicaIntegrationTest` 覆盖真实 Bitmap 输出、输入回收后快照独立性、主线程拒绝预处理、明暗主题回退、内容层令牌、资源释放与恢复、根背景归属、真实 Dialog 失焦与恢复、PixelCopy 窗口 GPU 输出。
- `LatestPreparationTest` 验证有界任务/结果队列、取消、关闭、跨线程代次、执行器拒绝与回调失败；`MicaPreparerIntegrationTest` 在设备侧验证输入所有权、同步/异步像素一致、关闭后快照可用、失败回调与重用，三项均登记为各 API smoke 必测用例。编译通过不等于设备执行通过。
- Demo 入口：**表面沙盒 → Mica 云母与内容分层**。可以开关材质、BaseAlt、深色、高对比度、降低透明度、降低动态效果、失焦回退，并更换静态背景。
- 本地构建、JVM、设备及跨版本结果须分别记录；添加测试不等于所有 API 或厂商设备已经通过。功耗、不同壁纸可读性和目标 ROM 视觉观感仍需要目标场景验收。
