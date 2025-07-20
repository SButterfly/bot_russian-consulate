package com.github.russianconsulatebot.services

data class OrderInfo(
    /**
     * Url of the base site, like https://hague.kdmid.ru
     */
    val baseUrl: String,
    /**
     * relative path to the order page
     */
    val orderPagePath: String,
    /**
     * session id key
     */
    val sessionId: String,
    /**
     * Initial order information
     */
    val order: Order,
)