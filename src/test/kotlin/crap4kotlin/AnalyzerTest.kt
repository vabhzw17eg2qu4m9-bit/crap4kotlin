package crap4kotlin

import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerTest {
    private val fooSrc = """
        package demo

        class Foo {
            fun bar(): Int {
                return 1
            }
            fun baz(): Int {
                return 2
            }
        }
    """.trimIndent()

    private fun project(tmp: File, source: String = fooSrc, name: String = "Foo.kt"): File {
        val src = File(tmp, "src/main/kotlin/demo").apply { mkdirs() }
        File(src, name).writeText(source)
        return tmp
    }

    private fun report(
        tmp: File,
        entries: String,
        className: String = "demo/Foo",
        sourceFile: String = "Foo.kt",
    ): File {
        val dir = File(tmp, "build/reports/jacoco/test").apply { mkdirs() }
        return File(dir, "jacocoTestReport.xml").apply {
            writeText(
                """
                <report>
                  <class name="$className" sourcefilename="$sourceFile">
                    $entries
                  </class>
                </report>
                """.trimIndent(),
            )
        }
    }

    private fun counter(line: Int, missed: Int, covered: Int): String =
        """<method name="m" line="$line"><counter type="LINE" missed="$missed" covered="$covered"/></method>"""

    @Test
    fun attributesCoverageByClassAndLine(@TempDir tmp: File) {
        project(tmp)
        report(tmp, counter(5, 0, 1))
        val result = analyze(listOf(File(tmp, "src/main/kotlin/demo/Foo.kt")), File(tmp, "build/reports/jacoco/test/jacocoTestReport.xml"), tmp, strict = false, diffLines = null)
        assertEquals(2, result.metrics.size)
        val bar = result.metrics.first { it.methodName == "Foo.bar()" }
        val baz = result.metrics.first { it.methodName == "Foo.baz()" }
        assertEquals(1.0, bar.coverage)
        assertEquals(1.0, bar.crapScore)
        assertNull(baz.coverage)
        assertNull(baz.crapScore)
        assertEquals("src/main/kotlin/demo/Foo.kt", bar.file)
    }

    @Test
    fun partialCoverageFeedsFormula(@TempDir tmp: File) {
        project(tmp)
        report(tmp, counter(5, 1, 1))
        val result = analyze(listOf(File(tmp, "src/main/kotlin/demo/Foo.kt")), File(tmp, "build/reports/jacoco/test/jacocoTestReport.xml"), tmp, strict = false, diffLines = null)
        val bar = result.metrics.first { it.methodName == "Foo.bar()" }
        assertEquals(0.5, bar.coverage)
        assertEquals(1.125, bar.crapScore)
    }

    @Test
    fun strictCountsMissingFileAsZero(@TempDir tmp: File) {
        // the report records a different source file: Foo.kt has no entry
        project(tmp)
        report(tmp, """<method name="x" line="1"><counter type="LINE" missed="0" covered="1"/></method>""", className = "other/Other", sourceFile = "Other.kt")
        val result = analyze(listOf(File(tmp, "src/main/kotlin/demo/Foo.kt")), File(tmp, "build/reports/jacoco/test/jacocoTestReport.xml"), tmp, strict = true, diffLines = null)
        val baz = result.metrics.first { it.methodName == "Foo.baz()" }
        assertEquals(0.0, baz.coverage)
        assertEquals(2.0, baz.crapScore)
    }

    @Test
    fun diffSelectionKeepsOnlyIntersectingMethods(@TempDir tmp: File) {
        project(tmp)
        val result = analyze(listOf(File(tmp, "src/main/kotlin/demo/Foo.kt")), null, tmp, strict = false, diffLines = mapOf("src/main/kotlin/demo/Foo.kt" to setOf(7)))
        assertEquals(listOf("Foo.baz()"), result.metrics.map { it.methodName })
    }

    @Test
    fun unreadableFileBecomesParseFailure(@TempDir tmp: File) {
        project(tmp)
        val src = File(tmp, "src/main/kotlin/demo/Bad.kt")
        src.writeText("class Bad")
        src.setReadable(false)
        val result = analyze(listOf(src), null, tmp, strict = false, diffLines = null)
        assertEquals(listOf("src/main/kotlin/demo/Bad.kt"), result.parseFailures)
        assertTrue(result.metrics.isEmpty())
    }

    @Test
    fun nestedPackageMatchesSlashedJacocoClass(@TempDir tmp: File) {
        // regression: JaCoCo names packages with slashes (a/b/c), the source
        // statement uses dots (package a.b.c) — the row must not be N/A
        val src = File(tmp, "src/main/kotlin/a/b/c").apply { mkdirs() }
        File(src, "Foo.kt").writeText(
            """
            package a.b.c

            class Foo {
                fun bar(): Int {
                    return 1
                }
            }
            """.trimIndent(),
        )
        val dir = File(tmp, "build/reports/jacoco/test").apply { mkdirs() }
        File(dir, "jacocoTestReport.xml").writeText(
            """
            <report>
              <package name="a/b/c">
                <class name="a/b/c/Foo" sourcefilename="Foo.kt">
                  <method name="bar" line="5"><counter type="LINE" missed="0" covered="1"/></method>
                </class>
              </package>
            </report>
            """.trimIndent(),
        )
        val result = analyze(listOf(File(tmp, "src/main/kotlin/a/b/c/Foo.kt")), File(tmp, "build/reports/jacoco/test/jacocoTestReport.xml"), tmp, strict = false, diffLines = null)
        val bar = result.metrics.single()
        assertEquals("Foo.bar()", bar.methodName)
        assertEquals(1.0, bar.coverage)
        assertEquals(1.0, bar.crapScore)
    }

    @Test
    fun missingCoverageThrows(@TempDir tmp: File) {
        project(tmp)
        try {
            analyze(listOf(File(tmp, "src/main/kotlin/demo/Foo.kt")), File(tmp, "nope.xml"), tmp, strict = false, diffLines = null)
            throw AssertionError("expected CoverageInputException")
        } catch (e: CoverageInputException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }
}
