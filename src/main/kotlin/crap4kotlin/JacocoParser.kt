package crap4kotlin

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// JaCoCo XML coverage parsing and per-method attribution.
// A `<method>` matches a source method when the class name and the `line`
// attribute are equal. LINE counter: covered / (covered + missed).
// Methods whose name contains `$` (compiler-generated) are skipped.

data class CoverageKey(val className: String, val line: Int)

class CoverageInputException(message: String) : RuntimeException(message)

class CoverageData(
    val methodCoverage: Map<CoverageKey, LongArray>, // key -> [missed, covered]
    val sourceFiles: Set<String>, // sourcefilename basenames present in the report
)

fun loadCoverage(path: File): CoverageData {
    if (!path.exists()) throw CoverageInputException("coverage file not found at ${path.absolutePath}")
    if (path.length() == 0L) throw CoverageInputException("coverage file is empty: ${path.absolutePath}")
    val data = try {
        parseCoverage(path)
    } catch (e: CoverageInputException) {
        throw e
    } catch (e: Exception) {
        throw CoverageInputException("failed to parse coverage file ${path.absolutePath}: ${e.message}")
    }
    if (data.sourceFiles.isEmpty()) {
        throw CoverageInputException("coverage file has no records for this project: ${path.absolutePath}")
    }
    return data
}

private fun parseCoverage(path: File): CoverageData {
    val doc = newDocumentBuilder().parse(path)
    val coverage = mutableMapOf<CoverageKey, LongArray>()
    val sourceFiles = mutableSetOf<String>()
    val classes = doc.getElementsByTagName("class")
    for (i in 0 until classes.length) {
        val cls = classes.item(i) as Element
        val className = cls.getAttribute("name")
        sourceFiles += cls.getAttribute("sourcefilename")
        collectMethods(cls, className, coverage)
    }
    return CoverageData(coverage, sourceFiles)
}

// external DTD/entities are never loaded: JaCoCo XML carries a DOCTYPE and
// the gate must not fetch it (also closes XXE)
private fun newDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance()
    for (feature in EXTERNAL_FEATURES) {
        try {
            factory.setFeature(feature, false)
        } catch (_: Exception) {
            // parser does not know the feature; JDK default stays safe
        }
    }
    return factory.newDocumentBuilder()
}

private val EXTERNAL_FEATURES = listOf(
    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    "http://xml.org/sax/features/external-general-entities",
    "http://xml.org/sax/features/external-parameter-entities",
)

private fun collectMethods(cls: Element, className: String, coverage: MutableMap<CoverageKey, LongArray>) {
    val methods = cls.getElementsByTagName("method")
    for (i in 0 until methods.length) {
        val m = methods.item(i) as Element
        val line = methodLine(m) ?: continue
        val counter = lineCounter(m) ?: continue
        addCounter(coverage, CoverageKey(className, line), counter)
    }
}

private fun methodLine(m: Element): Int? {
    if (m.getAttribute("name").contains("$")) return null
    return m.getAttribute("line").toIntOrNull()
}

private fun lineCounter(m: Element): LongArray? {
    val counters = m.getElementsByTagName("counter")
    for (i in 0 until counters.length) {
        val c = counters.item(i) as Element
        if (c.getAttribute("type") != "LINE") continue
        return longArrayOf(c.getAttribute("missed").toLongOrNull() ?: 0L, c.getAttribute("covered").toLongOrNull() ?: 0L)
    }
    return null
}

private fun addCounter(coverage: MutableMap<CoverageKey, LongArray>, key: CoverageKey, counter: LongArray) {
    val slot = coverage.getOrPut(key) { LongArray(2) }
    slot[0] += counter[0]
    slot[1] += counter[1]
}

// covered / (covered + missed) for a class+line key, or null without a record
fun coverageFor(coverage: CoverageData, key: CoverageKey): Double? {
    val slot = coverage.methodCoverage[key] ?: return null
    val total = slot[0] + slot[1]
    if (total == 0L) return null
    return slot[1].toDouble() / total
}
