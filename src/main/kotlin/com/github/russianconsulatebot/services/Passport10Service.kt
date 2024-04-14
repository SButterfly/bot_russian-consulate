package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.CaptureSessionException
import com.github.russianconsulatebot.exceptions.SessionException
import com.github.russianconsulatebot.services.dto.UserInfo
import com.github.russianconsulatebot.services.dto.Website
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.FileOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

private const val BIOPASSPORT = "BIOPASSPORT"

private const val CALENDAR_PAGE = "/queue/SPCalendar.aspx"
private const val WAITLIST_PAGE = "/queue/waitlist.aspx"

/**
 * Check availability on the passport 10.
 */
@Service
class Passport10Service(
    private val consulateHttpClient: ConsulateHttpClient,
) {
    private val log = LoggerFactory.getLogger(Passport10Service::class.java)

    suspend fun containsAvailableSlots(website: Website): Boolean {
        val userInfo = UserInfo.generateDummyUserInfo()
        log.info("Starting a new session")
        val sessionInfo = retry(3) { consulateHttpClient.startSession(website.baseUrl, userInfo) }
        log.info("Got session: {}", sessionInfo)

        val calendarPath = consulateHttpClient.passToOrderPage(sessionInfo, BIOPASSPORT)
        log.info("Got order path: {}", calendarPath)
        if (calendarPath == WAITLIST_PAGE) {
            throw IllegalStateException("Page '$calendarPath' is not supported yet")
        }
        if (calendarPath != CALENDAR_PAGE) {
            throw IllegalStateException("Expected $calendarPath to be equal $CALENDAR_PAGE")
        }

        val hasSlots = consulateHttpClient.checkAvailableSlots(sessionInfo, CALENDAR_PAGE)
        log.info("Has windows {}", hasSlots)
        return hasSlots
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
