package ru.tapps.trackerblocker.stable

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianPluralTest {

    @Test
    fun singular() {
        assertEquals("трекер", russianPlural(1, "трекер", "трекера", "трекеров"))
        assertEquals("трекер", russianPlural(21, "трекер", "трекера", "трекеров"))
        assertEquals("трекер", russianPlural(101, "трекер", "трекера", "трекеров"))
    }

    @Test
    fun few() {
        assertEquals("трекера", russianPlural(2, "трекер", "трекера", "трекеров"))
        assertEquals("трекера", russianPlural(4, "трекер", "трекера", "трекеров"))
        assertEquals("трекера", russianPlural(22, "трекер", "трекера", "трекеров"))
    }

    @Test
    fun many() {
        assertEquals("трекеров", russianPlural(0, "трекер", "трекера", "трекеров"))
        assertEquals("трекеров", russianPlural(5, "трекер", "трекера", "трекеров"))
        assertEquals("трекеров", russianPlural(11, "трекер", "трекера", "трекеров"))
        assertEquals("трекеров", russianPlural(14, "трекер", "трекера", "трекеров"))
        assertEquals("трекеров", russianPlural(25, "трекер", "трекера", "трекеров"))
    }
}
