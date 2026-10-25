package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Baseline Profile 护栏（2026-10-25）：规则随 AAR 合并进宿主，指向不存在的类会静默失效，
 * 写得过宽又会增加宿主的内存与磁盘占用。每条规则必须指向本模块真实声明的类，且只用三种固定写法。
 */
class BaselineProfileContractTest {
    @Test fun everyRuleNamesAnExistingHotPathClass() {
        val rules = BaselineProfileRules.parse(SourceContract.read("src/main/baseline-prof.txt"))
        BaselineProfileRules.assertDeclared(rules, SourceContract.file("material/LiveBackdropSampler.kt").parentFile.parentFile)
        assertTrue("profile should stay limited to hot paths: ${rules.size} classes", rules.size in 1..40)
        listOf("material/ModernBackdropBlur", "material/LensRefractionPolicy", "material/LiveBackdropSampler",
            "runtime/ActivitySkinSession", "liquid/LiquidActivityRenderer").forEach {
            assertTrue("hot path $it must be profiled", it in rules)
        }
    }
}

internal object BaselineProfileRules {
    private const val PREFIX = "com/lumen/coacervation/engine/"
    private val CLASS = Regex("""L$PREFIX([A-Za-z0-9/]+);""")
    private val METHODS = Regex("""HSPL$PREFIX([A-Za-z0-9/]+)(\$\*)?;->\*\*\(\*\*\)\*\*""")

    /** 返回 `包/类` 集合；任何不属于三种固定写法的行直接失败。 */
    fun parse(text: String): Set<String> {
        val classes = LinkedHashSet<String>()
        for (line in text.lines().map(String::trim)) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val name = CLASS.matchEntire(line)?.groupValues?.get(1)
                ?: METHODS.matchEntire(line)?.groupValues?.get(1)
                ?: throw AssertionError("unexpected baseline profile rule: $line")
            classes += name
        }
        return classes
    }

    fun assertDeclared(classes: Set<String>, javaRoot: File) {
        for (name in classes) {
            val packageDir = File(javaRoot, name.substringBeforeLast('/'))
            val simple = name.substringAfterLast('/')
            val declaration = Regex("""\b(class|object|interface)\s+$simple\b""")
            val found = packageDir.listFiles { file -> file.name.endsWith(".kt") }.orEmpty()
                .any { declaration.containsMatchIn(it.readText()) }
            assertTrue("baseline profile names a missing class: $name", found)
        }
    }
}
