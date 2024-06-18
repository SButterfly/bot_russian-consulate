package com.github.russianconsulatebot.utils

import com.github.russianconsulatebot.exceptions.PageParseSessionException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object PageParser {
    /**
     * Parses calendar page to [CalendarInfo]
     *
     * @throws PageParseSessionException is failed to parse a page
     */
    fun parseCalendar(page: Document): CalendarInfo {
        val calendarTable = page.selectFirst("#ctl00_MainContent_Calendar")
            ?: throw PageParseSessionException("Calendar widget is not on the page")

        // get rows of a table
        val rowElements = calendarTable.selectXpath("./tbody/tr")

        // row #0 is month + year
        val mothYearRow = rowElements[0]
        val (month, year) = extractMonthAndYear(mothYearRow.selectXpath(".//td[@align='center']").text())
        // row #1 is for days of week
        // row #2..n is for days of month
        val days = mutableListOf<CalendarDay>()
        for (i: Int in 2..<rowElements.size) {
            val dayElements = rowElements[i].selectXpath("./td")
            for (dayElement: Element in dayElements) {
                // get the child link of a day
                val linkElement = dayElement.selectXpath("./a")[0]

                // get href and parse json to get id
                val jsScript = linkElement.attr("href")
                val (target, argument) = extractJsKeyValue(jsScript)

                // get a style to understand availability
                val dayStyle = linkElement.attr("style")

                val day = linkElement.text().toInt()
                val isOutsideMonth = dayStyle.contains("Gray")
                val isSelected = dayStyle.contains("White")
                val isDisabled = dayElement.attr("disabled") == "disabled"

                // if this day belongs to another month, ignore it
                if (isOutsideMonth) {
                    continue
                }

                val calendarDay = CalendarDay(
                    date = LocalDate.of(year, month, day),
                    disabled = isDisabled,
                    selected = isSelected,
                    eventTarget = target,
                    eventArgument = argument,
                )
                days.add(calendarDay)
            }
        }

        return CalendarInfo(
            month = month,
            year = year,
            days = days
        )
    }

    fun parseSlots(page: Document): List<Slot> {
        val slotsPanel = page.selectFirst("#ctl00_MainContent_RadioButtonList1")
            ?: throw PageParseSessionException("Slot panel is not found on the page")

        // get rows of elements
        val rowElements = slotsPanel.selectXpath(".//label")
        return rowElements
            .map { element ->
                val text = element.text()

                val regex = Regex("([\\d.]{10}) ([\\d:]{4,5}) \\((.*)\\)")
                val matchResult = regex.find(text)
                    ?: throw PageParseSessionException("Failed to parse '$text' by regex $regex")

                val date = LocalDate.parse(matchResult.groupValues[1], DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                val time = LocalTime.parse(matchResult.groupValues[2], DateTimeFormatter.ofPattern("H:mm"))
                val description = matchResult.groupValues[3]

                val dateTime = LocalDateTime.of(date, time)
                return@map Slot(dateTime, description)
            }
    }

    private fun extractJsKeyValue(jsScript: String): Pair<String, String> {
        // should parse the line of type
        // javascript:__doPostBack('ctl00$MainContent$Calendar','8885')
        val keyValueRegex = Regex("javascript:__doPostBack\\('(.*)','(.*)'\\)")
        val matchResult = keyValueRegex.find(jsScript)
            ?: throw PageParseSessionException("Valued to parse jsScript '$jsScript' by regex '$keyValueRegex'")

        val key = matchResult.groupValues[1]
        val value = matchResult.groupValues[2]
        return key to value
    }

    private fun extractMonthAndYear(text: String): Pair<Int, Int> {
        // should parse the line of type: Maй 2024
        val monthYearRegex = Regex("(.+) (\\d{4})")
        val matchResult = (monthYearRegex.find(text)
            ?: throw PageParseSessionException("Valued to parse monthAndYear '$text' by regex '$monthYearRegex'"))

        val month = matchResult.groupValues[1].let {
            when (it) {
                "Январь" -> 1
                "Февраль" -> 2
                "Март" -> 3
                "Апрель" -> 4
                "Май" -> 5
                "Июнь" -> 6
                "Июль" -> 7
                "Август" -> 8
                "Сентябрь" -> 9
                "Октябрь" -> 10
                "Ноябрь" -> 11
                "Декабрь" -> 12
                else -> throw PageParseSessionException("Unknown month '$it'")
            }
        }
        val year = matchResult.groupValues[2].toInt()
        return month to year
    }
}

data class CalendarInfo(
    val month: Int,
    val year: Int,
    val days: List<CalendarDay>
) {
    /**
     * Return days, containing available slots
     */
    val availableDays: List<CalendarDay>
        get() = days.filter { !it.disabled }

    /**
     * Return selected day.
     */
    val selectedDay: CalendarDay
        get() = days.first { it.selected }
}

data class CalendarDay(
    /**
     * Calendar date
     */
    val date: LocalDate,
    /**
     * true if contains not available slots
     */
    val disabled: Boolean,
    /**
     * true if currently this date is selected
     */
    val selected: Boolean,
    /**
     * Target of a day.
     */
    val eventTarget: String,
    /**
     * Argument of a day.
     */
    val eventArgument: String,
)

/**
 * Availability slot on the page
 */
data class Slot(
    val dateTime: LocalDateTime,
    val description: String,
)