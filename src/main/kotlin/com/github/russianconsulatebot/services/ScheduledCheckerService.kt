package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.services.dto.Website
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Checker that finds available slots.
 */
@Service
class ScheduledCheckerService(
    private val checkSlotsDispatcher: ExecutorCoroutineDispatcher,
    private val passport10Service: Passport10Service,
    private val telegramBotService: TelegramBotService,
    @Value("\${scheduler.charIds:}") private val chatIds: List<Long>,
    private val lastChecks: LastChecks,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(cron = "\${scheduler.day_cron}", scheduler = "checkSlotsExecutor")
    suspend fun dayScheduler() {
        // By default, Scheduler is starting a new suspend function in Unconfined dispatcher (https://t.ly/udrQi)
        // which resulted that business code was running in reactor thread (reactor-http-nio-*)
        // To solve it, we explicitly set dispatcher, that is made from scheduler dispatcher
        withContext(checkSlotsDispatcher) {
            log.debug("Started day check")
            val website = Website.HAGUE
            if (isNightTime(website)) {
                log.debug("Stopped day check, because it's a night at {}", website)
                return@withContext
            }
            doCheck(website)
        }
    }

    @Scheduled(cron = "\${scheduler.night_cron}", scheduler = "checkSlotsExecutor")
    suspend fun nightScheduler() {
        withContext(checkSlotsDispatcher) {
            log.debug("Started night check")
            val website = Website.HAGUE
            if (!isNightTime(website)) {
                log.debug("Stopped night check, because it's a day at {}", website)
                return@withContext
            }
            doCheck(website)
        }
    }

    private suspend fun doCheck(website: Website) {
        log.info("Started finding available slots for {}", website.baseUrl)

        try {
            val availableSlots = passport10Service.containsAvailableSlots(website)
            log.info("Found available slots for notary: {}", availableSlots)

            if (availableSlots || true) {
                for (chatId in chatIds) {
                    telegramBotService.sendMessage(
                        chatId,
                        "Found available passport for 10 years slots on https://hague.kdmid.ru/ !!!"
                    )
                }
            }

            lastChecks.push("Found slot: $availableSlots")
        } catch (e: Exception) {
            log.error("Got an error", e)
            lastChecks.push(e.toString())
            throw e
        }
    }

    private fun isNightTime(website: Website): Boolean {
        val zonedDateTime = Instant.now().atZone(website.timezone)
        return zonedDateTime.hour >= 23 || zonedDateTime.hour <= 7
    }
}