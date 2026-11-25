package crap4kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTest {
    private fun metric(
        name: String,
        file: String = "a.kt",
        line: Int = 1,
        cc: Int = 1,
        cov: Double? = 1.0,
    ) = MethodMetric(name, file, line, cc, cov, crapScore(cc, cov))

    @Test
    fun headerAndSummaryShape() {
        val report = formatReport(listOf(metric("Foo.bar")), 8.0)
        val lines = report.lines()
        assertEquals("CRAP Report", lines[0])
        assertEquals("===========", lines[1])
        assertTrue(lines[2].startsWith("Method"))
        assertTrue(lines[3].all { it == '-' })
        assertTrue(report.endsWith("Max CRAP: 1.0 (threshold 8.0) — passed\n"))
    }

    @Test
    fun rowFormat() {
        val report = formatReport(listOf(metric("Foo.bar", "src/Foo.kt", 3, 5, 0.8)), 8.0)
        val row = report.lines()[4]
        assertTrue(row.contains("Foo.bar"))
        assertTrue(row.contains("src/Foo.kt"))
        assertTrue(row.trimEnd().endsWith("5.2"))
        assertTrue(row.contains("80.0%"))
    }

    @Test
    fun naCoverageAndScore() {
        val report = formatReport(listOf(metric("Foo.bar", cov = null)), 8.0)
        val row = report.lines()[4]
        assertTrue(row.contains("N/A"))
        assertTrue(report.contains("— passed"))
    }

    @Test
    fun failedVerdictAboveThreshold() {
        val report = formatReport(listOf(metric("Big.f", cc = 9, cov = 0.0)), 8.0)
        assertTrue(report.contains("Max CRAP: 90.0 (threshold 8.0) — FAILED"))
    }

    @Test
    fun sortedByCrapDescending() {
        val report = formatReport(
            listOf(metric("low"), metric("high", cc = 9, cov = 0.0), metric("mid", cc = 4, cov = 0.0)),
            100.0,
        )
        val rows = report.lines().filterIndexed { i, l -> i in 4..6 }
        assertTrue(rows[0].trimStart().startsWith("high"))
        assertTrue(rows[1].trimStart().startsWith("mid"))
        assertTrue(rows[2].trimStart().startsWith("low"))
    }

    @Test
    fun naRowsSortLast() {
        val report = formatReport(
            listOf(metric("na", cov = null), metric("scored", cc = 2, cov = 0.0)),
            100.0,
        )
        val rows = report.lines().filterIndexed { i, l -> i in 4..5 }
        assertTrue(rows[0].contains("scored"))
        assertTrue(rows[1].contains("na"))
    }

    @Test
    fun tieBrokenByFileThenLine() {
        val report = formatReport(
            listOf(metric("b", file = "z.kt", line = 5, cov = 0.0), metric("a", file = "a.kt", line = 9, cov = 0.0)),
            100.0,
        )
        val rows = report.lines().filterIndexed { i, l -> i in 4..5 }
        assertTrue(rows[0].contains("a.kt"))
        assertTrue(rows[1].contains("z.kt"))
    }

    @Test
    fun emptyReportPasses() {
        val report = formatReport(emptyList(), 8.0)
        assertTrue(report.contains("Max CRAP: 0.0 (threshold 8.0) — passed"))
    }

    @Test
    fun parseFailuresLineAppended() {
        val report = formatReport(emptyList(), 8.0, listOf("Broken.kt"))
        assertTrue(report.contains("Parse failures: 1 file(s) — see warnings above.\n"))
    }

    @Test
    fun maxCrapIgnoresNulls() {
        assertEquals(4.125, maxCrap(listOf(metric("x", cov = null), metric("y", cc = 3, cov = 0.5))))
        assertEquals(0.0, maxCrap(emptyList()))
    }
}
