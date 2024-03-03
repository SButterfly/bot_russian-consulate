package com.github.russianconsulatebot.services

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Checker that finds available slots.
 */
@Service
class ScheduledCheckerService(
    private val passport10Service: Passport10Service,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(cron = "\${scheduler.cron}")
    suspend fun findAvailableWindows() {
        log.info("Started finding available slots")

        val availableSlots = passport10Service.containsAvailableSlots()

        log.info("Found available slots for notary: {}", availableSlots)
    }
}