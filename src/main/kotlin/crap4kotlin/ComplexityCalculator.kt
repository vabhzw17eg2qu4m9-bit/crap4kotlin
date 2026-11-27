package crap4kotlin

// Cyclomatic complexity for Kotlin, port of the crap4 family rules table:
// base 1; +1 for each `if` (also `else if`), `for`, `while` (loop head),
// `do`, `catch`, each `->` arm of a `when` body (also `else ->`),
// `&&`, `||`, `?:`.
//
// Runs on a token slice (comments/strings already masked by the tokenizer).
// A `while` that terminates a `do` block does not count (the `do` already did).
// A `->` counts only as a `when` arm: `->` inside a lambda nested in a `when`
// body belongs to that lambda, so it is not counted.

fun countComplexity(tokens: List<Tok>): Int {
    val st = ComplexityState()
    for (tok in tokens) {
        if (st.braceStep(tok.text)) continue
        if (st.parenStep(tok.text)) continue
        if (st.keywordStep(tok.text)) continue
        st.operatorStep(tok.text)
    }
    return st.score
}

// Complexity of the token span [from, to] with the token ranges of nested
// child entries (local funs) removed.
fun complexityOf(tokens: List<Tok>, from: Int, to: Int, excluded: List<IntRange>): Int {
    val used = tokens.subList(from, to + 1)
        .withIndex()
        .filter { (idx, _) -> excluded.none { (from + idx) in it } }
        .map { it.value }
    return countComplexity(used)
}

internal class ComplexityState {
    var score = 1
    private val parens = ArrayDeque<Boolean>() // group opened directly after `when`?
    val braces = ArrayDeque<Boolean>() // block opened as a `when` body?
    private val doDepths = ArrayDeque<Int>() // brace depth enclosing each open `do`
    private var depth = 0
    private var prevWasWhen = false
    private var lastClosedWhenParen = false
    private var skipNextWhile = false

    fun braceStep(t: String): Boolean {
        if (t == "{") {
            braces.addLast(prevWasWhen || lastClosedWhenParen)
            prevWasWhen = false
            lastClosedWhenParen = false
            depth++
            return true
        }
        if (t == "}") {
            closeBrace()
            return true
        }
        return false
    }

    private fun closeBrace() {
        depth--
        if (doDepths.isNotEmpty() && depth == doDepths.last()) {
            doDepths.removeLast()
            skipNextWhile = true
        }
        braces.removeLastOrNull()
    }

    fun parenStep(t: String): Boolean {
        if (t == "(") {
            parens.addLast(prevWasWhen)
            prevWasWhen = false
            return true
        }
        if (t == ")") {
            lastClosedWhenParen = parens.removeLastOrNull() ?: false
            return true
        }
        return false
    }

    fun keywordStep(t: String): Boolean = when (t) {
        "when" -> {
            prevWasWhen = true
            true
        }
        "do" -> {
            doDepths.addLast(depth)
            score++
            true
        }
        "while" -> {
            if (!skipNextWhile) score++
            skipNextWhile = false
            true
        }
        else -> false
    }

    fun operatorStep(t: String) {
        when (t) {
            "if", "for", "catch", "&&", "||", "?:" -> score++
            "->" -> if (braces.lastOrNull() == true) score++
        }
    }
}
