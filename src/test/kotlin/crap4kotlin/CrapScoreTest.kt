package crap4kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrapScoreTest {
    @Test
    fun nullCoverageGivesNull() {
        assertNull(crapScore(5, null))
    }

    @Test
    fun fullCoverageGivesCc() {
        assertEquals(3.0, crapScore(3, 1.0))
    }

    @Test
    fun zeroCoverageGivesCcSquaredPlusCc() {
        assertEquals(2.0, crapScore(1, 0.0))
        assertEquals(90.0, crapScore(9, 0.0))
    }

    @Test
    fun formulaIsCcSquaredTimesUncoveredCubedPlusCc() {
        // 2^2 * (1 - 0.5)^3 + 2 = 4 * 0.125 + 2
        assertEquals(2.5, crapScore(2, 0.5))
        // 6^2 * 0.1^3 + 6 = 0.036 + 6
        assertEquals(6.036, crapScore(6, 0.9))
    }
}
