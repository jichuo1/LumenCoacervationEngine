package com.lumen.coacervation.engine.contract

import java.io.File

/**
 * 源码契约（"护栏"）测试的共享底座。
 *
 * 契约测试读取生产源码文本，断言某个真机教训对应的写法仍在：注册顺序、门控条件、不许复活的旧实现等。
 * 它们记录的是"为什么这里必须这样写"，比注释更难被无意删掉。
 *
 * 锚点缺失时 [after]/[before] 直接失败并把锚点写进失败信息：`substringAfter/Before` 在锚点找不到时返回原串，
 * 窗口悄悄扩大成整份文件、`contains` 照样通过——代码一搬家护栏就静默失效。
 *
 * 读入的源码统一把 CRLF 换成 LF：Windows 工作副本与 Linux CI 的换行不同，多行锚点在两边必须同样成立。
 */
object SourceContract {
    const val JAVA_ROOT = "src/main/java/com/lumen/coacervation/engine"

    /**
     * [path] 可以是以 `src/` 开头的模块内路径，也可以是相对 [JAVA_ROOT] 的路径（如 `liquid/LiquidActivityRenderer.kt`）。
     * Gradle 的测试工作目录是模块目录（见 lumen-engine/build.gradle.kts），IDE 可能是工程根，两种都能找到。
     */
    fun file(path: String): File {
        val relative = if (path.startsWith("src/")) path else "$JAVA_ROOT/$path"
        return listOf(relative, "lumen-engine/$relative").map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("contract source not found: $path (cwd ${File(".").absolutePath})")
    }

    fun read(path: String): String = normalize(file(path).readText())

    fun normalize(text: String): String = text.replace("\r\n", "\n")
}

/** 同 [String.substringAfter]，但锚点缺失时失败而不是返回原串。 */
fun String.after(anchor: String): String {
    val index = indexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (after): \"$anchor\"")
    return substring(index + anchor.length)
}

/** 同 [String.substringBefore]，但锚点缺失时失败而不是返回原串。 */
fun String.before(anchor: String): String {
    val index = indexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (before): \"$anchor\"")
    return substring(0, index)
}

/** **有意**截到末尾：锚点存在时同 [before]，不存在时返回全部。只用于最后一段后面本来就没有分隔符的场景。 */
fun String.beforeOrRest(anchor: String): String {
    val index = indexOf(anchor)
    return if (index < 0) this else substring(0, index)
}

/** 同 [String.substringAfterLast]，但锚点缺失时失败。 */
fun String.afterLast(anchor: String): String {
    val index = lastIndexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (afterLast): \"$anchor\"")
    return substring(index + anchor.length)
}

/** 同 [String.substringBeforeLast]，但锚点缺失时失败。 */
fun String.beforeLast(anchor: String): String {
    val index = lastIndexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (beforeLast): \"$anchor\"")
    return substring(0, index)
}
