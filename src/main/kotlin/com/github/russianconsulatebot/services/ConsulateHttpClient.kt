package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.SessionBrokenException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.parameters
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

private const val SESSION_ID_COOKIE = "ASP.NET_SessionId"

/**
 * A client that helps to navigate the website.
 */
@Service
class ConsulateHttpClient(
    private val httpClient: HttpClient,
    private val antiCaptureService: AntiCaptureService,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Starts a new session.
     * TODO Do this for each user when /starting the bot
     * TODO Or group by service to reduce queries for similar services?
     */
    suspend fun startSession(baseUrl: String, userInfo: UserInfo): SessionInfo {
        log.info("Starting new session...")

        // Get initial page with captcha
        val pageUrl = "$baseUrl/queue/visitor.aspx"
        val httpResponse = httpClient.get(pageUrl)
        val bodyAsText = httpResponse.bodyAsText()
        val document = Jsoup.parse(bodyAsText)
        log.debug("STATUS: {}, BODY {}", httpResponse.status.value, bodyAsText)

        val (eventValidation, viewState) = parseState(document)
        val (sessionId, captchaCode) = parseSessionIdAndCaptureCode(baseUrl, document)

        // Submit form
        log.debug("Submitting form...")
        val response = httpClient.submitForm(
            url = "$baseUrl/queue/visitor.aspx",
            formParameters = parameters {
                append("__EVENTTARGET", "")
                append("__EVENTARGUMENT", "")
                append("__VIEWSTATE", viewState)
                append("__EVENTVALIDATION", eventValidation)
                append("ctl00\$MainContent\$txtFam", userInfo.firstName)
                append("ctl00\$MainContent\$txtIm", userInfo.secondName)
                append("ctl00\$MainContent\$txtOt", userInfo.patronymic ?: "")
                append("ctl00\$MainContent\$txtTel", userInfo.phoneNumber)
                append("ctl00\$MainContent\$txtEmail", userInfo.email)
                append("ctl00\$MainContent\$DDL_Day", userInfo.birthDate.format(DateTimeFormatter.ofPattern("dd")))
                append("ctl00\$MainContent\$DDL_Month", userInfo.birthDate.format(DateTimeFormatter.ofPattern("MM")))
                append("ctl00\$MainContent\$TextBox_Year", userInfo.birthDate.format(DateTimeFormatter.ofPattern("yyyy")))
                append("ctl00\$MainContent\$DDL_Mr", userInfo.title.name)
                append("ctl00\$MainContent\$txtOt", captchaCode)
                append("ctl00\$MainContent\$ButtonA", "Далее")
            },
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=$sessionId")
            }
        )

        val submitResponse = response.bodyAsText()
        log.debug(submitResponse)
        require(response.status == HttpStatusCode.OK)

        return SessionInfo(sessionId, userInfo)
    }

    /**
     * Returns order page info, where you can see a calendar with available slots.
     */
    suspend fun fetchOrderInfo(baseUrl: String, order: Order): OrderInfo {
        log.info("Starting a new session...")
        
        // Get page to check status of order
        val orderPageResponse = httpClient.get("$baseUrl/queue/orderinfo.aspx")
        val orderPage = Jsoup.parse(orderPageResponse.bodyAsText())
        log.debug("STATUS: {}, BODY {}", orderPageResponse.status.value, orderPage)

        val (eventValidation, viewState) = parseState(orderPage)
        val (sessionId, captchaCode) = parseSessionIdAndCaptureCode(baseUrl, orderPage)

        // Submit form
        log.debug("Submitting form...")
        val confirmationPageResponse = httpClient.submitForm(
            url = "$baseUrl/queue/orderinfo.aspx",
            formParameters = parameters {
                append("__EVENTTARGET", "")
                append("__EVENTARGUMENT", "")
                append("__VIEWSTATE", viewState)
                append("__EVENTVALIDATION", eventValidation)
                append("ctl00\$MainContent\$txtID", order.orderNumber)
                append("ctl00\$MainContent\$txtUniqueID", order.code)
                append("ctl00\$MainContent\$txtCode", captchaCode)
                append("ctl00\$MainContent\$ButtonA", "Далее")
                append("ctl00\$MainContent\$FeedbackClientID", "0")
                append("ctl00\$MainContent\$FeedbackOrderID", "0")
            },
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=$sessionId")
            }
        )

        val confirmationPage = Jsoup.parse(confirmationPageResponse.bodyAsText())
        log.debug("STATUS: {}, BODY {}", confirmationPageResponse.status.value, confirmationPage)
        require(confirmationPageResponse.status == HttpStatusCode.OK)

        // Reparse some params
        val (eventValidation2, viewState2) = parseState(confirmationPage)

        // Submit again:
        log.debug("Submitting form again...")
        val confirmationPageResponse2 = httpClient.submitForm(
            url = "$baseUrl/queue/orderinfo.aspx",
            formParameters = parameters {
                append("__EVENTTARGET", "")
                append("__EVENTARGUMENT", "")
                append("__VIEWSTATE", viewState2)
                append("__EVENTVALIDATION", eventValidation2)
                append("ctl00\$MainContent\$ButtonB.x", "133")
                append("ctl00\$MainContent\$ButtonB.y", "30")
            },
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=$sessionId")
            }
        )
        val confirmationPage2 = Jsoup.parse(confirmationPageResponse2.bodyAsText())
        log.debug("STATUS: {}, BODY {}", confirmationPageResponse2.status.value, confirmationPage2)
        require(confirmationPageResponse2.status == HttpStatusCode.Found)

        // Get redirect page
        val redirectPath = confirmationPageResponse2.headers[HttpHeaders.Location]
        log.info("Check URL: $redirectPath")

        return OrderInfo(baseUrl, redirectPath!!, sessionId, order)
    }

    suspend fun checkOrder(orderInfo: OrderInfo) : Boolean {
        log.info("Checking an order at {}", orderInfo)

        val httpResponse = httpClient.get("${orderInfo.baseUrl}/${orderInfo.orderPagePath}") {
            header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=${orderInfo.sessionId}")
        }

        val orderPage = httpResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", httpResponse.status.value, orderPage)

        // This is an email field from first page. If session was broken than it will redirect to that page
        if (orderPage.contains("<input name=\"ctl00\$MainContent\$txtEmail\" type=\"text\"")) {
            throw SessionBrokenException("Redirected to the start page")
        }

        // Calendar widget should be on our target page
        if (!orderPage.contains("id=\"ctl00_MainContent_Calendar\"")) {
            throw SessionBrokenException("Calendar widget is not on the page")
        }

        // Ok, seems like we're there.
        if (orderPage.contains("<p>Извините, но в настоящий момент на интересующее Вас консульское действие в системе предварительной записи нет свободного времени.</p>")) {
            return false
        } else {
            return true
        }
    }

    private suspend fun parseSessionIdAndCaptureCode(baseUrl: String, document: Document) : Pair<String, String> {
        // Parse captcha image src
        log.info("Parsing captcha image src...")
        val imageElement = document.select("div.inp img").first()
        val captchaSrc = imageElement?.attr("src") ?: throw RuntimeException("Captcha image not found")

        val captchaUrl = "$baseUrl/queue/$captchaSrc"
        log.info("Captcha url: {}", captchaUrl)

        val (imageByteArray, sessionId) = fetchImageAndSessionId(captchaUrl)
        val captchaCode = antiCaptureService.solve(imageByteArray, "image/jpeg")
        log.info("Capture code: {}", captchaCode)
        log.info("Session id: {}", sessionId)

        return Pair(sessionId, captchaCode)
    }

    private suspend fun parseState(document: Document) : Pair<String, String> {
        log.info("Parsing state...")
        val eventValidationElement = document.select("input#__EVENTVALIDATION").first()
        val eventValidationField = eventValidationElement?.attr("value") ?: ""

        val viewStateElement = document.select("input#__VIEWSTATE").first()
        val viewStateField = viewStateElement?.attr("value") ?: ""

        log.info("EventValidation code: {}", eventValidationField)
        log.info("ViewState: {}", viewStateField)

        return Pair(eventValidationField, viewStateField)
    }

    private suspend fun fetchImageAndSessionId(captchaUrl: String): Pair<ByteArray, String> {
        return httpClient.prepareGet(captchaUrl).execute { response ->
            val setCookie = response.headers[HttpHeaders.SetCookie]
            log.info("Received image with ${response.contentLength()} content length and Set-Cookie ${setCookie}")

            val sessionId = setCookie
                ?.split(";")
                ?.find { it.startsWith(SESSION_ID_COOKIE) }
                ?.split("=")
                ?.get(1)
                ?: throw RuntimeException("ASP.NET_SessionId header not found in cookies: $setCookie")

            val outputStream = ByteArrayOutputStream()
            val channel: ByteReadChannel = response.body()
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    outputStream.write(bytes)
                }
            }
            return@execute Pair(outputStream.toByteArray(), sessionId)
        }
    }
}
