package crap4kotlin

// CRAP formula, port of the crap4 family:
//   CRAP = CC^2 * (1 - coverage)^3 + CC
// where CC >= 1 and coverage is a fraction in [0, 1]. Null coverage (no
// record) gives a null score, reported as N/A and excluded from the maximum.

fun crapScore(cc: Int, coverage: Double?): Double? {
    if (coverage == null) return null
    val uncovered = 1.0 - coverage
    return cc.toDouble() * cc * uncovered * uncovered * uncovered + cc
}
