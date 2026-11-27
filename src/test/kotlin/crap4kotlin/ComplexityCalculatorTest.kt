package crap4kotlin

import kotlin.test.Test
import kotlin.test.assertEquals

class ComplexityCalculatorTest {
    private fun cc(src: String): Int = countComplexity(tokenize(src.trimIndent()))

    @Test
    fun baseIsOne() {
        assertEquals(1, cc("fun f() {}"))
    }

    @Test
    fun ifCounts() {
        assertEquals(2, cc("fun f(x: Int) { if (x > 0) { } }"))
    }

    @Test
    fun elseIfCounts() {
        assertEquals(3, cc("fun f(x: Int) { if (x == 1) {} else if (x == 2) {} }"))
    }

    @Test
    fun elseDoesNotCount() {
        assertEquals(2, cc("fun f(x: Int) { if (x == 1) {} else {} }"))
    }

    @Test
    fun forCounts() {
        assertEquals(2, cc("fun f() { for (i in 1..3) { } }"))
    }

    @Test
    fun whileCounts() {
        assertEquals(2, cc("fun f(x: Int) { while (x > 0) { break } }"))
    }

    @Test
    fun doWhileCountsOnce() {
        assertEquals(2, cc("fun f(x: Int) { do { break } while (x > 0) }"))
    }

    @Test
    fun nestedDoWhileCountsEachHead() {
        assertEquals(
            3,
            cc("fun f(x: Int) { do { do { break } while (x > 1) } while (x > 0) }"),
        )
    }

    @Test
    fun catchCounts() {
        assertEquals(2, cc("fun f() { try { } catch (e: Exception) { } }"))
    }

    @Test
    fun finallyDoesNotCount() {
        assertEquals(2, cc("fun f() { try { } catch (e: Exception) { } finally { } }"))
    }

    @Test
    fun whenArmsCount() {
        assertEquals(4, cc("fun f(x: Int) { when (x) { 1 -> {} 2 -> {} else -> {} } }"))
    }

    @Test
    fun lambdaArrowInsideWhenDoesNotCount() {
        assertEquals(2, cc("fun f(x: Int) { when (x) { 1 -> listOf(1).map { it -> it } } }"))
    }

    @Test
    fun nestedWhenCountsItsOwnArms() {
        val src = "fun f(x: Int, y: Int) { when (x) { 1 -> when (y) { 1 -> {} else -> {} } else -> {} } }"
        assertEquals(5, cc(src))
    }

    @Test
    fun andCounts() {
        assertEquals(3, cc("fun f(a: Boolean, b: Boolean) { if (a && b) {} }"))
    }

    @Test
    fun orCounts() {
        assertEquals(3, cc("fun f(a: Boolean, b: Boolean) { if (a || b) {} }"))
    }

    @Test
    fun elvisCounts() {
        assertEquals(2, cc("fun f(x: Int?) = x ?: 0"))
    }

    @Test
    fun safeCallDoesNotCount() {
        assertEquals(1, cc("fun f(x: Int?) = x?.plus(1)"))
    }

    @Test
    fun ifInsideStringDoesNotCount() {
        assertEquals(1, cc("""fun f() { val s = "if && while ?:" }"""))
    }

    @Test
    fun ifInsideCommentDoesNotCount() {
        assertEquals(1, cc("fun f() { // if && while\n }"))
    }

    @Test
    fun blockCommentCountsLinesForReport() {
        val toks = tokenize("/* first\n second */ fun f() {}")
        assertEquals(6, toks.size)
        assertEquals(2, toks[0].line)
    }

    @Test
    fun backtickedKeywordDoesNotCount() {
        assertEquals(1, cc("fun f() { val x = `if` }"))
    }
}
