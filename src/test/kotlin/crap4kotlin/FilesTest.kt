package crap4kotlin

import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {
    @Test
    fun findSourceFilesWalksDefaultDir(@TempDir tmp: File) {
        val src = File(tmp, DEFAULT_SOURCE_DIR).apply { mkdirs() }
        File(src, "b.kt").writeText("class B")
        File(src, "sub").mkdirs()
        File(src, "sub/a.kt").writeText("class A")
        File(src, "notes.txt").writeText("skip")
        assertEquals(
            listOf("b.kt", "sub/a.kt").map { File(tmp, "$DEFAULT_SOURCE_DIR/$it").absolutePath },
            findSourceFiles(tmp).map { it.absolutePath },
        )
    }

    @Test
    fun expandPathsDedupesAndSorts(@TempDir tmp: File) {
        val src = File(tmp, "src").apply { mkdirs() }
        val a = File(src, "a.kt").apply { writeText("class A") }
        val dup = File(tmp, "src/a.kt")
        val out = expandPaths(listOf("src", "src/a.kt"), tmp)
        assertEquals(listOf(a.absolutePath), out.map { it.absolutePath })
        assertEquals(a, dup)
    }

    @Test
    fun missingPathExpandsToNothing(@TempDir tmp: File) {
        assertEquals(emptyList(), expandPaths(listOf("nowhere"), tmp))
    }

    @Test
    fun globCrossesSegmentsWithDoubleStar() {
        assertTrue(globToRegex("**/gen/**").matches("src/gen/x.kt"))
        assertTrue(globToRegex("**/gen/**").matches("gen/x.kt"))
        assertFalse(globToRegex("**/gen/**").matches("src/gen"))
    }

    @Test
    fun globStarStaysInSegment() {
        assertTrue(globToRegex("*.kt").matches("a.kt"))
        assertFalse(globToRegex("*.kt").matches("d/a.kt"))
        assertTrue(globToRegex("d/*.kt").matches("d/a.kt"))
    }

    @Test
    fun globEscapesRegexMetacharacters() {
        assertTrue(globToRegex("a+b.kt").matches("a+b.kt"))
        assertFalse(globToRegex("a+b.kt").matches("aab.kt"))
        assertTrue(globToRegex("x?y").matches("xay"))
        assertFalse(globToRegex("x?y").matches("x/y"))
    }

    @Test
    fun parsesDiffHunks() {
        val raw = """
            diff --git a/src/A.kt b/src/A.kt
            index 111..222 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -10,3 +10,2 @@ context
            -old1
            -old2
            -old3
            +new1
            +new2
            diff --git a/src/B.kt b/src/B.kt
            --- /dev/null
            +++ b/src/B.kt
            @@ -0,0 +1 @@
            +created
        """.trimIndent()
        val map = parseDiffLineMap(raw)
        assertEquals(setOf(10, 11), map["src/A.kt"])
        assertEquals(setOf(1), map["src/B.kt"])
    }

    @Test
    fun diffIntersectsRange() {
        val added = mapOf("a.kt" to setOf(5, 20))
        assertTrue(diffIntersects(added, "a.kt", 4, 6))
        assertTrue(diffIntersects(added, "a.kt", 20, 25))
        assertFalse(diffIntersects(added, "a.kt", 6, 19))
        assertFalse(diffIntersects(added, "missing.kt", 1, 2))
    }

    @Test
    fun changedSourcePathParsesStatuses() {
        assertEquals("src/main/kotlin/A.kt", changedSourcePath(" M src/main/kotlin/A.kt"))
        assertEquals("src/main/kotlin/New.kt", changedSourcePath("R  old.kt -> src/main/kotlin/New.kt"))
        assertEquals("src/main/kotlin/U.kt", changedSourcePath("?? src/main/kotlin/U.kt"))
        assertEquals(null, changedSourcePath("D  src/main/kotlin/Gone.kt"))
        assertEquals(null, changedSourcePath(" M README.md"))
        assertEquals(null, changedSourcePath(" M"))
        assertEquals(null, changedSourcePath("M"))
    }

    @Test
    fun toRelPathUsesForwardSlashes(@TempDir tmp: File) {
        val f = File(tmp, "a/b.kt")
        assertEquals("a/b.kt", toRelPath(f, tmp))
    }

    @Test
    fun changedFilesListsChangedKotlinSources(@TempDir tmp: File) {
        val src = File(tmp, "$DEFAULT_SOURCE_DIR").apply { mkdirs() }
        File(src, "A.kt").writeText("class A")
        git(tmp, "init")
        git(tmp, "add", ".")
        git(tmp, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "init")
        File(src, "B.kt").writeText("class B")
        File(src, "A.kt").writeText("class A2")
        File(src, "C.md").writeText("doc")
        val changed = changedFiles(tmp).map { it.name }
        assertEquals(listOf("A.kt", "B.kt"), changed)
    }

    private fun git(dir: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args.toList()).start()
        val code = proc.waitFor()
        if (code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed: " + proc.errorStream.bufferedReader().readText())
    }
}
