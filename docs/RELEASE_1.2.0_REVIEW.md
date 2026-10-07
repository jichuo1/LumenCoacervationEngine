# 1.2.0 发布前最终 Review

日期：2026-10-08。审核实现提交：d3193372b18c852a3e23d28c7a7b0341b690639d。
合并提交：bde4d292f7e798bd206661a287a7ccaaa7628e52；合并树与审核实现提交一致。
本发布记录为文档增量，引擎、动效、控件、Demo生产/测试源码及构建配置不变。

## 结论

重点复核公开ABI、默认路径恢复、来源许可/依赖/换代、异步资源与迟到结果、融合几何/解析梯度、RGBA预乘、连续更新、实例所有权、调节范围与发布门禁。
已确认的问题均已修复并回归，未留下本轮已知的发布阻断问题。
公开契约版本仍为1，版本为1.2.0；原构造、材质标识和持久化兼容保持。

## 最终门禁证据

- [最终CI](https://github.com/jichuo1/LumenCoacervationEngine/actions/runs/37676809401)：构建、731项JVM、Lint0错误、AAR/源码许可附件检查成功。
- [最终设备矩阵](https://github.com/jichuo1/LumenCoacervationEngine/actions/runs/37676809387)：四版本成功。下载实际XML后以独立verify-smoke-results.py再次校验：

| API / 镜像 | 实际通过 | 失败/错误 | 跳过 | 必测缺失 |
|---|---:|---:|---:|---:|
| 27 / default | 20 | 0 | 0 | 0 |
| 31 / google_apis | 21 | 0 | 0 | 0 |
| 33 / aosp_atd | 30 | 0 | 0 | 0 |
| 34 / aosp_atd | 30 | 0 | 0 | 0 |

共101项实际设备测试；不适用于低版本的AGSL项目由SDK过滤，不把缺少必测项或零测试当作成功。
API33/34覆盖窗口像素：融合桥接、四角、渐进模糊、局部按压、透明输入、外扩标记、变换光源。
真实选择器还覆盖打断/终态、动态调节外扩后保持实体位置、节点A/B以及资源恢复。
应用側构造计数确认连续输入不新建RenderEffect/RuntimeShader；这些计数不代表驱动物理GPU pass。

- 本地Windows host OpenGL另有完整30项设备回归。
- 11个相关公开类型与已发布1.1.0对照，旧JVM描述符无缺失；DEX新平台类型保持ApiNN隔离。
- Python门禁21项：设备报告11、模拟器准备6、运行库安装4。
- 三模块1.2.0 AAR与源码包准备完成；云端Demo包内versionName已核对为1.2.0。

## 发布前修正与基础设施

融合装饰调节外扩后重放原动效几何，避免实体位置偏移；按启用阶段计算k/4与3.5σ阴影尾部，避免可见截断。
原本的静止来源、默认几何、监听所有权、质量重配、迟到计数和JSON边界修复详见VISUAL_EFFECTS_REVIEW.md。

运行库的Azure HTTP镜像曾长时间停滞。安装改用工作区内的签名Ubuntu HTTPS源，限定重试/网络/命令超时；应用测试不重试或跳过。
API34标准镜像在安装Demo前即出现Google服务/SystemUI ANR与system_server Watchdog退出，未执行模块测试。
改用精简ATD镜像后全部30项真实测试通过；暂时启用硬件输出的测试规则仍保留像素断言，最终恢复原状态。
Gradle设备任务限制15分钟；超时、runner异常、缺失或跳过必测项仍阻止发布。

## 验收边界

新完整效果使用API33+直接Shader，有界软件来源/滤波加GPU绘制；低版本保留轮廓/固定模糊回退。
仅保证sRGB SDR ARGB8888契约，不保留HDR/扩展色域亮度；没有传感器、粒子、Compose或隐藏API路径。
模拟器基准原始数据有明显环境波动，不能据此宣称新路径更快或已通过手机帧率/电量验收。
实际模块UI仍需显式接入新配置；源码/API测试与Hook及厂商真实设备验收分别看待。

发布附件与SHA256见[1.2.0 Release](https://github.com/jichuo1/LumenCoacervationEngine/releases/tag/1.2.0)。
