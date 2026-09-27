pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LumenCoacervationEngine"

// 引擎本体：材质、会话、悬浮栏可读性、触摸光晕。只依赖 AndroidX core/annotation。
include(":lumen-engine")
// 交互与动效：长按弹性形变与高光、可打断动画、弹窗/全屏的打开与关闭形变、翻页与文字链、手风琴。
include(":lumen-motion")
// 可选：把 SwitchCompat / CheckBox / EditText 换装成引擎材质。唯一依赖 AppCompat 的部分。
include(":lumen-controls")
// 接入示例：按 docs/INTEGRATION_STANDARD.md 的最小接入写法搭的演示应用。
include(":sample")
