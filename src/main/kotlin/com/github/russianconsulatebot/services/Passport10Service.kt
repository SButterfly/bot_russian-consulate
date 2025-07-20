package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.CaptureSessionException
import com.github.russianconsulatebot.exceptions.ExpiredSessionException
import com.github.russianconsulatebot.exceptions.SessionException
import com.github.russianconsulatebot.services.dto.SessionInfo
import com.github.russianconsulatebot.services.dto.UserInfo
import com.github.russianconsulatebot.services.dto.Website
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private const val BIOPASSPORT = "BIOPASSPORT"

private const val CALENDAR_PAGE = "/queue/SPCalendar.aspx"

/**
 * Check availability on the passport 10.
 */
@Service
class Passport10Service(
    private val consulateHttpClient: ConsulateHttpClient,
) {
    private val log = LoggerFactory.getLogger(Passport10Service::class.java)

    private val map = ConcurrentHashMap<Website, SessionInfo>()

    suspend fun containsAvailableSlots(website: Website): Boolean {
        when (website) {
            Website.HAGUE -> {
                return simpleCheck(website)
            }
            Website.BELGUM -> {
                return checkWithEmailConfirmation(website)
            }
        }
    }

    private suspend fun simpleCheck(website: Website): Boolean {
        // remove cached session info, as we treat every cached session as broken
        val cachedSessionInfo = map.remove(website)
        if (cachedSessionInfo != null) {
            log.info("Found cached session info: {}", cachedSessionInfo)
            try {
                val hasSlots = consulateHttpClient.checkAvailableSlots(cachedSessionInfo, CALENDAR_PAGE)
                log.info("Has windows {}", hasSlots)
                // restore cache info, as it's still alive
                map[website] = cachedSessionInfo
                return hasSlots
            } catch (e: ExpiredSessionException) {
                log.info("The current session is expired. Create a new one")
            }
        }

        val userInfo = UserInfo.generateDummyUserInfo()
        log.info("Starting a new session")
        val sessionInfo = retry(3) { consulateHttpClient.startSession(website.baseUrl, userInfo) }
        log.info("Got session: {}", sessionInfo)
        val calendarPath = consulateHttpClient.passToOrderPage(sessionInfo, BIOPASSPORT)
        log.info("Got order path: {}", calendarPath)
        if (calendarPath != CALENDAR_PAGE) {
            throw IllegalStateException("Expected $calendarPath to be equal $CALENDAR_PAGE")
        }

        val hasSlots = consulateHttpClient.checkAvailableSlots(sessionInfo, CALENDAR_PAGE)
        log.info("Has windows {}", hasSlots)
        map[website] = sessionInfo
        return hasSlots
    }

    private suspend fun checkWithEmailConfirmation(website: Website): Boolean {
        val userInfo = UserInfo.generateDummyUserInfo()
        val sessionInfo = retry(3) { consulateHttpClient.startSession(website.baseUrl, userInfo) }
        log.info("Got session: {}", sessionInfo)
        val orderPath = consulateHttpClient.passToOrderPage(sessionInfo, BIOPASSPORT)
        log.info("Got order path: {}", orderPath)
        val order = consulateHttpClient.parseOrder(sessionInfo, orderPath)

        // TODO confirm an order by email

        log.info("Got order {}", order)
        val calendarPath = retry(3) { consulateHttpClient.startCheckingAndOrder(website.baseUrl, order) }
        log.info("Got calendar path {}", order)
        val hasWindows = consulateHttpClient.checkAvailableSlots(sessionInfo, calendarPath)
        log.info("Has windows {}", hasWindows)
        return hasWindows
    }

    private suspend fun <T> retry(maxAttempts: Int, function: suspend () -> T): T {
        var lastException: Exception
        var invocationNumber = 0
        do {
            try {
                return function()
            } catch (e: CaptureSessionException) {
                val tempFile = createTempPicture(e.image)
                val mappedException = SessionException("${e.message}. Capture file $tempFile", e)
                log.warn("Retrying due to an error", mappedException)
                lastException = mappedException
            }
        } while (++invocationNumber < maxAttempts)
        throw lastException
    }

    private suspend fun createTempPicture(image: BufferedImage) = withContext(Dispatchers.IO) {
        val tempFile = Files.createTempFile("capture", ".jpeg").toFile()
        FileOutputStream(tempFile).use {
            ImageIO.write(image, "jpeg", it)
        }
        tempFile
    }
}
