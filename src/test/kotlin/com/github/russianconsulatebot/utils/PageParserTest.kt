package com.github.russianconsulatebot.utils

import com.github.russianconsulatebot.exceptions.PageParseSessionException
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

class PageParserTest {

    @Test
    fun parseCalendarWidget() {
        val calendarStr = this::class.java.getResourceAsStream("/calendar_with_no_slots.html")!!
        val calendar = Jsoup.parse(calendarStr, null, "")

        val calendarInfo = PageParser.parseCalendar(calendar)

        assertEquals(2024, calendarInfo.year)
        assertEquals(5, calendarInfo.month)
        assertEquals(31, calendarInfo.days.size)
        assertEquals(5, calendarInfo.availableDays.size)
        assertEquals(LocalDate.parse("2024-05-10"), calendarInfo.selectedDay.date)
        assertEquals(
            listOf("2024-05-07", "2024-05-08", "2024-05-13", "2024-05-14", "2024-05-15"),
            calendarInfo.availableDays.map { it.date.toString() }
        )
        assertEquals(listOf("8893", "8894", "8899", "8900", "8901"), calendarInfo.availableDays.map { it.eventArgument })
    }

    @Test
    fun parseOneSlot() {
        val calendarStr = this::class.java.getResourceAsStream("/calendar_with_one_slot.html")!!
        val calendar = Jsoup.parse(calendarStr, null, "")

        val slots = PageParser.parseSlots(calendar)

        val expectedSlots = listOf(
            Slot(LocalDateTime.parse("2024-05-07T12:25"), "Окно 3")
        )
        assertEquals(expectedSlots, slots)
    }

    @Test
    fun parseSeveralSlots() {
        val calendarStr = this::class.java.getResourceAsStream("/calendar_with_several_slots.html")!!
        val calendar = Jsoup.parse(calendarStr, null, "")

        val slots = PageParser.parseSlots(calendar)

        val expectedSlots = listOf(
            Slot(LocalDateTime.parse("2024-05-08T12:30"), "Окно 2"),
            Slot(LocalDateTime.parse("2024-05-08T12:35"), "Окно 2"),
            Slot(LocalDateTime.parse("2024-05-08T12:40"), "Окно 2"),
            Slot(LocalDateTime.parse("2024-07-01T09:00"), "Окно 3"),
            Slot(LocalDateTime.parse("2024-05-08T12:20"), "Окно 3"),
            Slot(LocalDateTime.parse("2024-05-08T12:25"), "Окно 3"),
            Slot(LocalDateTime.parse("2024-05-08T12:30"), "Окно 3"),
        )
        assertEquals(expectedSlots, slots)
    }

    @Test
    fun parseNoSlots() {
        val calendarStr = this::class.java.getResourceAsStream("/calendar_with_no_slots.html")!!
        val calendar = Jsoup.parse(calendarStr, null, "")

        assertThrows<PageParseSessionException> {
            PageParser.parseSlots(calendar)
        }
    }
}