package com.anilili.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The year picker's rows and where the lazy list opens (issue #49). The list opens on the row
 * holding the current value, because that is the row that has to be composed for its claim on the
 * cursor to land.
 */
class YearPickerRowsTest {
    @Test
    fun `the picker offers every year from the current one down to 1900, newest first`() {
        val years = yearPickerYears(2026)

        assertEquals(2026, years.first())
        assertEquals(1900, years.last())
        assertEquals(127, years.size)
        assertEquals((2026 downTo 1900).toList(), years)
    }

    @Test
    fun `the list opens on the selected year's row`() {
        val years = yearPickerYears(2026)

        assertEquals(0, yearPickerInitialIndex(years, 2026))
        assertEquals(6, yearPickerInitialIndex(years, 2020))
        assertEquals(years.lastIndex, yearPickerInitialIndex(years, 1900))
    }

    @Test
    fun `a year the picker does not offer opens at the head`() {
        val years = yearPickerYears(2026)

        assertEquals(0, yearPickerInitialIndex(years, 1899))
        assertEquals(0, yearPickerInitialIndex(years, 2027))
    }
}
