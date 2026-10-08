# P2实现 Review（1.2.1发布线）

日期：2026-10-08。基线：已发布1.2.0，`8839f2e90b5d7ff89a49f74d034bbe5ebe2a9af3`。实现最初在`codex/effects-p2-20261008`按1.3.0候选验证；发布在`codex/release-1.2.1`按用户指定编号1.2.1。本轮不修改1.2.0标签，也没有把新代码写进Bilibili模块的生产接入层。

## 范围与结论

D14可选传感器光源、D20薄膜/纸张/有限能量、D21固定池粒子、D22统一资产契约及Lottie/PAG/Rive独立模块已经实现。中文Demo覆盖开关、参数、预算、本地文件导入、状态机输入和固定步进；公开API、版本说明和许可记录已同步。

本轮做了源码Review，关注线程、预算、所有权、旧系统隔离和结束/暂停/隐藏行为；不是独立第三方审计。最终本地门禁通过，未保留已知阻断性源码问题。Compose接入层、NativeBackdrop、Mesh、原生解析进程隔离及真实手机传感器能耗不属于本轮已完成项。

## Review中修正的问题

| 问题 | 修正与验证方向 |
|---|---|
| GitHub raw下载的是PAG Git LFS索引；后续示例画布也超过默认预算 | 取实际二进制并校验官方索引哈希，换为500×500/540ms的0.pag。预算拒绝与文件损坏分开报告 |
| 暂停保留粒子后，后台时间继续消耗寿命 | 暂停记录时间并在恢复时平移出生时间；固定时钟验证剩余寿命 |
| 能量结束仍留下不透明底色；改变参数留下旧phase | GPU/Canvas都在未触发和结束时透明，参数变化清零phase；用实际窗口像素比较 |
| 原生时钟被误描述为可控60fps | metadata明确Rive不支持寻帧/速度/重复/帧率控制；固定时钟挂载被拒绝 |
| Rive周期监督产生静止工作，且暂停漏记最后一个提交间隔 | 使用单个截止任务，暂停/隐藏先结算活跃时间，恢复仅保留剩余时长；低提交率与暂停后截止测试 |
| Rive错误输入名称/类型会排入原生线程 | 解析期有界枚举输入，提交前验证；错误返回false，暂停/隐藏不提交 |
| 原生View可自行处理触摸、唤醒暂停播放器 | 会话私有子容器阻止pointer到达播放器并把事件交还宿主；保留外层业务点击 |
| renderer/Surface回调失败可能逸出主线程 | 同步render/seek/input保护与异步失败通道；再次核对代次和player身份后只关闭自己 |
| 加载后挂载、以及加载中调低预算仍沿用旧值 | 主线程挂载前按当前字节/元数据/能力预算复核；超限释放，过期句柄关闭 |
| 资产禁用/裁剪配置没有完整落到View | 禁用隐藏自有子容器，裁剪仅作用于自己的容器；不改宿主原有子View或裁剪设置 |
| 传感器角速度使用线性近似、回调失败状态不明确 | 后续样本使用球面插值；增加CALLBACK_FAILED，注册/回调失败仅显式操作后重试 |

## 接入边界

- core/motion的发布POM没有Lottie、PAG、Rive或Compose。播放SDK仅为对应模块runtime依赖；公开签名无供应商类型。
- 三个供应商版本按用户确认固定。PAG/Rive包含原生库；PAG不含x86 ABI。Demo因同时演示三种格式而包含全部运行时，业务应用可只选一项。
- Lottie支持JSON和嵌入位图，检查真实位图头部总像素；未接网络图片、字体或dotLottie ZIP。
- Rive使用11.14.0的类型化View兼容层；其View及状态输入API已被上游标记deprecated，未宣称完成新版Compose/data binding迁移。
- PAG显式释放player/surface，但PAGFile无公开release API，只能丢弃引用等待供应商GC。PAG/Rive内部图像像素与native解析峰值暂未完整计量；字节/已知画布预算不等于内部硬内存上限。加载宿主信任的文件，原生解析不是沙箱。
- 每会话只有一个后台解析线程、一个排队替换。中断和代次隔离会关闭迟到句柄；供应商不响应中断时不能强制立即取消。网络来源须由宿主设置I/O超时。
- 生成器不读背景，捕获计数0；Canvas是近似外观。装饰层绘制在目标View之上，宿主应选择合适承载面和透明度，不能据此宣称自动通过阅读对比度验收。
- 传感器输入是可选生产者，默认关闭；模拟重力测试不代表真实设备轴向、功耗或多显示器验收。

## 前轮本地实现验证（历史1.3.0候选，非1.2.1发布产物）

| 检查 | 最终结果 |
|---|---|
| assembleDebug / testDebugUnitTest / :sample:testReleaseUnitTest / lintDebug | `artifacts/p2/final-gates-8.log`，BUILD SUCCESSFUL；747项JVM测试，失败/错误/跳过均0 |
| Lint生效HTML汇总 | 0 Error / 62 Warning；含依赖目录建议、固定PAG版本更新提示及展示容器返回false的触摸可访问性提示，未用baseline隐藏错误 |
| Python脚本门禁 | `artifacts/p2/python-final.txt`，22项通过，包含缺测/失败/过期报告拒绝 |
| API34 x86_64完整设备回归 | `artifacts/p2/full-device-release-candidate/TEST-local-api34.xml`，43项，失败/错误/跳过/必测缺失均0；运行日志`full-device-cold-run.txt`；含13项P2及原有页面、动效、融合、模糊、真实像素、基准测试 |
| 实际资产绘制 | Lottie/PAG/Rive分别检查窗口像素变化；暂停像素稳定，禁用恢复原背景；未自动播放 |
| 新效果像素 | 薄膜/纸张/能量均实际改变窗口像素，能量结束与参数取消回到原图；Shader复用，无背景捕获 |
| minSdk27 DEX审计 | `artifacts/p2/dex-audit-final.txt`人工分类；新增RuntimeShader字段只在`@RequiresApi(33) ProceduralProgramApi33`内，调用由SDK判断保护，未发现新增未隔离高版本类型 |
| 1.2.0主要表面ABI | 对正式JitPack AAR比较11种类型，已发布JVM描述符0缺失；`artifacts/p2/abi-checks.txt` |
| 分发与依赖 | 8个AAR/8个源码包生成；核心及5个新增模块许可附件逐字核对，POM确认core/motion无播放库/Compose |

最终Demo：`artifacts/p2/delivery/lumen-demo-1.3.0-candidate.apk`，46,938,223字节；SHA-256：`e6992a86805667e74b6d8713987c48d05d81fc4116b0bd0bd6488332c6f63985`。安装后回拉base.apk与此哈希一致；43项完整回归运行的就是这份APK。校验清单与机器可读摘要分别在`artifacts/p2/delivery/SHA256SUMS`、`artifacts/p2/final-validation.json`。该目录持久保存在工作树，按既有排除规则不提交二进制产物。

一轮回归被后续安装中断，另一轮在旧动效页waitForIdleSync停住，模拟器当时99%卡顿；都按缺测拒绝。保留`stalled-logcat.txt`及`stalled-gfxinfo.txt`后，冷重启本次专用AVD（Android34、host GPU、关闭Vulkan），重新完整通过43项。旧失败/缺测记录不算通过，也未修改旧用例或降低清单要求。

前轮候选仅实际跑了本机API34；该历史结果不能充当1.2.1四版本发布证据。新版远端验证及产物哈希见RELEASE_1.2.1_REVIEW.md。DEX分类不能替代低版本供应商SDK的设备验收；也未在真实手机安装、验证功耗或把Bilibili模块UI切到此候选引擎。
