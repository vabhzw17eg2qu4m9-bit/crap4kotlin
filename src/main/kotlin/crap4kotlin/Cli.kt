package crap4kotlin

import java.io.File
import java.util.Locale

// CLI entry logic, port of the crap4 family cli.js. Exit codes:
// 0 pass; 1 usage error or missing/empty coverage file; 2 CRAP above threshold.

class UsageException(message: String) : RuntimeException(message)

data class Options(
    val paths: List<String> = emptyList(),
    val changed: Boolean = false,
    val help: Boolean = false,
    val coverage: String? = null,
    val threshold: String? = null,
    val diffBase: String? = null,
    val strict: Boolean = false,
    val excludes: List<String> = emptyList(),
)

const val DEFAULT_THRESHOLD = 8.0

fun runCli(argv: List<String>, out: (String) -> Unit, err: (String) -> Unit, cwd: File = File(".")): Int {
    val opts = try {
        parseArgs(argv)
    } catch (e: UsageException) {
        err(e.message + "\n")
        out(usage())
        return 1
    }
    if (opts.help) {
        out(usage())
        return 0
    }
    return runAnalysis(opts, out, err, cwd)
}

private fun runAnalysis(opts: Options, out: (String) -> Unit, err: (String) -> Unit, cwd: File): Int {
    val files = try {
        resolveSelection(opts, cwd)
    } catch (e: Exception) {
        err("${e.message}\n")
        return 1
    }
    if (files.isEmpty()) {
        out("No Kotlin files to analyze.\n")
        return 0
    }
    val diffLines = try {
        diffLinesFor(opts, files, cwd)
    } catch (e: Exception) {
        err("${e.message}\n")
        return 1
    }
    val threshold = parseThreshold(opts.threshold)
    val coveragePath = resolveCoveragePath(opts, cwd)
    val result = try {
        analyze(files, coveragePath, cwd, opts.strict, diffLines)
    } catch (e: CoverageInputException) {
        err("${e.message}. Generate unit-test coverage with `./gradlew test jacocoTestReport`.\n")
        return 1
    }
    if (diffLines != null && result.metrics.isEmpty()) {
        out("No Kotlin files to analyze.\n")
        return 0
    }
    return finish(result, threshold, coveragePath, out, err)
}

// zero matched rows is the vacuous-gate case: the same family policy that
// fails a run on missing or empty coverage
private fun finish(
    result: AnalysisResult,
    threshold: Double,
    coveragePath: File,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    if (result.metrics.isNotEmpty() && result.metrics.all { it.coverage == null }) {
        err("no analyzed function matches the coverage input at ${coveragePath.absolutePath}: the gate would pass vacuously. Generate unit-test coverage with `./gradlew test jacocoTestReport`.\n")
        return 1
    }
    out(formatReport(result.metrics, threshold, result.parseFailures))
    return verdict(result.metrics, threshold, err)
}

private fun verdict(metrics: List<MethodMetric>, threshold: Double, err: (String) -> Unit): Int {
    val max = maxCrap(metrics)
    if (max > threshold) {
        err(String.format(Locale.ROOT, "CRAP threshold exceeded: %.1f > %.1f%n", max, threshold))
        return 2
    }
    return 0
}

private fun resolveSelection(opts: Options, cwd: File): List<File> {
    val raw = when {
        opts.changed -> changedFiles(cwd)
        opts.paths.isNotEmpty() -> {
            requirePathsExist(opts, cwd)
            expandPaths(opts.paths, cwd)
        }
        else -> findSourceFiles(cwd)
    }
    return applyExcludes(raw, opts.excludes, cwd)
}

private fun requirePathsExist(opts: Options, cwd: File) {
    if (!opts.strict) return
    for (p in opts.paths) {
        if (!File(cwd, p).exists()) throw UsageException("path does not exist: $p")
    }
}

private fun applyExcludes(files: List<File>, excludes: List<String>, cwd: File): List<File> {
    if (excludes.isEmpty()) return files
    val globs = excludes.map { globToRegex(it) }
    return files.filter { f -> globs.none { g -> g.matches(toRelPath(f, cwd)) } }
}

private fun diffLinesFor(opts: Options, files: List<File>, cwd: File): Map<String, Set<Int>>? {
    if (opts.diffBase == null || files.isEmpty()) return null
    val relPaths = files.map { toRelPath(it, cwd) }
    return gitChangedLines(opts.diffBase, relPaths, cwd)
}

private fun resolveCoveragePath(opts: Options, cwd: File): File =
    File(cwd, opts.coverage ?: DEFAULT_COVERAGE)

fun parseThreshold(raw: String?): Double {
    if (raw == null) return DEFAULT_THRESHOLD
    val n = raw.toDoubleOrNull() ?: throw UsageException("Invalid threshold: $raw")
    if (n < 0) throw UsageException("Invalid threshold: $raw")
    return n
}

// ------------------------------------------------------------------ args

private val SIMPLE_FLAGS = mapOf(
    "--help" to "help", "-h" to "help", "--changed" to "changed", "--strict" to "strict",
)

fun parseArgs(argv: List<String>): Options {
    var opts = Options()
    var i = 0
    while (i < argv.size) {
        val applied = applyArg(opts, argv, i)
        opts = applied.first
        i = applied.second
    }
    validate(opts)
    return opts
}

private fun applyArg(opts: Options, argv: List<String>, i: Int): Pair<Options, Int> {
    val a = argv[i]
    val simple = SIMPLE_FLAGS[a]
    if (simple != null) return Pair(setFlag(opts, simple), i + 1)
    if (isFlag(a, "--coverage")) return takeValue(opts, argv, i, "--coverage") { o, v -> o.copy(coverage = v) }
    if (isFlag(a, "--threshold")) return takeValue(opts, argv, i, "--threshold") { o, v -> o.copy(threshold = v) }
    if (isFlag(a, "--diff-base")) return takeValue(opts, argv, i, "--diff-base") { o, v -> o.copy(diffBase = v) }
    if (isFlag(a, "--exclude")) return takeValue(opts, argv, i, "--exclude", true) { o, v -> o.copy(excludes = o.excludes + v) }
    if (a.startsWith("--")) throw UsageException("Unknown flag: $a")
    return Pair(opts.copy(paths = opts.paths + a), i + 1)
}

private fun isFlag(a: String, flag: String): Boolean = a == flag || a.startsWith("$flag=")

private fun setFlag(opts: Options, name: String): Options = when (name) {
    "help" -> opts.copy(help = true)
    "changed" -> opts.copy(changed = true)
    else -> opts.copy(strict = true)
}

private fun takeValue(
    opts: Options,
    argv: List<String>,
    i: Int,
    flag: String,
    append: Boolean = false,
    set: (Options, String) -> Options,
): Pair<Options, Int> {
    val a = argv[i]
    val inline = a.startsWith("$flag=")
    val v = if (inline) a.substring(flag.length + 1) else argv.getOrNull(i + 1)
    if (v == null) throw UsageException("$flag requires a value")
    return Pair(set(opts, v), if (inline) i + 1 else i + 2)
}

private fun validate(opts: Options) {
    parseThreshold(opts.threshold)
    if (opts.changed && opts.paths.isNotEmpty()) {
        throw UsageException("--changed cannot be combined with explicit paths")
    }
    if (opts.diffBase != null && opts.changed) {
        throw UsageException("--diff-base cannot be combined with --changed")
    }
    if (opts.diffBase != null && opts.diffBase.isBlank()) {
        throw UsageException("--diff-base requires a non-empty git ref")
    }
}

fun usage(): String = """
Usage:
  crap4kotlin                  Analyze all .kt files under src/main/kotlin
  crap4kotlin --changed        Analyze git-changed source files under src/main/kotlin
  crap4kotlin <path>...        Analyze explicit files / directories
  crap4kotlin --help           Print this help message
  crap4kotlin --coverage <p>   Override coverage file (default: $DEFAULT_COVERAGE)
                               A missing, unparseable, or record-free coverage
                               file fails the run (exit 1): unit-test
                               coverage is required. Generate it with
                               `./gradlew test jacocoTestReport`
  crap4kotlin --threshold <n>  Override CRAP threshold (default: $DEFAULT_THRESHOLD)
  crap4kotlin --diff-base REF  Analyze only the functions intersecting lines
                               added/changed vs git ref REF (mutually
                               exclusive with --changed)
  crap4kotlin --strict         Selected files with no coverage entry count as
                               0.0% covered instead of N/A, and explicit
                               paths must exist (exit 1 otherwise)
  crap4kotlin --exclude GLOB   Skip files whose project-relative path matches
                               GLOB (repeatable)

Exit codes: 0 success; 1 usage error or missing coverage; 2 CRAP threshold exceeded.
""".trimStart()
