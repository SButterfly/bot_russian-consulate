package com.github.russianconsulatebot.services

import java.time.LocalDate

data class UserInfo(
    val firstName: String,
    val secondName: String,
    val patronymic: String?,
    val phoneNumber: String,
    val email: String,
    val birthDate: LocalDate,
    val title: Title,
) {
    companion object {
        val DUMMY = UserInfo(
            "Ivan",
            "Ivanov",
            null,
            "+79170123456",
            "teloh47590@aersm.com", // "example@example.com",
            LocalDate.parse("2000-01-01"),
            Title.MR,
        )
    }
}

enum class Title {
    MR,
    MS
}
