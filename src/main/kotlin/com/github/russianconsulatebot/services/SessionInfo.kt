package com.github.russianconsulatebot.services

data class SessionInfo(
    val sessionId: String,
    val baseUrl: String,
    val userInfo: UserInfo,
)