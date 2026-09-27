plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // 只把 KGP 放上 buildscript classpath，**不 apply**：AGP 9 的内置 Kotlin 读它的版本决定编译器，
    // 不声明就退回 AGP 自带的旧编译器。任何模块真的 apply 它，AGP 会让出内置 Kotlin。
    alias(libs.plugins.kotlin.android) apply false
}
