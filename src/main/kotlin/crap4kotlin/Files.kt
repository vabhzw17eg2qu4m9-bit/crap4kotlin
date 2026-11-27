package crap4kotlin

import java.io.File

// Source-file discovery: walk the default source dir, expand CLI paths,
// detect git-changed files, convert exclude globs, parse diff hunks.
// Port of the crap4 family files.js + diffLines.js.

const val DEFAULT_SOURCE_DIR = "src/main/kotlin"
const val DEFAULT_COVERAGE = "build/reports/jacoco/test/jacocoTestReport.xml"
const val SOURCE_EXTENSION = ".kt"
private val CHANGE_STATUS = setOf('M', 'A', 'R', 'C', '?')

fun findSourceFiles(projectRoot: File): List<File> =
    expandPaths(listOf(DEFAULT_SOURCE_DIR), projectRoot)

fun expandPaths(args: List<String>, projectRoot: File): List<File> {
    val files = sortedSetOf(compareBy<File> { it.absolutePath })
    for (arg in args) files += expandPath(File(projectRoot, arg).absoluteFile)
    return files.toList()
}

private fun expandPath(path: File): List<File> = when {
    path.isDirectory -> walkSourceDir(path)
    isKotlinFile(path) -> listOf(path)
    else -> emptyList()
}

private fun walkSourceDir(dir: File): List<File> =
    dir.walkTopDown().filter { isKotlinFile(it) }.sortedBy { it.absolutePath }.toList()

private fun isKotlinFile(path: File): Boolean = path.isFile && path.name.endsWith(SOURCE_EXTENSION)

fun toRelPath(file: File, projectRoot: File): String {
    val root = projectRoot.absoluteFile.toPath()
    val target = file.absoluteFile.toPath()
    return root.relativize(target).toString().replace(File.separatorChar, '/')
}

// `git status --porcelain` -> modified/added/renamed/copied/untracked source
// files under the default source dir.
fun changedFiles(projectRoot: File): List<File> {
    val out = runGit(listOf("-C", projectRoot.absolutePath, "status", "--porcelain"))
    val files = sortedSetOf(compareBy<File> { it.absolutePath })
    for (line in out.lineSequence()) {
        val path = changedSourcePath(line) ?: continue
        if (path.startsWith("$DEFAULT_SOURCE_DIR/")) files += File(projectRoot, path)
    }
    return files.toList()
}

// One porcelain line -> project-relative path, or null when not a changed source file.
fun changedSourcePath(line: String): String? {
    if (line.length < 4) return null
    val status = line.take(2)
    if (status.none { it in CHANGE_STATUS }) return null
    val raw = line.drop(3)
    val renamed = status.any { it == 'R' || it == 'C' }
    val target = if (renamed) raw.substringAfterLast(" -> ", "").trim() else raw.trim()
    return if (target.endsWith(SOURCE_EXTENSION)) target else null
}

fun runGit(args: List<String>): String {
    val proc = ProcessBuilder(listOf("git") + args).start()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    if (proc.waitFor() != 0) throw IllegalStateException("git ${args[0]} failed: ${err.trim()}")
    return out
}

// `git diff --unified=0 <ref> -- paths` -> per-file set of added line numbers.
fun gitChangedLines(ref: String, relPaths: List<String>, cwd: File): Map<String, Set<Int>> {
    val args = listOf("diff", "--unified=0", ref, "--") + relPaths
    val proc = ProcessBuilder(listOf("git") + args).directory(cwd).start()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    if (proc.waitFor() != 0) throw IllegalStateException("git diff $ref failed: ${err.trim()}")
    return parseDiffLineMap(out)
}

fun parseDiffLineMap(raw: String): Map<String, Set<Int>> {
    val added = mutableMapOf<String, MutableSet<Int>>()
    var current: String? = null
    for (line in raw.lineSequence()) {
        if (line.startsWith("+++ ")) {
            current = diffHeaderPath(line)
        } else if (line.startsWith("@@ ")) {
            addHunk(added, current, line)
        }
    }
    return added
}

private fun diffHeaderPath(header: String): String? {
    val path = header.drop(4).trim()
    if (path == "/dev/null") return null
    return path.removePrefix("b/")
}

private fun addHunk(added: MutableMap<String, MutableSet<Int>>, file: String?, hunk: String) {
    if (file == null) return
    val match = Regex("""\+(\d+)(?:,(\d+))? @@""").find(hunk) ?: return
    val lines = added.getOrPut(file) { mutableSetOf() }
    val start = match.groupValues[1].toInt()
    val count = match.groupValues[2].ifEmpty { "1" }.toInt()
    repeat(count) { lines.add(start + it) }
}

fun diffIntersects(added: Map<String, Set<Int>>, rel: String, startLine: Int, endLine: Int): Boolean {
    val lines = added[rel] ?: return false
    return lines.any { it in startLine..endLine }
}

// Glob -> Regex, port of the crap4 family globToRegExp: `**/` crosses zero
// or more segments, `**` matches anything, `*` and `?` stay within one segment.
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) i = appendGlobChunk(glob, i, sb)
    sb.append('$')
    return Regex(sb.toString())
}

private fun appendGlobChunk(glob: String, i: Int, sb: StringBuilder): Int {
    val c = glob[i]
    val twoStar = c == '*' && glob.getOrNull(i + 1) == '*'
    if (twoStar) {
        if (glob.getOrNull(i + 2) == '/') {
            sb.append("(?:.*/)?")
            return i + 3
        }
        sb.append(".*")
        return i + 2
    }
    if (c == '*') {
        sb.append("[^/]*")
        return i + 1
    }
    if (c == '?') {
        sb.append("[^/]")
        return i + 1
    }
    sb.append(Regex.escape(c.toString()))
    return i + 1
}
