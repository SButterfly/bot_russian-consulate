package com.github.russianconsulatebot.services.dto

import java.time.ZoneId

enum class Website(
    val baseUrl: String,
    val timezone: ZoneId
) {
    HAGUE("https://hague.kdmid.ru", ZoneId.of("Europe/Amsterdam")),
    BELGUM("https://brussels.kdmid.ru", ZoneId.of("Europe/Brussels"))
}