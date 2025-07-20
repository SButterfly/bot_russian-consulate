package com.github.russianconsulatebot.services.dto

import java.util.TimeZone

enum class Website(
    val baseUrl: String,
    val timezone: TimeZone
) {
    HAGUE("https://hague.kdmid.ru", TimeZone.getTimeZone("Europe/Amsterdam"))
}