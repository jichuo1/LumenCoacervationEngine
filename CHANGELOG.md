# 更新记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 `docs/VERSIONING.md`。

## [未发布]

新增公开 API，按 `docs/VERSIONING.md` 属次版本（1.1.0）。

### 新增

- **视效调参 `LumenEffectTuning`**（适配标准 §2.5）：四个相对默认值的倍率，默认 1 即引擎原样。
  - `edgeHighlightWidth`（0.25～4）/ `edgeHighlightIntensity`（0～3）：表面边缘高光的厚度与亮度。柔光缩放边框描边；高级材质缩放折射 rim 带、轮廓描边与廉价路径的高光带，镜面、菲涅尔强度同比缩放；rim 加厚时采样 padding 同步撑大。
  - `dragGlowIntensity`（0～4）/ `dragGlowRadius`（0.5～2）：长按拖动时触点光晕的亮度与半径，每次长按开始时读取，可即时生效。
  - 接入：`LumenActivityDelegate` 第三个参数 `effectTuningProvider`（`@JvmOverloads`，旧写法不变）、`LumenActivity.resolveEffectTuning()`、`LumenElasticInteraction` / `ElasticInteractionController` 的 `effectTuning` 参数（带默认值，源码兼容）。
  - `LumenEffectTuning.clamped(...)` 把滑块等连续输入收进范围。
- **示例应用**：材质页新增「视效调参」卡片，四个滑块；边缘高光松手后重建，长按光晕拖动即生效。
- **持续集成**：`.github/workflows/ci.yml` 在推送与 PR 上跑构建门禁（`ENGINEERING_RULES.md` §11）。
- **仓库规范**：`.editorconfig`（UTF-8、LF、4 空格缩进）与 PR 模板（对照发布检查与工程规则的自查清单）。

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
