package crap4kotlin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JacocoParserTest {
    private fun resource(name: String): File {
        val url = javaClass.classLoader.getResource(name) ?: throw IllegalStateException("missing resource $name")
        return File(url.toURI())
    }

    @Test
    fun parsesSample() {
        val data = loadCoverage(resource("jacoco/sample.xml"))
        assertEquals(0.75, coverageFor(data, CoverageKey("crap4kotlin/Foo", 10)))
        assertEquals(0.0, coverageFor(data, CoverageKey("crap4kotlin/Foo", 20)))
        assertEquals(1.0, coverageFor(data, CoverageKey("crap4kotlin/Foo${'$'}Companion", 30)))
        assertNull(coverageFor(data, CoverageKey("crap4kotlin/Foo", 99)))
        assertTrue(data.sourceFiles.contains("Foo.kt"))
    }

    @Test
    fun compilerGeneratedMethodsAreSkipped() {
        val data = loadCoverage(resource("jacoco/sample.xml"))
        assertNull(coverageFor(data, CoverageKey("crap4kotlin/Foo", 15)))
    }

    @Test
    fun sameKeyCountersAreSummed() {
        val xml = """
            <report>
              <class name="c/Foo" sourcefilename="Foo.kt">
                <method name="bar" line="5"><counter type="LINE" missed="1" covered="1"/></method>
              </class>
              <class name="c/Foo" sourcefilename="Foo.kt">
                <method name="bar" line="5"><counter type="LINE" missed="0" covered="2"/></method>
              </class>
            </report>
        """.trimIndent()
        val f = File.createTempFile("sum", ".xml").apply { writeText(xml) }
        f.deleteOnExit()
        assertEquals(0.75, coverageFor(loadCoverage(f), CoverageKey("c/Foo", 5)))
    }

    @Test
    fun zeroTotalCounterIsNotCoverable() {
        val data = CoverageData(mapOf(CoverageKey("c/Foo", 5) to longArrayOf(0, 0)), setOf("Foo.kt"))
        assertNull(coverageFor(data, CoverageKey("c/Foo", 5)))
    }

    @Test
    fun missingFileFails() {
        val e = assertFailsWith<CoverageInputException> { loadCoverage(File("/nonexistent/report.xml")) }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun emptyFileFails() {
        val empty = File.createTempFile("empty", ".xml").apply { writeText("") }
        empty.deleteOnExit()
        val e = assertFailsWith<CoverageInputException> { loadCoverage(empty) }
        assertTrue(e.message!!.contains("empty"))
    }

    @Test
    fun malformedXmlFails() {
        val bad = File.createTempFile("bad", ".xml").apply { writeText("<report><unclosed>") }
        bad.deleteOnExit()
        assertFailsWith<CoverageInputException> { loadCoverage(bad) }
    }

    @Test
    fun reportWithoutClassesFails() {
        val empty = File.createTempFile("noclass", ".xml").apply { writeText("<report></report>") }
        empty.deleteOnExit()
        val e = assertFailsWith<CoverageInputException> { loadCoverage(empty) }
        assertTrue(e.message!!.contains("no records"))
    }
}
