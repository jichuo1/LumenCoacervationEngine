package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** lumen-motion 的 Baseline Profile 护栏：规则只能指向本模块真实声明的透镜与交互会话类。 */
class MotionBaselineProfileContractTest {
    private val prefix = "com/lumen/coacervation/engine/"
    private val classRule = Regex("""L$prefix([A-Za-z0-9/]+);""")
    private val methodRule = Regex("""HSPL$prefix([A-Za-z0-9/]+)(\$\*)?;->\*\*\(\*\*\)\*\*""")

    @Test fun everyRuleNamesAnExistingMotionHotPathClass() {
        val profile = SourceContract.normalize(File("src/main/baseline-prof.txt").takeIf(File::isFile)
            ?.readText() ?: File("lumen-motion/src/main/baseline-prof.txt").readText())
        val classes = LinkedHashSet<String>()
        for (line in profile.lines().map(String::trim)) {
            if (line.isEmpty() || line.startsWith("#")) continue
            classes += classRule.matchEntire(line)?.groupValues?.get(1)
                ?: methodRule.matchEntire(line)?.groupValues?.get(1)
                ?: throw AssertionError("unexpected baseline profile rule: $line")
        }
        assertTrue(classes.size in 1..20)
        val javaRoot = listOf("src/main/java/com/lumen/coacervation/engine", "lumen-motion/src/main/java/com/lumen/coacervation/engine")
            .map(::File).first(File::isDirectory)
        for (name in classes) {
            val simple = name.substringAfterLast('/')
            val declaration = Regex("""\b(class|object|interface)\s+$simple\b""")
            val found = File(javaRoot, name.substringBeforeLast('/')).listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
                .any { declaration.containsMatchIn(it.readText()) }
            assertTrue("baseline profile names a missing class: $name", found)
        }
    }
}
