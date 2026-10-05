# 更新记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 `docs/VERSIONING.md`。

## [未发布]

新增公开 API，按 `docs/VERSIONING.md` 属次版本（1.1.0）。

### 新增

- **来源交互效果的显式保留**：新增 `ElasticTravelPolicy.PARENT_BOUNDS` 与 `MorphCornerMode.CAPSULE`，供宿主保持原列表交叠行程和胶囊形变起点。默认仍避让兄弟/使用来源声明圆角；规则按手势或面板固定，构造签名及三参数圆角方法保留。
- **尺寸受限的背景预览**：新增后台 API `LiquidBackgroundStore.decodePreview(...)`，接收宿主的控件像素尺寸与配色，单张位图限于 2 MiB。Demo 设置页展示布局后解码、代次校验和 Activity 所有的任务清理。
- **视效调参 `LumenEffectTuning`**（适配标准 §2.5）：五个相对默认值的倍率，默认 1 即引擎原样。
  - `edgeHighlightWidth`（0.25～4）/ `edgeHighlightIntensity`（0～3）：表面边缘高光的厚度与亮度。柔光缩放边框描边；高级材质缩放折射 rim 带、轮廓描边与廉价路径的高光带，镜面、菲涅尔强度同比缩放；rim 加厚时采样 padding 同步撑大。
  - `dragDeformation`（0～2）：长按拖动时控件的形变程度，跟手位移行程、按压收缩与拉伸一起缩放，0 关闭形变；位移仍不越过相邻卡片，拉伸仍受绝对上限约束。每次按下时读取；回弹中重新抓住控件时按本次倍率反推按压进度，尺寸不跳变。
  - `dragGlowIntensity`（0～4）/ `dragGlowRadius`（0.5～2）：长按拖动时触点光晕的亮度与半径，每次按下时读取，可即时生效。
  - 接入：`LumenActivityDelegate` 第三个参数 `effectTuningProvider`（`@JvmOverloads`，旧写法不变）、`LumenActivity.resolveEffectTuning()`、`LumenElasticInteraction` / `ElasticInteractionController` 的 `effectTuning` 参数（带默认值，源码兼容）。
  - `LumenEffectTuning.clamped(...)` 把滑块等连续输入收进范围。
- **演示包**：`sample` 扩成可下载的完整演示（README「下载 Demo」），发布在 Release 页的 `lumen-demo-<版本>.apk`。
  - 新增「自适应」页：网格列数随窗口宽度变化、宽屏双栏列表-详情、窗口信息。
  - 新增「设置」页：材质与实时取样、深浅色、强调色、弹窗背景模糊、自定义背景、五项视效调参（含「恢复默认」）、诊断信息。
  - 材质页新增全部表面角色一览与控件样式（状态标签、可选中条目、悬浮栏叠层）。
  - 改设置、切材质、旋转、分屏重建后停留在原页与原滚动位置。
  - `.github/workflows/demo.yml`：构建 release 包，在 API 27 / 31 / 33 / 34 模拟器上跑冒烟测试（每页、面板与形变、长按拖动的各档调参、全部设置项、自定义背景、旋转），全部通过后才附到 Release。
- **持续集成**：`.github/workflows/ci.yml` 在推送与 PR 上跑构建门禁（`ENGINEERING_RULES.md` §11）。
- **仓库规范**：`.editorconfig`（UTF-8、LF、4 空格缩进）与 PR 模板（对照发布检查与工程规则的自查清单）。

- **选中框连贯滑动 `LumenSlidingSelection`**（适配标准 §13.11，移植自来源工程 JEV 灵敏度面板）：单选组的选中框从旧选项连续滑到新选项，位置与尺寸一起插值，曲线与时长同来源。相对来源的改进：
  - 选中框逐帧用 `layout()` 直接摆放，不再逐帧 `requestLayout`；
  - 中途改选从当前位置**与速度**续接（来源从零速度重新起步），反向时不甩过目标；
  - 支持横向分段与竖向列表；逐帧通知引擎，高级材质采样跟得上；
  - 标题高亮按选中框覆盖比例连续计算，修复来源连点三项时第一项停在半高亮、改向时颜色跳变；
  - 选中行被长按拖动时选中框跟随形变。
  - 演示包：动效页新增竖向示例，设置页的三排互斥选项改为分段控件（滑到位后再应用）。

### 修复

- **实时玻璃的稳定采样源清晰度**：移植来源工程 `9113803`；实时截图缺席时使用未预模糊的稳定底图，抑制缓存与回退同源，AGSL 比例跟随实际绑定位图。截图填充、窗口直采与溶解的绘制状态分离，静态背景与三缓冲预算保持原值。
- **默认氛围背景的颗粒放大与重复分配**：移植来源工程 `cd48a75`；两种材质默认背景不再叠逐像素噪声，Liquid 自动底图复用显示/光学位图，原配色、暗角与自定义图片模糊链保持。
- **API 31–32 的高级材质模糊后端（BLUR）第二次绑定就被淘汰**：`LiquidBlurBackendApi31` 把 `RenderNode.setPosition()` 的返回值当成成功标志去 `check`，而它表示的是"位置是否变化"。同尺寸重绑（实时取样每次换缓冲都会发生）返回 false、抛异常，BLUR 被永久淘汰，这些设备一律退到最朴素的 TRANSLUCENT。已去掉该判断，并加契约测试禁止把 `setPosition` 当条件用（演示包冒烟测试在 API 31 模拟器上发现）。
- **高级材质降级原因缺失**：后端绑定底图失败、内存压力降到 TRANSLUCENT 这两条路径淘汰后端时没有记录原因，诊断里只看到后端变成 TRANSLUCENT、「降级原因」却为空。现在分别记录异常类型与 `memory-pressure`（演示包在 API 31 模拟器上发现）。

## [1.0.0] - 2026-09-27

首个独立版本，从 Bilibili Innocent Lab 模块中抽离，详见 `docs/PORTABILITY_AUDIT.md`。契约版本 1。

### 新增

- **两套材质**：
  - 柔光（`MATERIAL_YOU`）：静态环境磨砂，加上悬浮表面的软件透镜；
  - 高级材质（`LIQUID`）：RuntimeShader 折射与 PixelCopy 实时取样，后端按 REFRACTION → BLUR → TRANSLUCENT 单向降级。
- **材质两阶段激活**：新会话首帧绘制成功才确认健康；确认前进程死亡或渲染器失败，都会自动回退到柔光。
- **宿主接入层**：
  - `LumenActivityDelegate`：组合式，任意 Activity 基类都能用；
  - `LumenActivity`：继承式。
- **进程级入口**：`LumenEngine`，负责配置、材质选择、实时取样开关和内存压力释放。
- **`LumenPalette`**：由宿主提供配色，引擎不取壁纸色。
- **`LumenStorageNames`**：存储文件名可配置，便于从旧版本无损迁移。
- **悬浮栏可读性**（`GlowFloatingChrome`、`GlowBackdropTarget`）：滚动边缘溶解、按下方内容自适应补偿、API 31+ 的内容节点玻璃。
- **回弹视口**与手势归属声明（`ElasticGestureClaim`、`LiquidStretchGestureObserver`）。
- **触摸光晕**（`GlowState`、`TouchGlowRenderer`）：纯几何状态机，零分配绘制。
- **自定义背景图**（`LiquidBackgroundStore`）：有尺寸和字节预算，带 schema 校验。
- **可选模块 `lumen-motion`**（交互与动效）：
  - 长按弹性形变与触点高光（`LumenElasticInteraction`）；
  - 可打断动画内核；
  - 弹窗呈现器（`LumenModalPresenter`）：条目/图标锚点形变、锚定气泡、标题迁移与描述分层、覆盖式子面板（二级面板上再开三级面板）、背板压暗、背景模糊、预测式返回；
  - 条目形变成全屏 Activity（`ContainerMorphLauncher` / `ContainerMorphHost` / `ContainerMorphController`）；
  - 可打断翻页与文字链（`LumenPagePager`、`PageTextChain`）；
  - 手风琴（`SectionExpansionController`）；
  - 定位并高亮（`LumenReveal`）；
  - 微动效与命名曲线（`MicroMotion`、`LumenEasing`）；
  - 胶囊底栏（`LumenNavigationBar`）与分段档位条（`LumenSegmentScrubBar`）；
  - 去掉排布上的硬编码：翻页器不限页数；底栏最多 6 项、档位条最多 8 段且超出时报错（原来静默丢段）；定位支持 `RecyclerView` 等任意竖向滚动容器；气泡按 Activity 窗口定位（分屏、自由窗口）；弹窗可指定标题 View、未给宽度时最宽 560dp；全屏形变的飞行标题沿用目标页字形，两端按文字本身定位（标题带内边距时不再在结尾横跳）；角标轴心按布局方向；悬浮栏支持不贴边的悬浮表面与同边多条栏；
  - 多种卡片尺寸与排布的适配：长按弹性按相邻卡片限制行程；两类形变从来源自己的圆角开始；手风琴支持网格与横排的行高换算；文字链不放开横向轮播的裁剪；定位先横向滚到轮播里的目标；排序在网格与横向列表里自动放开方向，大卡片拾起放大封顶。
- **可选模块 `lumen-controls`**：`SwitchCompat`、`CheckBox`、`EditText` 换装；翻页器的 `SwitchCompat` 识别（`switchParts`）；长按拖拽排序（`LumenReorderCallback`）。
- **示例应用 `sample`**：按适配标准逐条对照的最小接入。
- **Maven 发布配置**：坐标 `com.lumen.coacervation.engine:lumen-engine` / `lumen-motion` / `lumen-controls`。
- **文档**：适配标准、架构、公开 API、工程规则、版本规则、BetterAndroid 倡议、可移植性审查报告。
- **工具**：DEX `check-cast` 审计脚本 `tools/audit_checkcast.py`；来源工程迁移脚本（引擎、测试、交互与动效各一份，保留供追溯）。

### 相对来源工程的变化

- 弹窗、全屏形变、弹性交互的编排从来源工程的 Activity 里抽成通用组件；覆盖式子面板从调用点约定升格为正式 API。
- 长按弹性对开关的排除从 `SwitchCompat` / `Switch` 泛化为"除复选框、单选框以外的 `CompoundButton`"，动效层因此不依赖 AppCompat。
- 来源工程中按窗口开关预测式返回的反射实现没有纳入（零反射），改由 manifest 声明。
- 长按弹性朝相邻卡片的方向不再越过邻居（来源工程在竖向列表里允许重叠几 dp）；锚点形变的起点圆角从"一律短边一半"改为"来源声明的圆角优先"。
- 去除对 BetterAndroid、KavaRef 与 AppCompat 的依赖，AppCompat 只留在可选模块里。
- 删除从未被读取的 `materialOutline` 参数。
- 两个版本判断属性补上 `@ChecksSdkIntAtLeast`，并删除随之变成冗余的判断。
- 线程、RenderNode、日志前缀统一为 `Lumen-`。
- 默认存储文件名改为 `lumen_*`。
