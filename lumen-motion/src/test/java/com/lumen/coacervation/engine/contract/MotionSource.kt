package com.lumen.coacervation.engine.contract

import java.io.File

/**
 * lumen-motion 源码的契约扫描器（移植自来源工程的 `SettingsUiSource`）：按文件名或函数名切出生产源码，
 * 注释与字符串在同一趟里识别。来源工程在 MainActivity 与分卷里找函数；这里在整个 lumen-motion 的源码里找。
 */
internal object MotionSource {
    private val CONTINUATIONS = listOf("=", "->", "+", ",", ".", ":", "&&", "||", "?:", "?")

    private val root: File by lazy { SourceContract.file("${SourceContract.JAVA_ROOT}/motion/InterruptibleMotion.kt").parentFile.parentFile }

    /** 文件名（不含路径）→ 源码。 */
    fun files(): List<Pair<String, String>> = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .sortedBy { it.path }
        .map { it.name to SourceContract.normalize(it.readText()) }
        .toList()

    /** 按简单文件名取源码（在所有子包里找，必须唯一）。 */
    fun file(name: String): String {
        val fileName = if (name.endsWith(".kt")) name else "$name.kt"
        val hits = root.walkTopDown().filter { it.isFile && it.name == fileName }.toList()
        require(hits.size == 1) { "source $fileName found ${hits.size} times" }
        return SourceContract.normalize(hits.single().readText())
    }

    /** 引擎本体（lumen-engine）的源码，按简单文件名找；用于"动效与引擎采样配合"的跨模块契约。 */
    fun engineFile(name: String): String {
        val fileName = if (name.endsWith(".kt")) name else "$name.kt"
        val engineRoot = listOf("../lumen-engine/${SourceContract.JAVA_ROOT}", "lumen-engine/${SourceContract.JAVA_ROOT}")
            .map(::File).firstOrNull(File::isDirectory)
            ?: throw AssertionError("lumen-engine sources not found (cwd ${File(".").absolutePath})")
        val hits = engineRoot.walkTopDown().filter { it.isFile && it.name == fileName }.toList()
        require(hits.size == 1) { "engine source $fileName found ${hits.size} times" }
        return SourceContract.normalize(hits.single().readText())
    }

    /** 全部源码拼在一起，供"整体存在性"断言使用。 */
    fun all(): String = files().joinToString("\n") { it.second }

    /**
     * 切出名为 [name] 的函数（**含签名**，从 `fun` 到配对的右花括号）。
     *
     * 在 lumen-motion 的全部源码里查找，命中必须**恰好一处**：
     * 找不到或重名都直接失败，避免退化成"在空串上通过"。
     */
    fun function(name: String): String {
        val hits = files().flatMap { (file, text) ->
            functions(text, name).map { file to it }
        }
        check(hits.isNotEmpty()) {
            "function `$name` not found in lumen-motion sources; it was probably renamed"
        }
        check(hits.size == 1) {
            "function `$name` is declared ${hits.size} times (${hits.map { it.first }}); " +
                "use functions() and assert on the intended overload"
        }
        return hits.single().second
    }

    /**
     * 只去注释、**保留字符串**的源码视图。
     *
     * 给"这个文件里有没有出现某种写法"这类断言用：注释里提到
     * `registerForActivityResult` 是在解释为什么**不能**这么写，不该被判成违规。
     * 字符串要保留——模板里的 `${'$'}{HookEntry.TARGET_PACKAGE}` 是真代码引用。
     *
     * **必须和 [mask] 用同一个扫描器**，也就是必须认识字符串字面量。
     * 早期版本只认两种注释起始符就抹，结果把 URL 字符串（`"https://…"`）里的双斜杠
     * 当成行注释，连它的右引号一起抹掉；后面 [mask] 再扫时看到一个**没有闭合的字符串**，
     * 于是一路吞到很远的下一个引号 —— 整份文件的结构消失，
     * `declaredFunctions` 从 125 个成员函数变成 0 个，而依赖它的断言全在空列表上"通过"。
     */
    fun code(source: String): String = scan(source, blankStrings = false)

    /** [source] 里所有名为 [name] 的函数声明（含签名），按出现顺序。 */
    fun functions(source: String, name: String): List<String> {
        val masked = mask(source)
        val pattern = Regex(
            """\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?""" +
                Regex.escape(name) + """\s*\("""
        )
        return pattern.findAll(masked).mapNotNull { match ->
            declarationEnd(masked, match.range.first)?.let { end ->
                source.substring(match.range.first, end)
            }
        }.toList()
    }

    /**
     * 顶层（或类成员）函数：名字 → 声明全文。
     *
     * [indent] 是声明的缩进宽度——类成员是 4，外移文件里的顶层扩展函数是 0。
     * 限定缩进才能把弹窗内部的局部 `fun`（例如 `fun decide(enabled: Boolean)`）排除掉，
     * 否则它们会被当成新的函数边界，把外层弹窗的函数体截断。
     */
    fun declaredFunctions(source: String, indent: Int): List<Pair<String, String>> {
        val masked = mask(source)
        val pattern = Regex(
            "(?m)^ {" + indent + "}(?:private |internal |public |protected |override |open |inline )*" +
                """fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?(\w+)\s*\("""
        )
        return pattern.findAll(masked).mapNotNull { match ->
            declarationEnd(masked, match.range.first)?.let { end ->
                match.groupValues[1] to source.substring(match.range.first, end)
            }
        }.toList()
    }

    /**
     * 从声明起点 [declStart]（`fun` 关键字处）出发，返回该声明结束后的下标。
     *
     * **逐行**累计 `(` `[` `{` 的深度，深度回到 0 的那一行就是声明的最后一行。
     * 不能只配对花括号——Kotlin 函数体有四种形态，只有深度法对四种都成立：
     *
     * ```
     * fun f() { … }                    代码块
     * fun f(): Int = when (x) { … }    表达式体 + 代码块
     * fun f(): String = getString(     表达式体，代码块**内嵌在括号里**：
     *     when { … }                   只配对花括号会停在这个 `}`，
     * )                                把结尾的 `)` 漏在外面
     * fun f() = bar()                  单行表达式体，本行深度就已经是 0
     * ```
     *
     * 第三种是实测踩过的：搬迁脚本按花括号配对切函数，把 `liquidRealtimeCaptureSummary`
     * 的收尾 `)` 留在了原文件里，编译一片红。这里的门禁如果也用花括号法，
     * 切出来的函数体会短一截——`assertTrue(contains(…))` 就会莫名其妙地失败，
     * 而 `assertFalse` 会莫名其妙地通过。
     */
    private fun declarationEnd(masked: String, declStart: Int): Int? {
        var lineStart = declStart
        var depth = 0
        var firstLine = true
        while (lineStart < masked.length) {
            val newline = masked.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) masked.length else newline
            val line = masked.substring(lineStart, lineEnd)
            depth += line.count { it == '(' || it == '[' || it == '{' }
            depth -= line.count { it == ')' || it == ']' || it == '}' }
            if (depth < 0) return null
            // 行尾是这些符号说明表达式还没写完，括号配平了也不能收尾。
            // （`fun f(spec: S): T? =` 换行再写返回值就是这种形态。）
            val continues = CONTINUATIONS.any { line.trimEnd().endsWith(it) }
            // 首行深度就为 0 只有单行表达式体一种可能（必须带 `=`）；
            // `fun f() {` 首行深度是 1，不会被误判成结束。
            val ended = depth == 0 && !continues && (!firstLine || line.contains('='))
            if (ended) return lineEnd
            firstLine = false
            lineStart = lineEnd + 1
        }
        return null
    }

    /**
     * 把注释与字符串字面量替换成等量空白，**长度逐字符对齐**。
     *
     * 花括号配对只能在这份副本上做（注释和字符串里的括号不算），
     * 但返回给断言的必须是原文——门禁本身要断言字符串字面量
     * （例如 `telemetry_choice_save_failed`），所以不能返回被抹掉的版本。
     */
    private fun mask(source: String): String = scan(source, blankStrings = true)

    /**
     * 唯一的源码扫描器：注释一律抹成等量空白，字符串按 [blankStrings] 决定抹还是留。
     *
     * 长度逐字符对齐，所以在结果上算出的下标可以直接回原文取片段。
     * **注释与字符串必须在同一趟里识别**：分成两趟做，先抹注释的那一趟会把
     * `"https://…"` 里的 `//` 当注释、连右引号一起抹掉，第二趟就从一个
     * 未闭合的字符串开始一路吞下去。
     */
    private fun scan(source: String, blankStrings: Boolean): String {
        val out = StringBuilder(source.length)
        var i = 0
        fun blankThrough(end: Int) {
            while (i < end && i < source.length) {
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
        }
        fun copyThrough(end: Int) {
            while (i < end && i < source.length) {
                out.append(source[i])
                i++
            }
        }
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blankThrough(end)
                }
                c == '/' && source.startsWith("/*", i) -> {
                    // Kotlin 的块注释可嵌套，按深度找真正的结尾。
                    var depth = 0
                    var j = i
                    while (j < source.length) {
                        if (source.startsWith("/*", j)) {
                            depth++
                            j += 2
                        } else if (source.startsWith("*/", j)) {
                            depth--
                            j += 2
                            if (depth == 0) break
                        } else {
                            j++
                        }
                    }
                    blankThrough(if (depth == 0) j else source.length)
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    if (blankStrings) blankThrough(end) else copyThrough(end)
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != c) {
                        j += if (source[j] == '\\') 2 else 1
                    }
                    val end = minOf(j + 1, source.length)
                    if (blankStrings) blankThrough(end) else copyThrough(end)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
