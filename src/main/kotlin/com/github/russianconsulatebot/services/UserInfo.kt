package com.github.russianconsulatebot.services

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

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
        fun generateDummyUserInfo(): UserInfo {
            val suffix = Random.nextInt()
            return UserInfo(
                "Ivan",
                "Ivanov_$suffix",
                null,
                "+31123456789",
                "example${suffix}@example.com",
                LocalDate.parse("2000-01-01"),
                Title.MR,
            )
        }
    }

    val dateOfBirthStr = birthDate.format(DateTimeFormatter.ofPattern("dd"))
    val monthOfBirthStr = birthDate.format(DateTimeFormatter.ofPattern("MM"))
    val yearOfBirthStr= birthDate.format(DateTimeFormatter.ofPattern("yyyy"))
}

enum class Title {
    MR,
    MS
}
