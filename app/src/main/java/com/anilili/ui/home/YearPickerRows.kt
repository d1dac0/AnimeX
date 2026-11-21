package com.anilili.ui.home

/**
 * The floor of the "Top scored by year" picker — the same 1900 the rail has always offered down
 * to, kept here so the list the picker offers and the list the rail used to compose in full stay
 * one list (issue #49).
 */
internal const val YEAR_PICKER_MIN_YEAR = 1900

/**
 * Every year the picker offers, newest first — the same list the picker used to compose all at
 * once the moment it opened (issue #49). Built here, not in the composable, so its shape is
 * checkable in the JVM suite.
 */
internal fun yearPickerYears(currentYear: Int): List<Int> =
    (currentYear downTo YEAR_PICKER_MIN_YEAR).toList()

/**
 * Where the lazy year list opens: on the selected year's row, so the row holding the current
 * value is composed when the dialog appears and its claim on the cursor (§31) can land (issue
 * #49). A year the list does not offer — the picker never selects one, but the rail's state
 * carries whatever it shows — opens at the head.
 */
internal fun yearPickerInitialIndex(years: List<Int>, selectedYear: Int): Int =
    years.indexOf(selectedYear).coerceAtLeast(0)

/** The content type every year row shares, so the lazy list reuses their composition (issue #49). */
internal const val YEAR_ROW_CONTENT_TYPE = "year-row"
