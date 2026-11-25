package crap4kotlin

// Kotlin method extraction with an own scanner (comments, strings, char
// literals and back-tick names are masked before matching), port of the
// crap4 family method extractor.
//
// Methods: every `fun` with a body, primary and secondary constructors,
// `init` blocks (all named `Type.init`), property accessors `get`/`set`
// with a body (`Type.get` / `Type.set`). Lambdas count toward the enclosing
// method (they are inside the body span). A local `fun` is its own entry.
//
// ponytail ceilings of the scanner (a scanner, not a compiler):
// - an expression body ends at the first line break where the bracket depth
//   is zero and the expression does not continue (no leading `.`, no
//   trailing operator); exotic formatting can end it one line early, which
//   only affects diff-range selection, never the CRAP score
// - local classes keep the enclosing class name in the coverage key, so
//   their methods may end up N/A rows
// - a `by lazy { ... }` delegation before the class body brace can close
//   the header scan early.

data class Tok(val text: String, val line: Int)

data class MethodDescriptor(
    val name: String,
    val className: String,
    val startLine: Int,
    val endLine: Int,
    val covLine: Int, // JaCoCo reports the first body line, not the declaration
    val headerIdx: Int, // first token (fun/class/get); exclusion range for parents
    val spanStart: Int, // complexity span (the body)
    val spanEnd: Int,
)

data class ExtractResult(val methods: List<MethodDescriptor>, val tokens: List<Tok>)

// ---------------------------------------------------------------- tokenizer

private const val PUNCT = "{}()[];,.:"
private const val OPERATORS = "+-*/%=<>!&|:^~?"

fun tokenize(source: String): List<Tok> {
    val out = mutableListOf<Tok>()
    var i = 0
    var line = 1
    while (i < source.length) {
        val step = tokenStep(source, i, line, out)
        i = step.next
        line = step.line
    }
    return out
}

private fun tokenStep(src: String, i: Int, line: Int, out: MutableList<Tok>): Step {
    val c = src[i]
    if (c == '\n' || c.isWhitespace()) return skipSpace(src, i, line)
    if (c == '/') return skipComment(src, i, line, out)
    if (c == '"' || c == '\'') return skipQuoted(src, i, line)
    if (c == '`') return emitBacktick(src, i, line, out)
    return emitWordOrSymbol(src, i, line, out)
}

internal data class Step(val next: Int, val line: Int)

private fun skipSpace(src: String, i: Int, line: Int): Step {
    var j = i
    var l = line
    while (j < src.length && src[j].isWhitespace()) {
        if (src[j] == '\n') l++
        j++
    }
    return Step(j, l)
}

private fun skipComment(src: String, i: Int, line: Int, out: MutableList<Tok>): Step {
    if (src.getOrNull(i + 1) == '/') return skipLineComment(src, i, line)
    if (src.getOrNull(i + 1) == '*') return skipBlockComment(src, i, line)
    return emitSymbol(src, i, line, out)
}

private fun skipLineComment(src: String, i: Int, line: Int): Step {
    var j = i + 2
    while (j < src.length && src[j] != '\n') j++
    return Step(j, line)
}

private fun skipBlockComment(src: String, i: Int, line: Int): Step {
    var j = i + 2
    var l = line
    var depth = 1
    while (j < src.length && depth > 0) {
        if (src.startsWith("/*", j)) {
            depth++
            j += 2
        } else if (src.startsWith("*/", j)) {
            depth--
            j += 2
        } else {
            if (src[j] == '\n') l++
            j++
        }
    }
    return Step(j, l)
}

private fun skipQuoted(src: String, i: Int, line: Int): Step {
    if (src.startsWith("\"\"\"", i)) return skipRawString(src, i, line)
    if (src[i] == '"') return skipString(src, i, line)
    return skipCharLiteral(src, i, line)
}

private fun skipString(src: String, i: Int, line: Int): Step {
    var j = i + 1
    var l = line
    while (j < src.length && src[j] != '"') {
        if (src[j] == '\\') j++
        else if (src[j] == '\n') l++
        j++
    }
    return Step(j + 1, l)
}

private fun skipRawString(src: String, i: Int, line: Int): Step {
    var j = i + 3
    var l = line
    while (j < src.length && !src.startsWith("\"\"\"", j)) {
        if (src[j] == '\n') l++
        j++
    }
    return Step(j + 3, l)
}

private fun skipCharLiteral(src: String, i: Int, line: Int): Step {
    var j = i + 1
    if (src.getOrNull(j) == '\\') j++
    return Step(j + 2, line)
}

private fun emitBacktick(src: String, i: Int, line: Int, out: MutableList<Tok>): Step {
    val end = src.indexOf('`', i + 1).let { if (it < 0) src.length else it + 1 }
    out += Tok(src.substring(i, end), line)
    return Step(end, line)
}

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '@'

private fun emitWordOrSymbol(src: String, i: Int, line: Int, out: MutableList<Tok>): Step {
    if (isWordChar(src[i])) return emitWord(src, i, line, out)
    return emitSymbol(src, i, line, out)
}

private fun emitWord(src: String, i: Int, line: Int, out: MutableList<Tok>): Step {
    var j = i
    while (j < src.length && isWordChar(src[j])) j++
    out += Tok(src.substring(i, j), line)
    return Step(j, line)
}

private fun emitSymbol(src: String, i: Int, line: Int, out: MutableList<Tok>): Step {
    val c = src[i]
    if (c in PUNCT) {
        out += Tok(c.toString(), line)
        return Step(i + 1, line)
    }
    var j = i
    while (j < src.length && src[j] in OPERATORS) j++
    if (j == i) j = i + 1 // any stray character becomes a single token
    out += Tok(src.substring(i, j), line)
    return Step(j, line)
}

// ---------------------------------------------------------------- extractor

fun extractMethods(source: String, fileName: String): ExtractResult {
    val tokens = tokenize(source)
    val matcher = BraceMatcher(tokens)
    val extractor = Extractor(tokens, fileName, matcher)
    extractor.run()
    return ExtractResult(extractor.methods, tokens)
}

// open brace index <-> close brace index (-1 when unbalanced)
internal class BraceMatcher(private val tokens: List<Tok>) {
    private val close = IntArray(tokens.size) { -1 }
    private val open = IntArray(tokens.size) { -1 }

    init {
        val stack = ArrayDeque<Int>()
        for (i in tokens.indices) {
            when (tokens[i].text) {
                "{" -> stack.addLast(i)
                "}" -> if (stack.isNotEmpty()) {
                    val o = stack.removeLast()
                    close[o] = i
                    open[i] = o
                }
            }
        }
    }

    fun closeOf(openIdx: Int): Int = close.getOrElse(openIdx) { -1 }

    fun openOf(closeIdx: Int): Int = open.getOrElse(closeIdx) { -1 }
}

private class TypeFrame(val name: String, val bodyDepth: Int)

private class Extractor(
    private val tokens: List<Tok>,
    private val fileName: String,
    private val matcher: BraceMatcher,
) {
    val methods = mutableListOf<MethodDescriptor>()
    private val pkgPrefix: String
    private val typeStack = ArrayDeque<TypeFrame>()
    private val methodStack = ArrayDeque<String>()
    private val typeBodyOpen = mutableMapOf<Int, String>() // brace idx -> type name
    private val methodBodyName = mutableMapOf<Int, String>() // brace idx -> method name
    private var braceDepth = 0

    init {
        pkgPrefix = packagePrefix()
    }

    fun run() {
        var i = 0
        while (i < tokens.size) {
            i = step(i)
        }
    }

    private fun step(i: Int): Int {
        val t = tokens[i].text
        val decl = declStep(i, t)
        if (decl > i) return decl
        return braceStep(i, t)
    }

    private fun declStep(i: Int, t: String): Int = when (t) {
        "class", "interface", "enum" -> classLike(i)
        "object" -> objectDecl(i)
        "fun" -> funDecl(i)
        else -> declStep2(i, t)
    }

    private fun declStep2(i: Int, t: String): Int = when (t) {
        "constructor" -> secondaryCtor(i)
        "init" -> initBlock(i)
        "get", "set" -> accessor(i)
        "package", "import" -> skipDotted(i + 1)
        else -> i
    }

    private fun braceStep(i: Int, t: String): Int {
        if (t == "{") {
            braceDepth++
            typeBodyOpen[i]?.let { typeStack.addLast(TypeFrame(it, braceDepth)) }
            return i + 1
        }
        if (t == "}") {
            val open = matcher.openOf(i)
            if (open >= 0 && methodBodyName.containsKey(open)) {
                // a recorded body's open brace was never counted: pop the
                // frame and leave braceDepth untouched
                methodStack.removeLast()
            } else {
                braceDepth--
                while (typeStack.isNotEmpty() && typeStack.last().bodyDepth > braceDepth) typeStack.removeLast()
            }
            return i + 1
        }
        return i + 1
    }

    // ------------------------------------------------------------- naming

    private fun classPath(extra: String?): String {
        val parts = typeStack.map { it.name } + listOfNotNull(extra)
        return pkgPrefix + parts.joinToString("$")
    }

    private fun facadePath(): String = pkgPrefix + fileName.removeSuffix(".kt") + "Kt"

    private fun currentTypeName(): String? = typeStack.lastOrNull()?.name

    private fun atMemberDepth(): Boolean =
        typeStack.isNotEmpty() && typeStack.last().bodyDepth == braceDepth

    // ------------------------------------------------------------- handlers

    private fun skipDotted(i: Int): Int {
        var j = i
        while (j < tokens.size && (isWordTok(j) || tokens[j].text == ".")) {
            val t = tokens[j].text
            if (t in PKG_STOPPERS || t == "import") break
            j++
        }
        return j
    }

    private fun classLike(i: Int): Int {
        val nameIdx = if (tokens[i].text == "enum" && wordAt(i + 1) == "class") i + 2 else i + 1
        val name = wordAt(nameIdx) ?: return i + 1
        var j = nameIdx + 1
        if (tokens.getOrNull(j)?.text == "(") {
            val close = closeParen(j)
            val covLine = if (close > j + 1) tokens[j + 1].line else tokens[nameIdx].line
            recordMethod("$name.init", classPath(name), tokens[nameIdx].line, tokens[close].line, covLine, j, nameIdx)
            j = close + 1
        }
        registerTypeBody(name, j)
        return i + 1
    }

    private fun objectDecl(i: Int): Int {
        val companion = tokens.getOrNull(i - 1)?.text == "companion"
        val name = wordAt(i + 1) ?: if (companion) "Companion" else return i + 1
        registerTypeBody(name, if (wordAt(i + 1) != null) i + 2 else i + 1)
        return i + 1
    }

    private fun initBlock(i: Int): Int {
        val owner = currentTypeName() ?: return i + 1
        if (tokens.getOrNull(i + 1)?.text != "{" || tokens.getOrNull(i - 1)?.text == ".") return i + 1
        return recordBraceMethod("$owner.init", classPath(null), i, i + 1)
    }

    private fun secondaryCtor(i: Int): Int {
        if (!atMemberDepth() || tokens.getOrNull(i - 1)?.text == "." || tokens.getOrNull(i + 1)?.text != "(") return i + 1
        val close = closeParen(i + 1)
        val body = findBodyBrace(close + 1) ?: return close + 1
        val owner = currentTypeName() ?: return close + 1
        return recordBraceMethod("$owner.init", classPath(null), i, body)
    }

    private fun accessor(i: Int): Int {
        if (!atAccessorSpot(i)) return i + 1
        val close = closeParen(i + 1)
        val owner = currentTypeName()
        val label = tokens[i].text
        val name = if (owner == null) label else "$owner.$label"
        val className = if (owner == null) facadePath() else classPath(null)
        return recordBodyMethod(name, className, i, close + 1)
    }

    private fun funDecl(i: Int): Int {
        val paren = findHeaderParen(i + 1) ?: return i + 1
        val name = tokens[paren - 1].text.removeSurrounding("`")
        val close = closeParen(paren)
        val prefix = if (methodStack.isEmpty()) ownerPrefix() else ""
        return recordBodyMethod(prefix + name + labels(paren, close), coverageClass(), i, close + 1)
    }

    // finds the body from `scanFrom` and records the method: brace body or
    // expression body; returns the index after the recorded body
    private fun recordBodyMethod(name: String, className: String, headerIdx: Int, scanFrom: Int): Int {
        val body = findFunBody(scanFrom) ?: return scanFrom
        if (body.first) {
            return recordBraceMethod(name, className, headerIdx, body.second)
        }
        val end = exprBodyEnd(body.second + 1)
        val covLine = tokens.getOrNull(body.second + 1)?.line ?: startLineOf(headerIdx)
        recordMethod(name, className, tokens[headerIdx].line, tokens[end].line, covLine, body.second + 1, headerIdx)
        return end + 1
    }

    private fun ownerPrefix(): String {
        val owner = currentTypeName() ?: return ""
        return "$owner."
    }

    // coverage class for a fun: any type context -> that type, else file facade
    private fun coverageClass(): String {
        if (typeStack.isNotEmpty()) return classPath(null)
        return facadePath()
    }

    // ------------------------------------------------------------- helpers

    // brace-body method: span = open..close brace. Registers the body frame
    // immediately (the loop never visits the open brace of a recorded body)
    // and returns the index after the OPEN brace: the loop keeps walking the
    // interior so local funs inside the body are found.
    private fun recordBraceMethod(name: String, className: String, headerIdx: Int, openIdx: Int): Int {
        val close = matcher.closeOf(openIdx)
        val endLine = if (close >= 0) tokens[close].line else tokens.lastOrNull()?.line ?: startLineOf(headerIdx)
        val firstInterior = openIdx + 1
        val covLine = if (close >= 0 && firstInterior < close) tokens[firstInterior].line else tokens[openIdx].line
        recordMethod(name, className, tokens[headerIdx].line, endLine, covLine, openIdx, headerIdx)
        if (close >= 0) {
            methodBodyName[openIdx] = name
            methodStack.addLast(name)
        }
        return openIdx + 1
    }

    private fun startLineOf(idx: Int): Int = tokens.getOrNull(idx)?.line ?: 0

    private fun recordMethod(
        name: String,
        className: String,
        startLine: Int,
        endLine: Int,
        covLine: Int,
        spanStart: Int,
        headerIdx: Int,
    ) {
        val safeEnd = maxOf(endLine, startLine)
        methods += MethodDescriptor(name, className, startLine, safeEnd, covLine, headerIdx, spanStart, spanEndOf(spanStart, safeEnd))
    }

    // last token index at or before endLine, starting from spanStart
    private fun spanEndOf(spanStart: Int, endLine: Int): Int {
        var j = spanStart
        while (j + 1 < tokens.size && tokens[j + 1].line <= endLine) j++
        return j
    }

    private fun registerTypeBody(name: String, from: Int) {
        val body = findBodyBrace(from) ?: return
        typeBodyOpen[body] = name
    }

    private fun isWordTok(i: Int): Boolean {
        val t = tokens.getOrNull(i) ?: return false
        return isWordText(t.text)
    }

    private fun isWordText(text: String): Boolean {
        val c = text.firstOrNull() ?: return false
        return c.isLetterOrDigit() || c == '_' || text.startsWith("`")
    }

    private fun wordAt(i: Int): String? {
        val t = tokens.getOrNull(i) ?: return null
        return if (isWordTok(i) && !t.text.startsWith("@")) t.text else null
    }

    private fun closeParen(open: Int): Int {
        var depth = 0
        for (j in open until tokens.size) {
            if (tokens[j].text == "(") depth++
            if (tokens[j].text == ")") {
                depth--
                if (depth == 0) return j
            }
        }
        return tokens.size - 1
    }

    // first `(` after a `fun` keyword (the parameter list)
    private fun findHeaderParen(from: Int): Int? {
        for (j in from until tokens.size) {
            val t = tokens[j].text
            if (t == "(") return j
            if (t == "{" || t == "}" || t == "=" || t == ";") return null
        }
        return null
    }

    // Pair(isBraceBody, index): the body `{`, or the `=` of an expression body
    private fun findFunBody(from: Int): Pair<Boolean, Int>? {
        var depth = 0
        for (j in from until tokens.size) {
            val t = tokens[j].text
            if (t == "(") depth++
            if (t == ")") depth--
            if (depth == 0) {
                if (t == "{") return Pair(true, j)
                if (t == "=") return Pair(false, j)
                if (isDeclStopper(t)) return null
            }
        }
        return null
    }

    // `{` of a declaration body: balanced parens skipped, decl stoppers end the scan
    private fun findBodyBrace(from: Int): Int? {
        var depth = 0
        for (j in from until tokens.size) {
            val t = tokens[j].text
            if (t == "(") depth++
            if (t == ")") depth--
            if (depth == 0) {
                if (t == "{") return j
                if (isDeclStopper(t)) return null
            }
        }
        return null
    }

    private fun isDeclStopper(t: String): Boolean = t in DECL_STOPPERS

    // expression body end: last token before a non-continuing line change
    private fun exprBodyEnd(from: Int): Int {
        var depth = 0
        var end = minOf(from, tokens.size - 1)
        var j = from
        while (j < tokens.size) {
            val t = tokens[j]
            depth += depthDelta(t.text)
            if (depth < 0) return end
            val next = tokens.getOrNull(j + 1)
            if (depth == 0 && (next == null || (next.line > t.line && !isContinuation(t, next)))) return j
            end = j
            j++
        }
        return end
    }

    private fun depthDelta(text: String): Int = when (text) {
        "(", "[", "{" -> 1
        ")", "]", "}" -> -1
        else -> 0
    }

    private fun isContinuation(prev: Tok, next: Tok): Boolean {
        if (next.text.startsWith(".") || next.text == "?.") return true
        return prev.text.last() in OPERATORS || prev.text == "->"
    }

    // parameter labels, e.g. "(a,b)" — the first name of each top-level segment
    private fun labels(open: Int, close: Int): String {
        if (close - open < 2) return "()"
        return "(${paramNames(open, close).joinToString(",")})"
    }

    private fun paramNames(open: Int, close: Int): List<String> {
        val names = mutableListOf<String>()
        var expectName = true
        val nest = Nesting()
        for (j in open + 1 until close) {
            val t = tokens[j]
            if (nest.step(t.text) || nest.depth > 0) continue
            if (t.text == ",") {
                expectName = true
                continue
            }
            if (expectName && isParamName(t)) {
                names += t.text.removeSurrounding("`")
                expectName = false
            }
        }
        return names
    }

    // paren and angle nesting (angle pairs balance in default-value expressions)
    private class Nesting {
        var depth = 0
        private var angle = 0

        fun step(text: String): Boolean {
            when (text) {
                "(" -> depth++
                ")" -> depth--
                "<" -> angle++
                ">" -> if (angle > 0) angle--
                else -> return false
            }
            return true
        }
    }

    private fun isParamName(t: Tok): Boolean = isWordText(t.text) && t.text !in PARAM_NOISE

    // accessor spot: member level (or top-level property), not an expression
    // member call (`.`), followed by `(`
    private fun atAccessorSpot(i: Int): Boolean {
        if (tokens.getOrNull(i + 1)?.text != "(") return false
        if (typeStack.isNotEmpty() && !atMemberDepth()) return false
        val prev = tokens.getOrNull(i - 1)?.text
        if (prev != null && tokens[i].line == tokens[i - 1].line && prev in NON_ACCESSOR_PREV) return false
        return true
    }

    private fun packagePrefix(): String {
        if (tokens.firstOrNull()?.text != "package") return ""
        val parts = packageParts()
        if (parts.isEmpty()) return ""
        return parts.joinToString(".") + "/"
    }

    private fun packageParts(): List<String> {
        val parts = mutableListOf<String>()
        var j = 1
        while (j < tokens.size && (isWordTok(j) || tokens[j].text == ".")) {
            val t = tokens[j].text
            if (t in PKG_STOPPERS) break
            if (t != ".") parts += t
            j++
        }
        return parts
    }

    companion object {
        private val DECL_STOPPERS = setOf(
            "}", ";", "fun", "val", "var", "class", "interface", "object", "init", "constructor", "get", "set", "typealias",
        )
        private val PKG_STOPPERS = setOf(
            "class", "interface", "fun", "object", "enum", "val", "var", "typealias", "import",
            "data", "sealed", "abstract", "open", "annotation", "expect", "actual", "internal",
            "private", "public", "protected", "companion", "const", "inline", "value", "funinterface",
        )
        private val PARAM_NOISE = setOf(
            "vararg", "noinline", "crossinline", "suspend", "val", "var", "private", "public", "internal", "protected",
        )
        private val NON_ACCESSOR_PREV = setOf(".", "=", "(", ",", "?:", "&&", "||", "->", "{")
    }
}
