package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.YearMonth

/** Capitalized Spanish month names, index 0 = January. */
internal val MONTHS_ES = listOf(
    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre",
)

/** "{Spanish month} {year}" (e.g. "Mayo 2026"). */
fun monthLabelEs(ym: YearMonth): String = "${MONTHS_ES[ym.month - 1]} ${ym.year}"
