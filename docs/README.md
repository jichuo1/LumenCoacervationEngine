# 文档索引

文档分三类。**现行**文档描述当前版本，随代码一起更新；**历史**文档是某次改动或发布时的快照，保留原貌、不再改写，读它时以对应版本为准。

## 现行：规范

| 文档 | 内容 | 何时更新 |
|---|---|---|
| [INTEGRATION_STANDARD.md](INTEGRATION_STANDARD.md) | 宿主接入的必须/应当/可以条款；代码和测试按节号引用 | 接入方式或条款变化时；节号只增不改（`VERSIONING.md` §4） |
| [API.md](API.md) | 受兼容承诺保护的全部公开声明 | 新增或改变公开声明时 |
| [ENGINEERING_RULES.md](ENGINEERING_RULES.md) | 引擎内部写法规则（隔离类、零分配、线程、失败隔离、测试） | 新的真机教训落成规则时；隔离类表随类增删同步 |
| [BUILD_WORKFLOW.md](BUILD_WORKFLOW.md) | 严格配置缓存、源码契约输入、精确 APK 分发与设备门禁 | 构建、缓存或门禁执行方式变化时 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层、包职责、管线与扩展点 | 新增包、管线或跨模块协作时 |
| [VERSIONING.md](VERSIONING.md) | 版本规则、持久化协议、发布检查清单 | 每次发布（编号例外记录在这里） |

## 现行：功能说明

| 文档 | 内容 |
|---|---|
| [SURFACE_SESSIONS.md](SURFACE_SESSIONS.md) | 局部视效会话：来源、采样、位置与滚动通知 |
| [VIEWPORT_CONTENT_SOURCE.md](VIEWPORT_CONTENT_SOURCE.md) | 小视口来源：区域并集、overlay 坐标、前景探针、反馈拒绝与关闭 |
| [VISUAL_EFFECTS.md](VISUAL_EFFECTS.md) | 局部增强：轮廓、按压、光源、渐进模糊、质量与平台回退 |
| [P2_EFFECTS_AND_ASSETS.md](P2_EFFECTS_AND_ASSETS.md) | 传感器光源、程序化效果与粒子、Lottie / PAG / Rive 资产 |
| [NATIVE_COMPOSE.md](NATIVE_COMPOSE.md) | 原生 Compose 接入原型与待验收项 |
| [FLUENT_MATERIAL.md](FLUENT_MATERIAL.md) | Fluent 亚克力与层级：配方来源、回退规则、接入与验证 |
| [PRISM_MATERIAL.md](PRISM_MATERIAL.md) | Prism 棱镜玻璃与液态选中框：配方与默认值、深浅色、接入与验证 |
| [MICA_MATERIAL.md](MICA_MATERIAL.md) | 可选云母背景：Mica / Mica Alt、静态背景预算、内容分层、状态回退与资料依据 |
| [SKIN_MOTION.md](SKIN_MOTION.md) | 自定义皮肤的动画交接、可选屏幕四角适配与像素保留预算 |

## 参考

| 文档 | 内容 |
|---|---|
| [BETTERANDROID_INITIATIVE.md](BETTERANDROID_INITIATIVE.md) | 写法倡议（非标准）；唯一强制项已写入 `ENGINEERING_RULES.md` |
| [../CHANGELOG.md](../CHANGELOG.md) | 各版本变化 |

## 历史快照（不再改写）

| 文档 | 快照对象 |
|---|---|
| [MICA_BUILD_REVIEW_2026_10_15.md](MICA_BUILD_REVIEW_2026_10_15.md) | 云母审阅修复、构建／门禁性能基准，以及 API 37 的候选与基线设备失败对照 |
| [RELEASE_1.5.6_REVIEW.md](RELEASE_1.5.6_REVIEW.md) | 材质浓度发布审阅：默认路径逐位不变、功耗与静止门控、与玻璃区域判定的关系、二进制与 API 级兼容核对，以及审阅中修复的两项问题 |
| [RELEASE_1.5.5_REVIEW.md](RELEASE_1.5.5_REVIEW.md) | 通用滑块的设计、各材质的实现取舍（Prism 镜片不采样背景）、真机复核与验证边界；多级手风琴首级标题形变修复 |
| [RELEASE_1.5.4_REVIEW.md](RELEASE_1.5.4_REVIEW.md) | 性能报告复核、等价优化、刷新率自唤醒陷阱、二进制兼容与验证边界 |
| [ASYNC_PREPARATION_REVIEW_2026_10_23.md](ASYNC_PREPARATION_REVIEW_2026_10_23.md) | 未发布异步 Mica 接口、源码契约索引、缓存正反验证与 API 37 定向设备记录 |
| [RELEASE_1.5.2_REVIEW.md](RELEASE_1.5.2_REVIEW.md) | 屏幕圆角、自定义皮肤动画、异步预处理与门禁索引的发布审阅；替代前一阶段未发布状态 |
| [RELEASE_1.5.3_REVIEW.md](RELEASE_1.5.3_REVIEW.md) | 长文本输入框描边、展开手风琴四向形变、当前页局部外溢及手势回归的发布审阅 |
| [PORTABILITY_AUDIT.md](PORTABILITY_AUDIT.md) | 1.0.0 从来源工程抽离时的可移植性审查 |
| [VISUAL_ENHANCEMENT_PLAN.md](VISUAL_ENHANCEMENT_PLAN.md)、[VISUAL_EFFECTS_REVIEW.md](VISUAL_EFFECTS_REVIEW.md) | 1.2.0 视效增强的执行记录与候选阶段 Review |
| [P2_ENHANCEMENT_PLAN.md](P2_ENHANCEMENT_PLAN.md)、[P2_REVIEW.md](P2_REVIEW.md) | 1.2.1 传感器、效果与资产的计划与 Review |
| [ELASTIC_CLIP_FIX_REVIEW.md](ELASTIC_CLIP_FIX_REVIEW.md) | 1.2.4 之后的长按形变截断修复 |
| [DEPENDENCY_REFRESH_2026_10_11.md](DEPENDENCY_REFRESH_2026_10_11.md) | 2026-10-11 的依赖稳定版核验 |
| `RELEASE_x.y.z_REVIEW.md` | 各版本发布审阅；随 Release 附为 `REVIEW_x.y.z.md`，内容与 tag 一致，发布后不得修改 |

## 维护规则

- 行为或公开声明变化时，同一提交里更新对应的现行文档；不要在历史快照里追加新结论，新结论写进现行文档或新的发布审阅。
- 现行文档不写会过时的统计数字（文件数、测试数等）；需要时给出核对方法。版本号只出现在“自 x.y 起”这类说明里。
- 发布时按 `VERSIONING.md` §5 检查本索引：新增的文档归入上面某一类，失效的现行文档改为历史快照并注明对象。
