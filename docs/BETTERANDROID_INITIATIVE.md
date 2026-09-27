# 关于 BetterAndroid 写法的倡议

> **性质**：本文是倡议，不是适配标准。
>
> - 只有一条是强制的：引擎本体不依赖 BetterAndroid 系列库（`ENGINEERING_RULES.md` §1、§3）。
> - 其余都是给宿主的建议。宿主不采纳，也不影响符合 `INTEGRATION_STANDARD.md`。
>
> **背景**：凝光视效引擎诞生在一个大量使用 HighCapable 系列库（BetterAndroid、KavaRef、Hikage）的项目里。抽离成独立引擎时，所有 BetterAndroid 写法都被换成了原生写法。本文说明这样做的理由，以及宿主怎样把两者组合起来用。

## 1. 倡议摘要

**引擎内部坚持原生写法，宿主层欢迎 BetterAndroid。两者只在 Activity 基类这一处接缝相接。**

| 层 | 写法 | 理由 |
|---|---|---|
| 引擎本体（`lumen-engine`） | 只用原生 Android 与 AndroidX core | 可移植、lint 能证明版本边界、零反射（§2） |
| 可选模块（`lumen-controls`） | 原生 + AppCompat | 同上 |
| 宿主的 Activity 基类 | 自由选择，推荐组合式委托（§4） | 接缝只放在一处 |
| 宿主的业务代码 | 自由选择，BetterAndroid 同样合适 | 引擎不关心宿主用什么写 |

## 2. 为什么引擎本体不用 BetterAndroid

### 2.1 可移植性

引擎要能放进任意 Android 应用。如果引擎依赖 BetterAndroid，它的版本和传递依赖就会被强加给每一个宿主：宿主自己也用 BetterAndroid 时可能版本冲突，宿主不用时又平白多出一组依赖。

一个视效引擎，不应该替宿主决定工具库。

### 2.2 版本边界必须能被 lint 证明

引擎有 12 个 API 隔离类，还有大量 `@RequiresApi` 构造调用（`ENGINEERING_RULES.md` §2）。对引擎来说，**版本边界就是崩溃边界**。

Android Lint 的 `NewApi` 检查只认两种写法：

- `Build.VERSION.SDK_INT` 的直接比较；
- 标注了 `@ChecksSdkIntAtLeast` 的函数。

来源工程实测：BetterAndroid 1.1.6 的 `AndroidVersion.isAtLeast(...)` 没有这个注解，换过去之后 `NewApi` 会直接报**错误**。为此，来源工程共有 16 处 `ReplaceWithAndroidVersion` 定点压制，专门压制 BetterAndroid 自带的"改用 AndroidVersion"lint 建议；其中 15 处就在引擎代码里。换句话说，引擎在来源工程里就已经在版本边界上坚持原生写法。

在引擎这种代码里，静态可证明比写法简洁更重要。

### 2.3 不用反射

KavaRef 是反射工具。引擎禁止反射（`ENGINEERING_RULES.md` §3），这条禁令同时支撑着两件事：

- 宿主零配置混淆；
- DEX 旧系统安全审计。

### 2.4 引擎本来就不需要它

来源工程里，引擎对 BetterAndroid 系列的依赖只有以下几处，每一处都有一行的原生等价写法（§3）：

- `AndroidVersion` ×10；
- `parentOrNull` ×2；
- KavaRef `classOf` ×2；
- `AppViewsActivity` 基类。

换掉之后，引擎代码没有变长，行为也没有变化（312 个测试全部通过）。

后来纳入的交互与动效层（`lumen-motion`）同样如此。迁移脚本替换的 BetterAndroid 写法有：

- `AndroidVersion` ×2；
- View 扩展 `child(...)` ×9、`textToString()` ×7、`parentOrNull()` ×1；
- `textColor =` 赋值 ×1；
- Hikage 的 `@HikageView` 注解 ×1。

另外去掉了 1 处 `ReplaceWithAndroidVersion` 文件级压制。

唯一没有迁的是 `PredictiveBack.kt`：它用 KavaRef 反射调隐藏 API，按窗口开关预测式返回，与零反射规则冲突。宿主改用 manifest 声明（适配标准 §13.10）。280 个测试全部通过。

### 2.5 不能要求宿主继承特定基类

`AppViewsActivity` 作为基类很好用，但引擎如果要求宿主继承它，所有已有自己基类的应用就都接不进来。所以引擎的接入点是**组合式委托**（`LumenActivityDelegate`），任何基类都能持有它。

## 3. 写法对照

下表列出引擎迁移时替换掉的写法，以及几个宿主常用写法的原生等价物：

| BetterAndroid / KavaRef | 原生等价 | 说明 |
|---|---|---|
| `AndroidVersion.isAtLeast(AndroidVersion.T)` | `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` | lint 能据此证明 `@RequiresApi(33)` 调用安全 |
| `AndroidVersion.isLessThan(AndroidVersion.P)` | `Build.VERSION.SDK_INT < Build.VERSION_CODES.P` | |
| `AndroidVersion.code` | `Build.VERSION.SDK_INT` | |
| `parentOrNull`（View 扩展） | `view.parent as? ViewGroup` | |
| `classOf<T>()` | `T::class.java` | |
| `AppViewsActivity` 基类 | 任意基类 + `LumenActivityDelegate` | 见 §4 |
| `toast(...)` | `Toast.makeText(context, text, duration).show()` | 引擎不弹 Toast，这一条只供宿主参考 |
| `isUiInNightMode` | `configuration.uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES` | |
| `updatePadding` / `updateMargins` | AndroidX core-ktx 的 `updatePadding` / `updateLayoutParams<MarginLayoutParams>` | |

## 4. 倡议条款

**倡议 1：宿主层可以自由使用 BetterAndroid。**

`AppViewsActivity`、系统栏控制、View 扩展、Hikage 布局 DSL 都可以照常使用。引擎只关心 `INTEGRATION_STANDARD.md` 规定的调用时机和层级关系，不关心布局和业务代码用什么写。

**倡议 2：接缝只放在一处。**

宿主应当写一个自己的 Activity 基类，在这里持有委托并完成转发；业务 Activity 继承这个基类。这样宿主的其他地方都不需要知道引擎的生命周期规则。示例：

```kotlin
/** 宿主自己的基类：BetterAndroid 的 AppViewsActivity + 凝光引擎委托（适配标准 §2.1）。 */
abstract class LumenViewsActivity : AppViewsActivity() {

    protected val lumen by lazy(LazyThreadSafetyMode.NONE) {
        LumenActivityDelegate(this, ::resolvePalette)
    }

    /** 宿主自己的取色，例如由 Monet 调色板换算成 LumenPalette（适配标准 §2.2）。 */
    protected abstract fun resolvePalette(): LumenPalette

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lumen.onDispatchTouchEvent(event)          // 在 super 之前
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        lumen.onStart()
    }

    override fun onStop() {
        lumen.onStop()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        lumen.onTrimMemory(level)
        super.onTrimMemory(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        lumen.onLowMemory()
        @Suppress("DEPRECATION")
        super.onLowMemory()
    }

    override fun onDestroy() {
        try {
            lumen.onDestroy()                      // 在 super 之前
        } finally {
            super.onDestroy()
        }
    }
}
```

业务 Activity 这样写：

```kotlin
class SettingsActivity : LumenViewsActivity() {

    override fun resolvePalette(): LumenPalette = AppTheme.lumenPalette(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Consent.granted(this)) {              // 适配标准 §2.3：未授权分支不 prepare
            showConsent()
            return
        }
        lumen.prepare()
        val root = buildRoot()                     // XML、代码或 Hikage 都可以
        setContentView(root)
        lumen.bindRoot(root) { recreate() }        // 适配标准 §2.4
    }
}
```

**倡议 3：守卫 `@RequiresApi` 的版本判断用原生写法。**

宿主的普通业务代码用 `AndroidVersion` 没有问题。但有两类代码，**建议**改用 `Build.VERSION.SDK_INT`，或者宿主自己写一个带 `@ChecksSdkIntAtLeast` 的小函数：

- 守卫 `@RequiresApi` 调用的判断；
- 仿照引擎写的 `ApiNN` 隔离类。

在这些地方，要定点压制 `ReplaceWithAndroidVersion`，并用注释写明原因，以免后来的人"顺手修好"。

**倡议 4：不要用反射访问引擎的 `internal` 成员。**

`internal` 不属于契约，版本之间随时可能变化，而且反射会绕过 §2.2 的版本边界保护。需要的能力，请求引擎公开 API。

**倡议 5：审计宿主自己的版本边界。**

`tools/audit_checkcast.py` 的第 5 个参数传入宿主自己的类前缀，就能审计宿主代码。对宿主来说，这类审计比写法风格更重要。

## 5. 何时重新评估

如果以后 BetterAndroid 的版本助手带上了 `@ChecksSdkIntAtLeast`，§2.2 的理由就会失效，倡议 3 可以随之放宽。

§2.1 可移植性和 §2.5 基类中立这两条理由不受影响，所以引擎本体仍然保持原生写法。

## 6. 与来源工程的关系

来源工程 Bilibili Innocent Lab 在宿主层大量使用 BetterAndroid、KavaRef 和 Hikage，这正是本倡议推荐的分工：宿主怎么写都可以，引擎保持中立。

来源工程今后切换到独立引擎时，它的 `SkinnedActivity`（继承 `AppViewsActivity`）只需按倡议 2 改为持有委托即可，业务代码不需要改动（见 `PORTABILITY_AUDIT.md` §8）。
