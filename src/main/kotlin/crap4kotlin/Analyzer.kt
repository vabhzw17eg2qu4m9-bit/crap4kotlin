package crap4kotlin

import java.io.File

// Orchestrates parsing + coverage attribution to produce MethodMetric[].
// A coverage input that is missing, unparseable, or record-free throws
// (fail-closed — a CRAP score without coverage is meaningless).

data class AnalysisResult(val metrics: List<MethodMetric>, val parseFailures: List<String>)

fun analyze(
    filePaths: List<File>,
    coveragePath: File?,
    projectRoot: File,
    strict: Boolean,
    diffLines: Map<String, Set<Int>>?,
): AnalysisResult {
    val coverage = coveragePath?.let { loadCoverage(it) }
    val metrics = mutableListOf<MethodMetric>()
    val failures = mutableListOf<String>()
    for (file in filePaths) {
        analyzeFile(file, projectRoot, coverage, strict, diffLines, metrics, failures)
    }
    metrics.sortWith(::compareMetricsForReport)
    return AnalysisResult(metrics, failures)
}

private fun analyzeFile(
    file: File,
    projectRoot: File,
    coverage: CoverageData?,
    strict: Boolean,
    diffLines: Map<String, Set<Int>>?,
    metrics: MutableList<MethodMetric>,
    failures: MutableList<String>,
) {
    val rel = toRelPath(file, projectRoot)
    val extracted = try {
        extractMethods(file.readText(), file.name)
    } catch (e: Exception) {
        System.err.println("Warning: failed to parse $rel: ${e.message}")
        failures += rel
        return
    }
    val selected = inDiffSelection(diffLines, rel, extracted.methods) ?: return
    val uncovered = strict && coverage != null && !coverage.sourceFiles.contains(file.name)
    for (m in selected) metrics += metricOf(m, extracted, rel, coverage, uncovered)
}

// Diff-scoped runs keep only the functions whose line range intersects an
// added/changed line; a file with no intersecting function contributes nothing.
private fun inDiffSelection(
    diffLines: Map<String, Set<Int>>?,
    rel: String,
    methods: List<MethodDescriptor>,
): List<MethodDescriptor>? {
    if (diffLines == null) return methods
    val selected = methods.filter { diffIntersects(diffLines, rel, it.startLine, it.endLine) }
    return selected.ifEmpty { null }
}

private fun metricOf(
    m: MethodDescriptor,
    extracted: ExtractResult,
    rel: String,
    coverage: CoverageData?,
    uncovered: Boolean,
): MethodMetric {
    val complexity = methodComplexity(m, extracted)
    val cov = if (uncovered) 0.0 else coverage?.let { coverageFor(it, CoverageKey(m.className, m.covLine)) }
    return MethodMetric(m.name, rel, m.startLine, complexity, cov, crapScore(complexity, cov))
}

// Body complexity; token ranges of local funs nested in the body belong to
// the child entry and are excluded from the parent.
private fun methodComplexity(m: MethodDescriptor, extracted: ExtractResult): Int {
    if (m.spanStart > m.spanEnd) return 1
    val excluded = extracted.methods
        .filter { it !== m && nestedIn(it, m) }
        .map { it.headerIdx..it.spanEnd }
    return complexityOf(extracted.tokens, m.spanStart, m.spanEnd, excluded)
}

private fun nestedIn(child: MethodDescriptor, parent: MethodDescriptor): Boolean =
    child.headerIdx >= parent.headerIdx && child.spanEnd <= parent.spanEnd
