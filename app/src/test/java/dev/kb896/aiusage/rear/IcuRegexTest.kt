package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every Regex in the app compiles on the phone. Android's java.util.regex is ICU's, and ICU
 * refuses what the JVM — which these tests run on — lets by: a bare `}` (or `{` that opens no
 * {n,m}), a bare `]` outside a character class. One of them (SurfacePlace's, a bare `}`)
 * passed every test here and threw on the phone as its object initialised, and the service
 * crashed. So every pattern literal in the sources is read and checked by ICU's stricter rule.
 */
class IcuRegexTest {

    /** Where a pattern would not compile on ICU, by that rule; empty when it would. */
    private fun problems(p: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var depth = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\') { i += 2; continue }
            if (depth > 0) {
                when (c) {
                    '[' -> depth++
                    ']' -> depth--
                }
                i++
                continue
            }
            when (c) {
                '[' -> {
                    depth = 1
                    i++
                    if (i < p.length && p[i] == '^') i++
                    if (i < p.length && p[i] == ']') i++ // a leading ] is the set's own
                    continue
                }
                ']' -> out += "bare ] at $i"
                '}' -> out += "bare } at $i"
                '{' -> {
                    val q = Regex("""\{\d+(,\d*)?\}""").matchAt(p, i)
                    if (q == null) out += "{ that opens no {n,m} at $i" else { i += q.value.length; continue }
                }
            }
            i++
        }
        if (depth > 0) out += "unclosed ["
        return out
    }

    @Test fun `the rule itself`() {
        assertTrue(problems("""^\s*bounds=\{(-?\d+)}""").isNotEmpty())
        assertTrue(problems("""Layer \[\d+] x""").isNotEmpty())
        assertTrue(problems("""a{b""").isNotEmpty())
        assertEquals(emptyList<String>(), problems("""^\s*bounds=\{(-?\d+)\}"""))
        assertEquals(emptyList<String>(), problems("""ComponentInfo\{([^}]+)\}"""))
        assertEquals(emptyList<String>(), problems("""a{2,3}b{4}c{1,}"""))
        assertEquals(emptyList<String>(), problems("""[]a][^]b]"""))
    }

    /** Every pattern literal given to Regex(…), "…".toRegex() or Pattern.compile(…) under the app's sources. */
    private fun patterns(): List<Pair<String, String>> {
        val root = File("src/main/java")
        val raw = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val plain = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val calls = Regex("""(?:Regex|Pattern\.compile)\(\s*""")
        val out = mutableListOf<Pair<String, String>>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            fun take(at: Int) {
                raw.matchAt(src, at)?.let { out += f.name to deinterpolate(it.groupValues[1]); return }
                plain.matchAt(src, at)?.let { out += f.name to deinterpolate(unescape(it.groupValues[1])) }
            }
            calls.findAll(src).forEach { take(it.range.last + 1) }
            // "…".toRegex(): the literal just before.
            Regex("""(\"\"\"(?:.*?)\"\"\"|"(?:[^"\\\n]|\\.)*")\.toRegex\(""", RegexOption.DOT_MATCHES_ALL).findAll(src).forEach { take(it.range.first) }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\\$", "$").replace("\u0000", "\\")

    /** A template's ${…} and $name stand for something plain. */
    private fun deinterpolate(s: String) = s.replace(Regex("""\$\{[^}]*\}"""), "0").replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "0")

    @Test fun `every pattern in the sources compiles on ICU`() {
        val all = patterns()
        assertTrue("the scan found the patterns (${all.size})", all.size >= 30)
        val bad = all.mapNotNull { (file, p) -> problems(p).takeIf { it.isNotEmpty() }?.let { "$file: $p — $it" } }
        assertEquals(emptyList<String>(), bad)
    }
}
