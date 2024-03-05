package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.DataErrorSessionException
import com.github.russianconsulatebot.exceptions.SessionException
import com.github.russianconsulatebot.exceptions.WrongCaptureSessionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.nio.file.Files
import kotlin.io.path.writeBytes

private const val SESSION_ID_COOKIE = "ASP.NET_SessionId"

/**
 * A client that helps to navigate the website.
 */
@Service
class ConsulateHttpClient(
    private val webClient: WebClient,
//    private val httpClient: HttpClient,
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
        val document = webClient.get()
            .uri("$baseUrl/queue/visitor.aspx")
            .retrieve()
            .awaitBody<Document>()

        // Parse the page
        val pageState = parsePageState(document)
        val (sessionId, captchaCode, imageBytes) = parseSessionIdAndCaptureCode(baseUrl, document)

        // Submit form
        log.info("Submitting form with userInfo=$userInfo, captureCode=$captchaCode, and sessionId=$sessionId")
        val menuPage = webClient.post()
            .uri("$baseUrl/queue/visitor.aspx")
            .cookie(SESSION_ID_COOKIE, sessionId)
            .body(BodyInserters.fromFormData(
                LinkedMultiValueMap<String, String>()
                    .apply {
                        add("__EVENTTARGET", "")
                        add("__EVENTARGUMENT", "")
                        add("__VIEWSTATE", pageState.viewState)
                        add("__EVENTVALIDATION", pageState.eventValidation)
                        add("ctl00\$MainContent\$txtFam", userInfo.firstName)
                        add("ctl00\$MainContent\$txtIm", userInfo.secondName)
                        add("ctl00\$MainContent\$txtOt", userInfo.patronymic ?: "")
                        add("ctl00\$MainContent\$txtTel", userInfo.phoneNumber)
                        add("ctl00\$MainContent\$txtEmail", userInfo.email)
                        add("ctl00\$MainContent\$DDL_Day", userInfo.dateOfBirthStr)
                        add("ctl00\$MainContent\$DDL_Month", userInfo.monthOfBirthStr)
                        add("ctl00\$MainContent\$TextBox_Year", userInfo.yearOfBirthStr)
                        add("ctl00\$MainContent\$DDL_Mr", userInfo.title.name)
                        add("ctl00\$MainContent\$txtCode", captchaCode)
                        add("ctl00\$MainContent\$ButtonA", "Далее")
                    }
            ))
            .retrieve()
            .awaitBody<Document>()

        // Parse menu page
        val errorText = parseErrorText(menuPage)

        if (errorText != null) {
            if (errorText.contains("Символы с картинки введены не правильно")) {
                val file = withContext(Dispatchers.IO) {
                    Files.createTempFile("capture", ".jpeg")
                        .apply { writeBytes(imageBytes) }
                }

                throw WrongCaptureSessionException("Capture '$captchaCode' was wrong $file")
            }
            throw DataErrorSessionException("Data error: $errorText")
        }

        // We are on menu page
        if (menuPage.selectFirst(":containsOwn(ПЕРЕЧЕНЬ КОНСУЛЬСКИХ ДЕЙСТВИЙ)") == null) {
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
        val orderPage = webClient.get()
            .uri("${sessionInfo.baseUrl}/queue/bpssp.aspx?nm=$consulateType")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .retrieve()
            .awaitBody<Document>()

        val orderPageState = parsePageState(orderPage)

        // Submit with adult params
        val submitResponse = webClient.post()
            .uri("${sessionInfo.baseUrl}/queue/bpssp.aspx?nm=$consulateType")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .body(BodyInserters.fromFormData(
                LinkedMultiValueMap<String, String>()
                    .apply {
                        add("__EVENTTARGET", "")
                        add("__EVENTARGUMENT", "")
                        add("__VIEWSTATE", orderPageState.viewState)
                        add("__EVENTVALIDATION", orderPageState.eventValidation)
                        add("ctl00\$MainContent\$RList", "$consulateType;PSSP")
                        add("ctl00\$MainContent\$CheckBoxID", "on")
                        add("ctl00\$MainContent\$ButtonA", "Далее")
                    }
            ))
            .retrieve()
            .toBodilessEntity()
            .awaitSingle()

        // It should redirect to the page with Confirmation
        require(submitResponse.statusCode == HttpStatus.FOUND)

        val secondConfirmPage = webClient.get()
            .uri("${sessionInfo.baseUrl}/queue/Rlist.aspx")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .retrieve()
            .awaitBody<Document>()

        val confirmationState = parsePageState(secondConfirmPage)

        // TODO Add check, that such order already exists in the system

        log.info("Sending second confirmation")
        val submitResponse2 = webClient.post()
            .uri("${sessionInfo.baseUrl}/queue/Rlist.aspx")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .body(BodyInserters.fromFormData(
                LinkedMultiValueMap<String, String>()
                    .apply {
                        add("__EVENTTARGET", "")
                        add("__EVENTARGUMENT", "")
                        add("__VIEWSTATE", confirmationState.viewState)
                        add("__EVENTVALIDATION", confirmationState.eventValidation)
                        add("ctl00\$MainContent\$ButtonQueue", "Записаться на прием")
                    }
            ))
            .retrieve()
            .toBodilessEntity()
            .awaitSingle()
        require(submitResponse2.statusCode == HttpStatus.FOUND)

        val calendarPath = submitResponse2.headers[HttpHeaders.LOCATION]?.first()
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
    }

    /**
     * Returns order page info, where you can see a calendar with available slots.
     */
    suspend fun fetchOrderInfo(baseUrl: String, order: Order): OrderInfo {
        log.info("Starting a new session...")
        
        // Get page to check status of order
        val orderPage = webClient.get()
            .uri("$baseUrl/queue/orderinfo.aspx")
            .retrieve()
            .awaitBody<Document>()

        val (eventValidation, viewState) = parsePageState(orderPage)
        val (sessionId, captchaCode) = parseSessionIdAndCaptureCode(baseUrl, orderPage)

        // Submit form
        log.info("Submitting form...")
        val confirmationPageResponse = webClient.post()
            .uri("$baseUrl/queue/orderinfo.aspx")
            .cookie(SESSION_ID_COOKIE, sessionId)
            .body(BodyInserters.fromFormData(
                LinkedMultiValueMap<String, String>()
                    .apply {
                        add("__EVENTTARGET", "")
                        add("__EVENTARGUMENT", "")
                        add("__VIEWSTATE", viewState)
                        add("__EVENTVALIDATION", eventValidation)
                        add("ctl00\$MainContent\$txtID", order.orderNumber)
                        add("ctl00\$MainContent\$txtUniqueID", order.code)
                        add("ctl00\$MainContent\$txtCode", captchaCode)
                        add("ctl00\$MainContent\$ButtonA", "Далее")
                        add("ctl00\$MainContent\$FeedbackClientID", "0")
                        add("ctl00\$MainContent\$FeedbackOrderID", "0")
                    }
            ))
            .retrieve()
            .toEntity(Document::class.java)
            .awaitSingle()

        require(confirmationPageResponse.statusCode == HttpStatus.OK)

        // Reparse some params
        val (eventValidation2, viewState2) = parsePageState(confirmationPageResponse.body!!)

        // Submit again:
        log.debug("Submitting form again...")
        val confirmationPageResponse2 = webClient.post()
            .uri("$baseUrl/queue/orderinfo.aspx")
            .cookie(SESSION_ID_COOKIE, sessionId)
            .body(BodyInserters.fromFormData(
                LinkedMultiValueMap<String, String>()
                    .apply {
                        add("__EVENTTARGET", "")
                        add("__EVENTARGUMENT", "")
                        add("__VIEWSTATE", viewState2)
                        add("__EVENTVALIDATION", eventValidation2)
                        add("ctl00\$MainContent\$ButtonB.x", "133")
                        add("ctl00\$MainContent\$ButtonB.y", "30")
                    }
            ))
            .retrieve()
            .toBodilessEntity()
            .awaitSingle()
        require(confirmationPageResponse2.statusCode == HttpStatus.FOUND)

        // Get redirect page
        val redirectPath = confirmationPageResponse2.headers[HttpHeaders.LOCATION]?.first()
        log.info("Check URL: $redirectPath")

        return OrderInfo(baseUrl, redirectPath!!, sessionId, order)
    }

    /**
     * Checks available slots on the calendar page.
     */
    suspend fun checkAvailableSlots(sessionInfo: SessionInfo, calendarPage: String) : Boolean {
        log.info("Checking an order at {}", sessionInfo)

        val orderPage = webClient.get()
            .uri("${sessionInfo.baseUrl}${calendarPage}")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .retrieve()
            .awaitBody<Document>()

        // TODO If session was broken than it will redirect to the login page
//        if (orderPage.selectFirst("input#ctl00\$MainContent\$txtEmail") != null) {
//            throw SessionException("Redirected to the start page")
//        }

        if (orderPage.selectFirst(":containsOwn(Ваша заявка заблокирована)") != null) {
            throw SessionException("Your order is blocked")
        }

        // Calendar widget should be on our target page
        if (orderPage.selectFirst("#ctl00_MainContent_Calendar") == null) {
            throw SessionException("Calendar widget is not on the page")
        }

        // Ok, seems like we're there.
        if (orderPage.selectFirst(":containsOwn(нет свободного времени)") != null) {
            return false
        } else {
            return true
        }
    }

    private suspend fun parseSessionIdAndCaptureCode(baseUrl: String, document: Document) : Triple<String, String, ByteArray> {
        // Parse captcha image src
        log.info("Parsing captcha image src...")
        val imageElement = document.selectFirst("div.inp img")
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
        val eventValidationElement = document.selectFirst("input#__EVENTVALIDATION")
        val eventValidationField = eventValidationElement?.attr("value") ?: ""

        val viewStateElement = document.selectFirst("input#__VIEWSTATE")
        val viewStateField = viewStateElement?.attr("value") ?: ""

        log.info("EventValidation code: {}", eventValidationField)
        log.info("ViewState: {}", viewStateField)

        return PageState(eventValidationField, viewStateField)
    }

    private suspend fun fetchImageAndSessionId(captchaUrl: String): Pair<ByteArray, String> {
        val response = webClient.get()
            .uri(captchaUrl)
            .retrieve()
            .toEntity(ByteArray::class.java)
            .awaitSingle()

        val setCookieHeader = response.headers[HttpHeaders.SET_COOKIE]?.first()
        val sessionId = setCookieHeader
            ?.split(";")
            ?.find { it.startsWith(SESSION_ID_COOKIE) }
            ?.split("=")
            ?.get(1)
            ?: throw RuntimeException("ASP.NET_SessionId header not found in cookies: $setCookieHeader")

        return Pair(response.body!!, sessionId)
    }

    private fun parseErrorText(document: Document): String? {
        val errorBlock = document.selectFirst("#ctl00_MainContent_lblCodeErr") ?: return null

        val children = errorBlock.childNodes()
        val stringBuilder = StringBuilder()
        for (child in children) {
            if (child is TextNode) {
                stringBuilder.append(child.text()).append("\n")
            }
        }
        return stringBuilder.trim().toString()
    }
}
