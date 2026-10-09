# 1.2.2发布 Review

修复开发基线：`ce6a39ccac19c1a509c7a13eae515fc97b63d09d`。本轮按用户明确要求发布为1.2.2；可选实际滚动通知属于新增公开API的编号例外，契约版本仍为1，见VERSIONING.md。旧公开入口、三参数滚动容器构造、动画通知及数据协议保持兼容。

## 源码 Review

本轮修复Activity与局部会话的采样几何缓存。真实滚动先更新位置，再同步比较旧的硬件绘制足迹；通知不提前记账，不消费其他页或动画的窗口合并批次。窗口pre-draw之后的滚动通知可补刷，OnDraw只复位阶段；post-OnDraw的computeScroll仍依赖容器同步接线。

局部会话比较source到target的相对矩阵，同一祖先平移而相对矩阵不变时不刷新目标；来源内容变化只标脏，并在干净转脏时有界调度一帧。来源录制中的滚动重入按身份去重，最外层finally后补查，关闭、暂停和异常出口清空暂存。共享内容节点仅在成功录制且尺寸/倍率变化时刷新已有GPU代理。纯relative足迹在绘制前暂存、成功硬件绘制后发布，避免SurfaceCapture对Matrix原地preScale污染足迹。

新监听注册/移除成对，OnDraw分发中的移除异常通过弱观察者延后重试，不中断资源释放；后台Activity拒绝迟到滚动。独立源码复核未发现新的阻断问题。通知路径保留原生命周期、窗口、反馈和资源所有权校验，不新增生产依赖或外观参数。

## 性能与验收边界

同步桥接增加CPU几何比较，不宣称零开销或零分配。Activity路径遍历已登记表面，局部会话最多32个绑定；作用域排除先于可能遍历全部绑定祖先的反馈检查。局部目标使用isShown门控，没有以粗窗口裁剪误拒旋转或外扩表面。

这次本地验证不包括手机快速滚动的实际像素验收，也不包括新版本四系统模拟器结果。现有frame/press/shapes/light/controller的即时通知继续有效，不把这些已接通知的路径列为未修复缺陷。

## 本地发布门禁

1.2.2配置下完整门禁通过：assembleDebug、testDebugUnitTest、sample:testReleaseUnitTest、lintDebug、sample:assembleRelease、sample:assembleReleaseAndroidTest及publishAllPublicationsToProjectLocalRepository同轮执行成功，耗时2分14秒。共有776项JVM测试，其中引擎426、motion336、controls2、effects5、assets5、sample Release2；失败、错误、跳过均为0。Lint以各模块HTML生效汇总为准，0 Error / 63 Warning；22项脚本测试全部通过。

八模块AAR及源码包已发布到项目内Maven仓；核心与effects/assets/lottie/pag/rive六模块的分发许可内容逐项校验通过。Release APK及仪器测试APK已构建，但未运行设备测试。minSdk27 Release DEX审计的9条cast及40条高版本签名经人工分类，均位于ApiNN隔离类或相应合成类，未发现新的未隔离引用。实际Release class中LumenEngine.VERSION为1.2.2、CONTRACT_VERSION为1；新增默认方法和旧构造的兼容签名已核对。

发布附件VERIFICATION_1.2.2.json记录本轮本地证据及分发哈希。修复阶段1.2.1候选产物哈希不作为本次1.2.2正式分发哈希。

## 远端发布门禁

状态：pending。计划先提交修复分支并创建PR，等待CI与API27/31/33/34 Demo矩阵成功后合入main，再创建不可变1.2.2标签与Release，预热并核验JitPack。远端最终commit、run、必测清单与分发哈希由发布附件VERIFICATION_1.2.2.json记录。本地APK不作为已通过模拟器门禁的Demo附件。
