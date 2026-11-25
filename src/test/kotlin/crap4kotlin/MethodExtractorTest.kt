package crap4kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MethodExtractorTest {
    private fun extract(src: String, file: String = "Test.kt") = extractMethods(src.trimIndent(), file)

    private fun names(src: String, file: String = "Test.kt"): List<String> =
        extract(src, file).methods.map { it.name }

    private fun classNames(src: String, file: String = "Test.kt"): List<String> =
        extract(src, file).methods.map { it.className }

    @Test
    fun topLevelFunGetsFacadeClass() {
        val r = extract("fun main(args: Array<String>) { }")
        assertEquals(listOf("main(args)"), r.methods.map { it.name })
        assertEquals(listOf("TestKt"), classNames("fun main(args: Array<String>) { }"))
    }

    @Test
    fun packagePrefixIncluded() {
        val cn = classNames("package a.b.c\n\nfun go() { }", "Go.kt")
        assertEquals(listOf("a.b.c/GoKt"), cn)
    }

    @Test
    fun memberFun() {
        val r = extract("class Foo { fun bar(a: Int, b: String) { } }")
        assertEquals(listOf("Foo.bar(a,b)"), r.methods.map { it.name })
        assertEquals(listOf("Foo"), r.methods.map { it.className })
    }

    @Test
    fun expressionBodyFun() {
        assertEquals(listOf("Foo.twice(x)"), names("class Foo { fun twice(x: Int) = x * 2 }"))
    }

    @Test
    fun secondaryConstructorIsInit() {
        assertEquals(listOf("Foo.init"), names("class Foo { constructor(a: Int) { } }"))
    }

    @Test
    fun secondaryConstructorWithDelegation() {
        val src = "class Foo { var saved = 0\n constructor(a: Int) : super(a) { saved = a } }"
        assertEquals(listOf("Foo.init"), names(src))
    }

    @Test
    fun initBlockIsInit() {
        assertEquals(listOf("Foo.init"), names("class Foo { init { } }"))
    }

    @Test
    fun primaryConstructorIsInitAtClassLine() {
        val r = extract("class Foo(val a: Int) {\n fun bar() { }\n}")
        assertEquals(listOf("Foo.init", "Foo.bar()"), r.methods.map { it.name })
        assertEquals(1, r.methods[0].startLine)
        assertEquals(1, r.methods[0].endLine)
        assertEquals(2, r.methods[1].startLine)
    }

    @Test
    fun primaryCtorParamsWithModifiersAndDefaults() {
        assertEquals(
            listOf("Foo.init", "Foo.z()"),
            names("class Foo(private val a: Int, val b: Int = 1) { fun z() { } }"),
        )
    }

    @Test
    fun propertyAccessors() {
        val src = "class Foo {\n var x: Int = 0\n get() = field\n set(v) { field = v }\n}"
        assertEquals(listOf("Foo.get", "Foo.set"), names(src))
    }

    @Test
    fun oneLineAccessor() {
        assertEquals(listOf("Foo.get"), names("class Foo { val y: Int get() = 2 }"))
    }

    @Test
    fun memberCallIsNotAccessor() {
        assertEquals(
            listOf("Foo.get()"),
            names("class Foo { val x = mapOf(1 to 2).get(1)\n fun get() = 3 }"),
        )
    }

    @Test
    fun localFunIsOwnPlainEntry() {
        assertEquals(listOf("Foo.outer()", "inner()"), names("class Foo { fun outer() { fun inner() { } } }"))
    }

    @Test
    fun localFunComplexityExcludedFromParent() {
        val r = extract(
            "class Foo {\n fun outer(x: Int) {\n fun inner(y: Int) { if (y > 0) { } }\n if (x > 0) { }\n }\n}",
        )
        val outer = r.methods.first { it.name == "Foo.outer(x)" }
        val inner = r.methods.first { it.name == "inner(y)" }
        assertEquals(2, complexityOfParent(outer, r))
        assertEquals(2, complexityOfParent(inner, r))
    }

    // mirrors the analyzer's parent-exclusion rule
    private fun complexityOfParent(m: MethodDescriptor, r: ExtractResult): Int {
        val excluded = r.methods
            .filter { it !== m && it.headerIdx >= m.headerIdx && it.spanEnd <= m.spanEnd }
            .map { it.headerIdx..it.spanEnd }
        return complexityOf(r.tokens, m.spanStart, m.spanEnd, excluded)
    }

    @Test
    fun nestedClassJoinsNameWithDollar() {
        val r = extract("class Outer { class Inner { fun deep() { } } }")
        assertEquals(listOf("Inner.deep()"), r.methods.map { it.name })
        assertEquals(listOf("Outer${'$'}Inner"), r.methods.map { it.className })
    }

    @Test
    fun companionObject() {
        val cn = classNames("class Foo { companion object { fun make() = 1 } }")
        assertEquals(listOf("Foo${'$'}Companion"), cn)
        assertEquals(listOf("Companion.make()"), names("class Foo { companion object { fun make() = 1 } }"))
    }

    @Test
    fun extensionFunInFacade() {
        assertEquals(listOf("shout()"), names("fun Int.shout() = toString()"))
    }

    @Test
    fun bodylessFunSkipped() {
        val src = "interface Api {\n fun ok(): Int\n fun bad(x: Int)\n}"
        assertEquals(emptyList(), names(src))
    }

    @Test
    fun interfaceDefaultMethod() {
        assertEquals(listOf("Api.ok()"), names("interface Api { fun ok() = 1 }"))
    }

    @Test
    fun stringsAndCommentsMasked() {
        val src = """
            // fun fake(a: Int) { if (a > 0) {} }
            class Foo {
                fun real(): String {
                    val s = "fun fake(x: Int)"
                    return s
                }
            }
        """.trimIndent()
        assertEquals(listOf("Foo.real()"), names(src))
    }

    @Test
    fun multilineSignatureLineNumbers() {
        val src = "class Foo {\n fun wide(\n a: Int,\n b: Int,\n ) {\n if (a > 0) { }\n }\n}"
        val r = extract(src)
        assertEquals(2, r.methods[0].startLine)
        assertEquals(7, r.methods[0].endLine)
        assertEquals(listOf("Foo.wide(a,b)"), r.methods.map { it.name })
    }

    @Test
    fun unnamedObjectDoesNotPushType() {
        assertEquals(listOf("run()"), names("val r = object : Runnable { fun run() { } }"))
    }

    @Test
    fun namedObjectIsType() {
        assertEquals(listOf("Default.make()"), names("object Default { fun make() = 1 }"))
    }

    @Test
    fun braceBodySpanCoversBody() {
        val r = extract("class Foo { fun f(x: Int) {\n if (x > 0) { }\n }\n }")
        val m = r.methods[0]
        val span = r.tokens.subList(m.spanStart, m.spanEnd + 1).map { it.text }
        assertTrue(span.contains("if"))
        assertTrue(!span.contains("fun"))
    }

    @Test
    fun enumClassMethods() {
        assertEquals(listOf("Color.pick()"), names("enum class Color { RED, GREEN\n fun pick() = RED }"))
    }

    @Test
    fun whenExpressionBodyEndsAfterBlock() {
        val src = "class Foo {\n fun pick(x: Int) = when (x) {\n 1 -> \"a\"\n else -> \"b\"\n }\n fun after() { }\n}"
        assertEquals(listOf("Foo.pick(x)", "Foo.after()"), names(src))
    }

    @Test
    fun chainedExpressionBody() {
        val src = "class Foo {\n val xs = listOf(1)\n fun first() = xs\n .map { it -> it }\n .first()\n fun last() { }\n}"
        assertEquals(listOf("Foo.first()", "Foo.last()"), names(src))
    }

    @Test
    fun suspendAndVarargParams() {
        assertEquals(listOf("Foo.go(xs)"), names("class Foo { suspend fun go(vararg xs: Int) { } }"))
    }

    @Test
    fun backtickedFunName() {
        assertEquals(listOf("Foo.check(x)"), names("class Foo { fun `check`(x: Int) { } }"))
    }
}

class MethodExtractorBranchTest {
    private fun extract(src: String, file: String = "Test.kt") = extractMethods(src.trimIndent(), file)

    @Test
    fun truncatedHeaderRecordsNothing() {
        // `findHeaderParen`/`findFunBody` hitting the end of the token stream
        assertEquals(emptyList(), extract("class A {\n fun f(").methods)
    }

    @Test
    fun functionTypeReturnWithExpressionBody() {
        // depth tracking across a parenthesised function type
        assertEquals(listOf("Foo.bind(x)"), extract("class Foo { fun bind(x: Int): (Int) -> Unit = { }").methods.map { it.name })
    }

    @Test
    fun parenthesisedDefaultSplitsCorrectly() {
        assertEquals(listOf("Foo.f(x,y)"), extract("class Foo { fun f(x: Int = g(1), y: Int = 2) { } }").methods.map { it.name })
    }

    @Test
    fun genericParamLabels() {
        assertEquals(listOf("Foo.f(xs)"), extract("class Foo { fun f(xs: List<String>) { } }").methods.map { it.name })
    }

    @Test
    fun callInsideBodyIsNotAccessor() {
        // `get` inside a method body must not become a property accessor
        assertEquals(
            listOf("Foo.f()"),
            extract("class Foo { fun f(): Int { return listOf(1).get(0) } }").methods.map { it.name },
        )
    }

    @Test
    fun genericUpperBoundClassHeader() {
        assertEquals(listOf("Box.get()"), extract("class Box<T : Comparable<T>>(val v: T) {\n fun get() = v\n}").methods.map { it.name })
    }
}

class TokenizerBranchTest {
    private fun cc(src: String): Int = countComplexity(tokenize(src.trimIndent()))

    @Test
    fun rawStringMasked() {
        assertEquals(1, cc("fun f() { val s = \"\"\"if && while ?:\"\"\" }"))
    }

    @Test
    fun rawStringWithQuotesInside() {
        assertEquals(1, cc("fun f() { val s = \"\"\"a \"b\" if\"\"\" }"))
    }

    @Test
    fun charLiteralMasked() {
        assertEquals(1, cc("fun f() { val c = 'x' }"))
    }

    @Test
    fun escapedCharLiteralMasked() {
        assertEquals(1, cc("fun f() { val c = '\\n' }"))
    }

    @Test
    fun nestedBlockCommentMasked() {
        assertEquals(1, cc("/* outer /* inner if */ still && */ fun f() {}"))
    }

    @Test
    fun unclosedBraceFallsBackToBraceLine() {
        // closeOf returns -1: the method is still recorded, end line clamps
        val r = extractMethods("class A {\n fun f() {\n if (x) { }\n", "A.kt")
        assertEquals(listOf("A.f()"), r.methods.map { it.name })
        assertTrue(r.methods[0].endLine >= r.methods[0].startLine)
    }

    @Test
    fun funInterfaceHeaderHasNoParen() {
        assertEquals(emptyList(), extractMethods("fun interface Api {\n fun ok(): Int\n}", "Api.kt").methods)
    }

    private fun extractMethods(src: String, file: String) = crap4kotlin.extractMethods(src.trimIndent(), file)
}
