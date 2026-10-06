package com.denebapps.patrimonio.domain.model

/** Own value type (kotlinx-datetime 0.6.x has no `YearMonth`) that keeps the DB primary key
 *  ("YYYY-MM") stable and version-independent. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    init {
        require(month in 1..12) { "month must be in 1..12, was $month" }
    }

    /** Stable "YYYY-MM" string used as the DB primary key. */
    fun asKey(): String {
        val y = year.toString().padStart(4, '0')
        val m = month.toString().padStart(2, '0')
        return "$y-$m"
    }

    override fun compareTo(other: YearMonth): Int = ordinal().compareTo(other.ordinal())

    private fun ordinal(): Long = year.toLong() * 12L + (month - 1)

    companion object {
        fun fromKey(key: String): YearMonth {
            val (y, m) = key.split("-").map { it.toInt() }
            return YearMonth(y, m)
        }
    }
}
