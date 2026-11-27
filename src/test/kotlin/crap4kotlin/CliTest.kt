package crap4kotlin

import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private val out = StringBuilder()
    private val err = StringBuilder()

    private fun run(args: List<String>, cwd: File): Int =
        runCli(args, { out.append(it) }, { err.append(it) }, cwd)

    private fun reset() {
        out.clear()
        err.clear()
    }

    private fun project(tmp: File): File {
        val src = File(tmp, DEFAULT_SOURCE_DIR).apply { mkdirs() }
        File(src, "Demo.kt").writeText(
            """
            package demo

            class Demo {
                fun a(): Int {
                    return 1
                }
            }
            """.trimIndent(),
        )
        return tmp
    }

    private fun report(
        tmp: File,
        missed: Int,
        covered: Int,
        line: Int = 5,
        className: String = "demo/Demo",
        sourceFile: String = "Demo.kt",
        methodName: String = "a",
    ): File {
        val dir = File(tmp, "build/reports/jacoco/test").apply { mkdirs() }
        return File(dir, "jacocoTestReport.xml").apply {
            writeText(
                """
                <report>
                  <class name="$className" sourcefilename="$sourceFile">
                    <method name="$methodName" line="$line"><counter type="LINE" missed="$missed" covered="$covered"/></method>
                  </class>
                </report>
                """.trimIndent(),
            )
        }
    }

    @Test
    fun helpExitsZero() {
        reset()
        assertEquals(0, run(listOf("--help"), File(".")))
        assertTrue(out.contains("Usage:"))
        assertTrue(out.contains("--threshold"))
    }

    @Test
    fun unknownFlagExitsOne() {
        reset()
        assertEquals(1, run(listOf("--wat"), File(".")))
        assertTrue(err.contains("Unknown flag: --wat"))
        assertTrue(out.contains("Usage:"))
    }

    @Test
    fun invalidThresholdExitsOne() {
        reset()
        assertEquals(1, run(listOf("--threshold", "abc"), File(".")))
        assertTrue(err.contains("Invalid threshold: abc"))
        reset()
        assertEquals(1, run(listOf("--threshold", "-1"), File(".")))
        assertTrue(err.contains("Invalid threshold: -1"))
    }

    @Test
    fun changedWithPathsExitsOne() {
        reset()
        assertEquals(1, run(listOf("--changed", "src"), File(".")))
        assertTrue(err.contains("--changed cannot be combined with explicit paths"))
    }

    @Test
    fun diffBaseWithChangedExitsOne() {
        reset()
        assertEquals(1, run(listOf("--diff-base", "HEAD", "--changed"), File(".")))
        assertTrue(err.contains("--diff-base cannot be combined with --changed"))
    }

    @Test
    fun strictMissingPathExitsOne(@TempDir tmp: File) {
        reset()
        assertEquals(1, run(listOf("--strict", "missing/dir"), tmp))
        assertTrue(err.contains("path does not exist: missing/dir"))
    }

    @Test
    fun emptyProjectExitsZero(@TempDir tmp: File) {
        reset()
        assertEquals(0, run(emptyList(), tmp))
        assertTrue(out.contains("No Kotlin files to analyze."))
    }

    @Test
    fun missingCoverageExitsOne(@TempDir tmp: File) {
        project(tmp)
        reset()
        assertEquals(1, run(emptyList(), tmp))
        assertTrue(err.contains("coverage file not found"))
        assertTrue(err.contains("./gradlew test jacocoTestReport"))
    }

    @Test
    fun emptyCoverageFileExitsOne(@TempDir tmp: File) {
        project(tmp)
        File(tmp, "empty.xml").writeText("")
        reset()
        assertEquals(1, run(listOf("--coverage", "empty.xml"), tmp))
        assertTrue(err.contains("coverage file is empty"))
    }

    @Test
    fun coverageWithoutRecordsExitsOne(@TempDir tmp: File) {
        project(tmp)
        File(tmp, "norec.xml").writeText("<report></report>")
        reset()
        assertEquals(1, run(listOf("--coverage", "norec.xml"), tmp))
        assertTrue(err.contains("no records"))
    }

    @Test
    fun coveredProjectExitsZero(@TempDir tmp: File) {
        project(tmp)
        report(tmp, missed = 0, covered = 1)
        reset()
        assertEquals(0, run(emptyList(), tmp))
        assertTrue(out.contains("Max CRAP: 1.0 (threshold 8.0) — passed"))
        assertTrue(out.contains("Demo.a()"))
    }

    @Test
    fun uncoveredComplexityNineExitsTwo(@TempDir tmp: File) {
        // the acceptance proof: an uncovered method with complexity 9 (8 ifs)
        val ifs = (1..8).joinToString("\n") { "        if (a == $it) return $it" }
        val src = """
            package demo

            class Big {
                fun f(a: Int): Int {
            $ifs
                    return 0
                }
            }
        """.trimIndent()
        project(tmp).let { File(it, "$DEFAULT_SOURCE_DIR").deleteRecursively() }
        val dir = File(tmp, DEFAULT_SOURCE_DIR).apply { mkdirs() }
        File(dir, "Big.kt").writeText(src)
        val funLine = src.lines().indexOfFirst { it.contains("fun f(") } + 1
        report(tmp, missed = 1, covered = 0, line = funLine + 1, className = "demo/Big", sourceFile = "Big.kt", methodName = "f")
        reset()
        assertEquals(2, run(emptyList(), tmp))
        assertTrue(err.contains("CRAP threshold exceeded: 90.0 > 8.0"))
        assertTrue(out.contains("— FAILED"))
    }

    @Test
    fun strictFlagCountsUncoveredFileAsZero(@TempDir tmp: File) {
        // Demo.kt has a complexity-3 method; with --strict the missing entry
        // counts as 0.0% covered -> CRAP 12 -> exit 2
        val dir = File(tmp, DEFAULT_SOURCE_DIR).apply { mkdirs() }
        File(dir, "Demo.kt").writeText(
            """
            package demo

            class Demo {
                fun a(x: Int): Int {
                    if (x == 1) return 1
                    if (x == 2) return 2
                    return 0
                }
            }
            """.trimIndent(),
        )
        val reports = File(tmp, "build/reports/jacoco/test").apply { mkdirs() }
        File(reports, "jacocoTestReport.xml").writeText(
            """
            <report>
              <class name="other/Other" sourcefilename="Other.kt">
                <method name="x" line="1"><counter type="LINE" missed="0" covered="1"/></method>
              </class>
            </report>
            """.trimIndent(),
        )
        reset()
        assertEquals(2, run(listOf("--strict"), tmp))
        assertTrue(out.contains("0.0%"))
        assertTrue(err.contains("CRAP threshold exceeded: 12.0 > 8.0"))
    }

    @Test
    fun excludeDropsFiles(@TempDir tmp: File) {
        val src = File(tmp, DEFAULT_SOURCE_DIR).apply { mkdirs() }
        File(src, "Keep.kt").writeText("class Keep { fun k() { } }")
        File(src, "gen").mkdirs()
        File(src, "gen/Drop.kt").writeText("class Drop { fun d() { } }")
        File(tmp, "build/reports/jacoco/test").apply { mkdirs() }
        File(tmp, "build/reports/jacoco/test/jacocoTestReport.xml").writeText(
            """
            <report>
              <class name="Keep" sourcefilename="Keep.kt">
                <method name="k" line="1"><counter type="LINE" missed="0" covered="1"/></method>
              </class>
            </report>
            """.trimIndent(),
        )
        reset()
        assertEquals(0, run(listOf("--exclude", "**/gen/**"), tmp))
        assertTrue(!out.contains("Drop.d()"))
        assertTrue(out.contains("Keep.k()"))
    }

    @Test
    fun coverageMatchingNothingExitsOne(@TempDir tmp: File) {
        project(tmp)
        // the report records a different source file: no analyzed row matches
        val dir = File(tmp, "build/reports/jacoco/test").apply { mkdirs() }
        File(dir, "jacocoTestReport.xml").writeText(
            """
            <report>
              <class name="other/Other" sourcefilename="Other.kt">
                <method name="x" line="1"><counter type="LINE" missed="0" covered="1"/></method>
              </class>
            </report>
            """.trimIndent(),
        )
        reset()
        assertEquals(1, run(emptyList(), tmp))
        assertTrue(err.contains("no analyzed function matches the coverage input"))
        assertTrue(err.contains("pass vacuously"))
    }

    @Test
    fun diffBaseWithoutChangesAnalyzesNothing(@TempDir tmp: File) {
        val repo = project(tmp)
        git(repo, "init")
        git(repo, "add", ".")
        git(repo, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "init")
        report(tmp, missed = 0, covered = 1)
        reset()
        assertEquals(0, run(listOf("--diff-base", "HEAD"), repo))
        assertTrue(out.contains("No Kotlin files to analyze."))
    }

    private fun git(dir: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args.toList()).start()
        val code = proc.waitFor()
        if (code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed: " + proc.errorStream.bufferedReader().readText())
    }
}
