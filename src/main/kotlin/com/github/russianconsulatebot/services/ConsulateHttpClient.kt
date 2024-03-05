package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.DataErrorSessionException
import com.github.russianconsulatebot.exceptions.SessionException
import com.github.russianconsulatebot.exceptions.WrongCaptureSessionException
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
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

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
     * Logins in the system and returns session information.
     */
    suspend fun startSession(baseUrl: String, userInfo: UserInfo): SessionInfo {
        log.info("Starting new session...")

        // Get initial page with captcha
        val httpResponse = httpClient.get("$baseUrl/queue/visitor.aspx")
        val bodyAsText = httpResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", httpResponse.status.value, bodyAsText)

        // Parse the page
        val document = Jsoup.parse(bodyAsText)
        val pageState = parsePageState(document)
        val (sessionId, captchaCode, _) = parseSessionIdAndCaptureCode(baseUrl, document)

        // Submit form
        log.info("Submitting form with userInfo=$userInfo, captureCode=$captchaCode, and sessionId=$sessionId")
        val submitResponse = httpClient.submitForm(
            url = "$baseUrl/queue/visitor.aspx",
            formParameters = parameters {
                append("__EVENTTARGET", "")
                append("__EVENTARGUMENT", "")
                append("__VIEWSTATE", pageState.viewState)
                append("__EVENTVALIDATION", pageState.eventValidation)
                append("ctl00\$MainContent\$txtFam", userInfo.firstName)
                append("ctl00\$MainContent\$txtIm", userInfo.secondName)
                append("ctl00\$MainContent\$txtOt", userInfo.patronymic ?: "")
                append("ctl00\$MainContent\$txtTel", userInfo.phoneNumber)
                append("ctl00\$MainContent\$txtEmail", userInfo.email)
                append("ctl00\$MainContent\$DDL_Day", userInfo.dateOfBirthStr)
                append("ctl00\$MainContent\$DDL_Month", userInfo.monthOfBirthStr)
                append("ctl00\$MainContent\$TextBox_Year", userInfo.yearOfBirthStr)
                append("ctl00\$MainContent\$DDL_Mr", userInfo.title.name)
                append("ctl00\$MainContent\$txtCode", captchaCode)
                append("ctl00\$MainContent\$ButtonA", "Далее")
            },
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=$sessionId")
            }
        )

        // Parse menu page
        val menuPageStr = submitResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", submitResponse.status.value, menuPageStr)

        val menuPage = Jsoup.parse(menuPageStr)
        val errorText = parseErrorText(menuPage)

        if (errorText != null) {
            if (errorText.contains("Символы с картинки введены не правильно")) {
                throw WrongCaptureSessionException("Capture '$captchaCode' was wrong")
            }
            throw DataErrorSessionException("Data error: $errorText")
        }

        // We are on menu page
        if (!menuPageStr.contains("ПЕРЕЧЕНЬ КОНСУЛЬСКИХ ДЕЙСТВИЙ")) {
            throw SessionException("Failed to find menu on the page")
        }

        return SessionInfo(sessionId, baseUrl, userInfo)
    }

    /**
     * Fills in any forms to get to the calendar page.
     * Returns url path to the consulate action. e.g. TODO
     */
    suspend fun passToCalendarPage(sessionInfo: SessionInfo, consulateType: String): String {
        log.info("Start passing forms for $consulateType and sessionInfo $sessionInfo")

        // Get the first page with option, which passport type we should choose
        val orderPageResponse = httpClient.get(
            urlString = "${sessionInfo.baseUrl}/queue/bpssp.aspx?nm=$consulateType",
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
            }
        )
        val orderPageStr = orderPageResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", orderPageResponse.status.value, orderPageStr)

        val orderPage = Jsoup.parse(orderPageStr)
        val orderPageState = parsePageState(orderPage)

        // Submit with adult params
        val submitResponse = httpClient.submitForm(
            url = "${sessionInfo.baseUrl}/queue/bpssp.aspx?nm=$consulateType",
            formParameters = parameters {
                append("__EVENTTARGET", "")
                append("__EVENTARGUMENT", "")
                append("__VIEWSTATE", orderPageState.viewState)
                append("__EVENTVALIDATION", orderPageState.eventValidation)
                append("ctl00\$MainContent\$RList", "$consulateType;PSSP")
                append("ctl00\$MainContent\$CheckBoxID", "on")
                append("ctl00\$MainContent\$ButtonA", "Далее")
            },
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
            }
        )

        // It should redirect to the page with Confirmation
        val confirmationPageStr = submitResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", submitResponse.status.value, confirmationPageStr)
        require(submitResponse.status == HttpStatusCode.Found)

        val secondConfirmResponse = httpClient.get(
            urlString = "${sessionInfo.baseUrl}/queue/Rlist.aspx",
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
            }
        )

        val secondConfirmPageStr = secondConfirmResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", secondConfirmResponse.status.value, secondConfirmPageStr)

        val confirmationPage = Jsoup.parse(secondConfirmPageStr)
        val confirmationState = parsePageState(confirmationPage)

        // TODO Add check, that such order already exists in the system

        log.info("Sending second confirmation")
        val submitResponse2 = httpClient.submitForm(
            url = "${sessionInfo.baseUrl}/queue/Rlist.aspx",
            formParameters = parameters {
                append("__EVENTTARGET", "")
                append("__EVENTARGUMENT", "")
                append("__VIEWSTATE", confirmationState.viewState)
                append("__EVENTVALIDATION", confirmationState.eventValidation)
                // __PREVIOUSPAGE
                append("ctl00\$MainContent\$ButtonQueue", "Записаться на прием")
            },
            block = {
                header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
            }
        )

        val redirectPage = submitResponse2.bodyAsText()
        log.debug("STATUS: {}, BODY {}", submitResponse2.status.value, redirectPage)
        require(submitResponse2.status == HttpStatusCode.Found)

        val calendarPath = submitResponse2.headers[HttpHeaders.Location]
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
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

        val (eventValidation, viewState) = parsePageState(orderPage)
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
        val (eventValidation2, viewState2) = parsePageState(confirmationPage)

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

    /**
     * Checks available slots on the calendar page.
     */
    suspend fun checkAvailableSlots(sessionInfo: SessionInfo, calendarPage: String) : Boolean {
        log.info("Checking an order at {}", sessionInfo)

        val httpResponse = httpClient.get("${sessionInfo.baseUrl}${calendarPage}") {
            header(HttpHeaders.Cookie, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
        }

        val orderPageStr = httpResponse.bodyAsText()
        log.debug("STATUS: {}, BODY {}", httpResponse.status.value, orderPageStr)

        // TODO This is an email field from first page. If session was broken than it will redirect to that page
        if (orderPageStr.contains("<input name=\"ctl00\$MainContent\$txtEmail\" type=\"text\"")) {
            throw SessionException("Redirected to the start page")
        }

        if (orderPageStr.contains("Ваша заявка заблокирована")) {
            throw SessionException("Your order is blocked")
        }

        // Calendar widget should be on our target page
        if (!orderPageStr.contains("id=\"ctl00_MainContent_Calendar\"")) {
            throw SessionException("Calendar widget is not on the page")
        }

        // Ok, seems like we're there.
        if (orderPageStr.contains("<p>Извините, но в настоящий момент на интересующее Вас консульское действие в системе предварительной записи нет свободного времени.</p>")) {
            return false
        } else {
            return true
        }
    }

    private suspend fun parseSessionIdAndCaptureCode(baseUrl: String, document: Document) : Triple<String, String, ByteArray> {
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

        return Triple(sessionId, captchaCode, imageByteArray)
    }

    private fun parsePageState(document: Document) : PageState {
        log.info("Parsing state...")
        val eventValidationElement = document.select("input#__EVENTVALIDATION").first()
        val eventValidationField = eventValidationElement?.attr("value") ?: ""

        val viewStateElement = document.select("input#__VIEWSTATE").first()
        val viewStateField = viewStateElement?.attr("value") ?: ""

        log.info("EventValidation code: {}", eventValidationField)
        log.info("ViewState: {}", viewStateField)

        return PageState(eventValidationField, viewStateField)
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

    private fun parseErrorText(document: Document): String? {
        val errorBlock = document.select("#ctl00_MainContent_lblCodeErr").first() ?: return null

        // the first element of erorr block is <font>, skip it
        val children = errorBlock.children().first()!!.childNodes()
        val stringBuilder = StringBuilder()
        for (child in children) {
            if (child is TextNode) {
                stringBuilder.append(child.text()).append("\n")
            }
        }
        return stringBuilder.trim().toString()
    }
}
