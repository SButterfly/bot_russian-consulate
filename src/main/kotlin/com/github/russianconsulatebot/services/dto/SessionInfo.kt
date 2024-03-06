package com.github.russianconsulatebot.services.dto

data class SessionInfo(
    val sessionId: String,
    val baseUrl: String,
    val userInfo: UserInfo,
)