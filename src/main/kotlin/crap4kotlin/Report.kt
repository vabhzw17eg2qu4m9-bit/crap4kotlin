package crap4kotlin

import java.util.Locale

// Tabular CRAP report formatter, identical to crap4js.
//
// Output shape:
//   CRAP Report
//   ===========
//   <header line of dashes>
//   <one row per method>
//   <blank line>
//   Max CRAP: <max> (threshold <t>) — FAILED|passed
//   [Parse failures: N file(s) — see warnings above.]
//
// Sorting: numeric CRAP descending; null (N/A) entries last; ties broken by
// (file asc, startLine asc). All-N/A report yields max 0.0 and "passed".

data class MethodMetric(
    val methodName: String,
    val file: String,
    val startLine: Int,
    val complexity: Int,
    val coverage: Double?,
    val crapScore: Double?,
)

private const val W_METHOD = 30
private const val W_FILE = 35
private const val W_CC = 4
private const val W_COV = 7
private const val W_CRAP = 8

fun maxCrap(metrics: List<MethodMetric>): Double =
    metrics.maxOfOrNull { it.crapScore ?: 0.0 } ?: 0.0

fun formatReport(metrics: List<MethodMetric>, threshold: Double, parseFailures: List<String> = emptyList()): String {
    val sorted = metrics.sortedWith(::compareMetricsForReport)
    val sb = StringBuilder()
    sb.append("CRAP Report\n")
    sb.append("===========\n")
    val header = padRight("Method", W_METHOD) + " " + padRight("File", W_FILE) + " " +
        padLeft("CC", W_CC) + " " + padLeft("Cov%", W_COV) + " " + padLeft("CRAP", W_CRAP)
    sb.append(header).append('\n')
    sb.append("-".repeat(header.length)).append('\n')
    for (m in sorted) {
        sb.append(padRight(m.methodName, W_METHOD)).append(' ')
            .append(padRight(m.file, W_FILE)).append(' ')
            .append(padLeft(m.complexity.toString(), W_CC)).append(' ')
            .append(padLeft(formatCoverage(m.coverage), W_COV)).append(' ')
            .append(padLeft(formatCrap(m.crapScore), W_CRAP)).append('\n')
    }
    val max = maxCrap(sorted)
    val verdict = if (max > threshold) "FAILED" else "passed"
    sb.append('\n')
    sb.append("Max CRAP: ${fmt(max)} (threshold ${fmt(threshold)}) — $verdict\n")
    if (parseFailures.isNotEmpty()) {
        sb.append("Parse failures: ${parseFailures.size} file(s) — see warnings above.\n")
    }
    return sb.toString()
}

// CRAP descending, null (N/A) last, ties by (file asc, startLine asc)
fun compareMetricsForReport(a: MethodMetric, b: MethodMetric): Int {
    val aNull = a.crapScore == null
    val bNull = b.crapScore == null
    if (aNull && bNull) return tieBreak(a, b)
    if (aNull) return 1
    if (bNull) return -1
    if (b.crapScore != a.crapScore) return b.crapScore!!.compareTo(a.crapScore!!)
    return tieBreak(a, b)
}

private fun tieBreak(a: MethodMetric, b: MethodMetric): Int {
    if (a.file != b.file) return a.file.compareTo(b.file)
    return a.startLine.compareTo(b.startLine)
}

private fun formatCoverage(coverage: Double?): String {
    if (coverage == null) return "N/A"
    return fmt(coverage * 100) + "%"
}

private fun formatCrap(score: Double?): String {
    if (score == null) return "N/A"
    return fmt(score)
}

private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

fun padLeft(s: String, w: Int): String = s.padStart(w)

fun padRight(s: String, w: Int): String = s.padEnd(w)
