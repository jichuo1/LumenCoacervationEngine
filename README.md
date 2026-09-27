# 凝光视效引擎 · Lumen Coacervation Engine

面向 Android View 系统的可移植玻璃材质与视效引擎。它为一个 Activity 提供以下能力：

- 统一的表面材质；
- 悬浮栏可读性；
- 回弹；
- 触摸光晕；
- 材质健康回退。

可选的交互与动效层（`lumen-motion`）另外提供：

- 长按拖动形变与触点高光；
- 可打断的翻页与文字链；
- 弹窗和全屏页的打开与关闭形变，包括锚定气泡、标题迁移、二级面板上再开三级面板、预测式返回；
- 手风琴、定位高亮、微动效。
- 以上能力都按卡片尺寸和排布（列表、网格、大小混排、瀑布流、横向轮播、卡中卡）自动适配。

宿主只需声明"这块表面是什么"，由引擎决定怎样画。

| | |
|---|---|
| 版本 | 1.0.0（契约版本 1） |
| 最低系统 | Android 8.1（API 27） |
| 依赖 | 引擎本体只依赖 AndroidX core |
| 许可证 | Apache-2.0 |

## 两套材质

| 材质 | 标识 | 实现 | 设备要求 |
|---|---|---|---|
| 柔光 | `MATERIAL_YOU` | 静态环境磨砂，加上悬浮表面的软件透镜 | 全部设备，不会失败 |
| 高级材质 | `LIQUID` | RuntimeShader 折射，加上可选的 PixelCopy 实时取样 | REFRACTION 需要 API 33+；BLUR 需要 API 31+；其余设备用 TRANSLUCENT |

切到高级材质之后，新会话的首帧绘制成功才算确认健康。如果进程在确认前死亡，或者渲染器失败，下次启动会自动回到柔光，不会反复崩溃。

## 模块

| 模块 | 说明 |
|---|---|
| `lumen-engine` | 引擎本体（必需） |
| `lumen-motion` | 可选：交互与动效（依赖 `lumen-engine`，不依赖 AppCompat） |
| `lumen-controls` | 可选：`SwitchCompat` / `CheckBox` / `EditText` 换装、翻页器的开关识别、拖拽排序（依赖 AppCompat、RecyclerView） |
| `sample` | 最小接入示例，每一步都标注了适配标准的节号 |

## 快速开始

**1. 依赖**：两种方式任选其一，详见适配标准 §1.1。

```kotlin
// settings.gradle.kts：复合构建
includeBuild("../LumenCoacervationEngine")

// app/build.gradle.kts
dependencies {
    implementation("com.lumen.coacervation.engine:lumen-engine:1.0.0")
}
```

**2. Activity**：组合式委托，任何 Activity 基类都能用。

```kotlin
class MainActivity : AppCompatActivity() {
    private val lumen = LumenActivityDelegate(this) { LumenPalette.modern(primary, onPrimary, secondary, tertiary, dark) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lumen.prepare()                                  // 放在宿主自己的授权门、早退检查之后
        val root = buildContent()                        // 用 lumen.cardBackground() / floatingBackground() 等取表面
        setContentView(root)
        lumen.bindRoot(root) { recreate() }              // 高级材质整体失败时回调一次
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lumen.onDispatchTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }
    override fun onStart() { super.onStart(); lumen.onStart() }
    override fun onStop() { lumen.onStop(); super.onStop() }
    override fun onTrimMemory(level: Int) { lumen.onTrimMemory(level); super.onTrimMemory(level) }
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() { lumen.onLowMemory(); @Suppress("DEPRECATION") super.onLowMemory() }
    override fun onDestroy() { try { lumen.onDestroy() } finally { super.onDestroy() } }
}
```

**3. Application**：处理进程级内存压力。

```kotlin
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) LumenEngine.releaseGraphics()
}
```

**4. 材质开关**

```kotlin
if (LumenEngine.selectMaterial(context, SkinId.LIQUID, realtimeCapture = true)) {
    recreate()
} else {
    switch.isChecked = false
}
```

**5. 交互与动效**（可选，`lumen-motion`）

```kotlin
private val elastic by lazy { LumenElasticInteraction(this, lumen) }
private val modals by lazy { LumenModalPresenter(this, lumen, elastic = elastic) }

override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    lumen.onDispatchTouchEvent(event)
    return elastic.dispatch(event) { super.dispatchTouchEvent(it) }   // 长按弹性（§12）
}

// 条目 → 卡片形变；弹窗第一个子 View 是标题，与条目标题同文字时做标题迁移（§13.1、§13.3）
row.setOnClickListener {
    val dialog = Dialog(this)
    val container = modals.createContainer().apply { addView(title); addView(body) }
    modals.present(dialog, container, anchor = row)
}
```

悬浮栏、回弹、控件、翻页、全屏页形变、二级面板等的完整写法，见 `sample/` 和适配标准。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/INTEGRATION_STANDARD.md`](docs/INTEGRATION_STANDARD.md) | **适配标准**：宿主必须/应当做什么，附验收清单 |
| [`docs/API.md`](docs/API.md) | 公开 API 清单（兼容承诺的范围） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构：分层、会话、状态机、两条渲染管线 |
| [`docs/ENGINEERING_RULES.md`](docs/ENGINEERING_RULES.md) | 修改引擎本体时必须遵守的规则（旧系统安全、零反射、零分配、失败隔离） |
| [`docs/VERSIONING.md`](docs/VERSIONING.md) | 版本号与契约版本规则 |
| [`docs/BETTERANDROID_INITIATIVE.md`](docs/BETTERANDROID_INITIATIVE.md) | 关于 BetterAndroid 写法的倡议 |
| [`docs/PORTABILITY_AUDIT.md`](docs/PORTABILITY_AUDIT.md) | 从来源工程抽离时的可移植性审查报告 |
| [`CHANGELOG.md`](CHANGELOG.md) | 更新记录 |

## 构建

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug --console=plain --no-daemon
./gradlew publishAllPublicationsToProjectLocalRepository --no-daemon   # 产物输出到 build/repo
```

需要 JDK 17 或更高版本，以及 Android SDK（compileSdk 37）。本机 SDK 路径写在 `local.properties` 里，该文件不入库。

## 来源与许可证

引擎抽离自 [Bilibili Innocent Lab](https://github.com/jichuo1/Bilibili_Innocent_Lab)，由原作者以 Apache License 2.0 重新授权发布，详见 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)。来源工程本身仍沿用它原来的许可证。
