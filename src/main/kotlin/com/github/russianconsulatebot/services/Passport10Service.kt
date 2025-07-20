package com.github.russianconsulatebot.services

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Check availability on the passport 10.
 */
@Service
class Passport10Service(
    private val consulateHttpClient: ConsulateHttpClient,
) {
    private val log = LoggerFactory.getLogger(Passport10Service::class.java)

    suspend fun containsAvailableSlots() : Boolean {
        // TODO remove hardcoded values
        val baseUrl = "https://hague.kdmid.ru"
        val order = Order("104497", "8F71F287")

        val orderInfo = consulateHttpClient.fetchOrderInfo(baseUrl, order)
        log.info("Found order info: {}", orderInfo)

        val hasWindows = consulateHttpClient.checkOrder(orderInfo)
        return hasWindows
    }
}
