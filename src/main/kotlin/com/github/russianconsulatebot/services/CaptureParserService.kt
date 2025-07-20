package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.utils.executeAsync
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Service to find capture-image on a page.
 */
@Service
class CaptureParserService(
    private val consulateOkHttpClient: OkHttpClient,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Parses session_id and capture image.
     */
    suspend fun parseSessionIdAndCaptureImage(baseUrl: String, document: Document): Pair<String, BufferedImage> {
        log.info("Parsing captcha image src...")
        val imageElement = document.selectFirst("div.inp img")
        val captchaSrc = imageElement?.attr("src") ?: throw RuntimeException("Captcha image not found")

        val captchaUrl = "$baseUrl/queue/$captchaSrc"
        log.info("Captcha url: {}", captchaUrl)

        val (sessionId, imageByteArray) = fetchSessionIdAndImage(captchaUrl)
        log.info("Session id: {}", sessionId)

        val image = ImageIO.read(ByteArrayInputStream(imageByteArray))
        val processedImage = postProcess(image)

        return Pair(sessionId, processedImage)
    }

    private fun postProcess(image: BufferedImage): BufferedImage {
        // For now, we should return only middle capture
        return image.getSubimage(200, 0, 200, 200)
    }

    private suspend fun fetchSessionIdAndImage(captchaUrl: String): Pair<String, ByteArray> {
        val response = consulateOkHttpClient.newCall(
            Request.Builder()
                .url(captchaUrl)
                .build()
        )
            .executeAsync()

        val setCookieHeaders = response.headers[HttpHeaders.SET_COOKIE]
        val sessionId = setCookieHeaders?.split(";")
            ?.find { it.startsWith(SESSION_ID_COOKIE) }
            ?.split("=")
            ?.get(1)
            ?: throw RuntimeException("ASP.NET_SessionId header not found in cookies: $setCookieHeaders")

        return Pair(sessionId, response.body!!.bytes())
    }

    /**
     * Converts a given Image into a BufferedImage
     *
     * @param img The Image to be converted
     * @return The converted BufferedImage
     */
    fun toBufferedImage(img: Image): BufferedImage {
        if (img is BufferedImage) {
            return img
        }

        // Create a buffered image with transparency
        val bimage = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB)

        // Draw the image on to the buffered image
        val bGr = bimage.createGraphics()
        bGr.drawImage(img, 0, 0, null)
        bGr.dispose()

        // Return the buffered image
        return bimage
    }
}
