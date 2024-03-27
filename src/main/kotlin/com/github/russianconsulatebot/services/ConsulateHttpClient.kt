package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.exceptions.CaptureSessionException
import com.github.russianconsulatebot.exceptions.DataErrorSessionException
import com.github.russianconsulatebot.exceptions.ExpiredSessionException
import com.github.russianconsulatebot.exceptions.SessionException
import com.github.russianconsulatebot.exceptions.TooManyQuestionsSessionException
import com.github.russianconsulatebot.services.dto.Order
import com.github.russianconsulatebot.services.dto.PageState
import com.github.russianconsulatebot.services.dto.SessionInfo
import com.github.russianconsulatebot.services.dto.UserInfo
import com.github.russianconsulatebot.utils.awaitBodyEntity
import kotlinx.coroutines.reactive.awaitSingle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import org.springframework.web.reactive.function.client.awaitBody

const val SESSION_ID_COOKIE = "ASP.NET_SessionId"

/**
 * A client that helps to navigate the website.
 */
@Service
class ConsulateHttpClient(
    private val webClient: WebClient,
    private val captureParserService: CaptureParserService,
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
        val (sessionId, image) = captureParserService.parseSessionIdAndCaptureImage(baseUrl, document)
        val captchaCode = antiCaptureService.solve(image)

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
        val errorTextElement = menuPage.selectFirst("#ctl00_MainContent_lblCodeErr")
        if (errorTextElement != null) {
            val errorText = errorTextElement.childTexts().joinToString("\n")
            if (errorText.contains("Символы с картинки введены не правильно")) {
                throw CaptureSessionException("Capture '$captchaCode' was wrong", image)
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
     * Fills in any forms to get to the calendar or order page.
     * Returns url path to the consulate action. e.g. TODO
     */
    suspend fun passToOrderPage(sessionInfo: SessionInfo, consulateType: String): String {
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
                        add("__PREVIOUSPAGE", orderPageState.previousPage)
                        add("__EVENTVALIDATION", orderPageState.eventValidation)
                        add("ctl00\$MainContent\$RList", "$consulateType;PSSP")
                        add("ctl00\$MainContent\$CheckBoxID", "on")
                        add("ctl00\$MainContent\$ButtonA", "Далее")
                    }
            ))
            .retrieve()
            .awaitBodyEntity<Document>()

        // It should redirect to the page with Confirmation
        if (submitResponse.statusCode != HttpStatus.FOUND) {
            if (submitResponse.body!!
                    .selectFirst(":containsOwn(Превышено ограничение на количество вопросов)") != null) {
                throw TooManyQuestionsSessionException("To many questions")
            }
            throw SessionException("The response should be redirected to another page")
        }

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
                        add("__PREVIOUSPAGE", confirmationState.previousPage)
                        add("__EVENTVALIDATION", confirmationState.eventValidation)
                        add("ctl00\$MainContent\$ButtonQueue", "Записаться на прием")
                    }
            ))
            .retrieve()
            .awaitBodilessEntity()

        require(submitResponse2.statusCode == HttpStatus.FOUND)

        val calendarPath = submitResponse2.headers[HttpHeaders.LOCATION]?.first()
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
    }

    /**
     * Starts a session and returns a path to calendar page.
     */
    suspend fun startCheckingAndOrder(baseUrl: String, order: Order): String {
        log.info("Starting a new session...")
        
        // Get page to check status of order
        val orderPage = webClient.get()
            .uri("$baseUrl/queue/orderinfo.aspx")
            .retrieve()
            .awaitBody<Document>()

        val (eventValidation, viewState) = parsePageState(orderPage)
        val (sessionId, image) = captureParserService.parseSessionIdAndCaptureImage(baseUrl, orderPage)
        val captchaCode = antiCaptureService.solve(image)

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
            .awaitBodyEntity<Document>()

        require(confirmationPageResponse.statusCode == HttpStatus.OK)

        val confirmationPage = confirmationPageResponse.body!!
        if (confirmationPage.selectFirst(":containsOwn(Ваша заявка требует  подтверждения)") != null) {
            throw SessionException("The order is not confirmed yet")
        }

        // Reparse some params
        val (eventValidation2, viewState2) = parsePageState(confirmationPage)

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

        val calendarPath = confirmationPageResponse2.headers[HttpHeaders.LOCATION]?.first()
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
    }

    /**
     * Parses order from the order page.
     */
    suspend fun parseOrder(sessionInfo: SessionInfo, orderPagePath: String): Order {
        log.info("Checking an order at {}", sessionInfo)

        val orderPage = webClient.get()
            .uri("${sessionInfo.baseUrl}${orderPagePath}")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .retrieve()
            .awaitBody<Document>()

        val orderInfo = orderPage.selectFirst(":containsOwn(Вы записаны в список ожидания по вопросу)")
        if (orderInfo == null) {
            throw SessionException("Can't find order info in the page")
        }

        val orderIdRegex = Regex("Номер заявки - (\\d+)")
        val codeRegex = Regex("Защитный код - (\\w+)")

        val childTexts = orderInfo.childTexts()
        val orderId = childTexts.asSequence()
            .mapNotNull { orderIdRegex.find(it) }
            .map { it.groupValues[1] }
            .firstOrNull()
        val code = childTexts.asSequence()
            .mapNotNull { codeRegex.find(it) }
            .map { it.groupValues[1] }
            .firstOrNull()

        if (orderId == null || code == null) {
            throw SessionException("Failed to parse order id or code in the text '${childTexts}'")
        }

        return Order(orderId, code)
    }

    /**
     * Checks available slots on the calendar page.
     */
    suspend fun checkAvailableSlots(sessionInfo: SessionInfo, calendarPagePath: String) : Boolean {
        log.info("Checking an order at {}", sessionInfo)

        val orderResponse = webClient.get()
            .uri("${sessionInfo.baseUrl}${calendarPagePath}")
            .cookie(SESSION_ID_COOKIE, sessionInfo.sessionId)
            .retrieve()
            .awaitBodyEntity<Document>()

        // The page can return redirected status to the start page
        if (orderResponse.statusCode == HttpStatus.FOUND) {
            throw ExpiredSessionException("Redirected to the start page")
        }

        val orderPage = orderResponse.body!!

        // If the page was redirected to the "login" page we will see this text block
        if (orderPage.selectFirst("#ctl00_MainContent_txtEmail") != null) {
            throw ExpiredSessionException("Redirected to the start page")
        }

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

    private fun parsePageState(document: Document) : PageState {
        log.info("Parsing state...")
        val eventValidationElement = document.selectFirst("input#__EVENTVALIDATION")
        val eventValidationField = eventValidationElement?.attr("value") ?: ""

        val viewStateElement = document.selectFirst("input#__VIEWSTATE")
        val viewStateField = viewStateElement?.attr("value") ?: ""

        val prevPageElement = document.selectFirst("input#__PREVIOUSPAGE")
        val prevPageField = prevPageElement?.attr("value") ?: ""

        log.info("EventValidation code: {}", eventValidationField)
        log.info("ViewState: {}", viewStateField)
        log.info("Previous page: {}", prevPageField)

        return PageState(eventValidationField, viewStateField, prevPageField)
    }

    /**
     * Returns a list of all child text on the element.
     */
    private fun Element.childTexts(): List<String> {
        val children = this.childNodes()
        val result = mutableListOf<String>()
        for (child in children) {
            if (child is TextNode) {
                result.add(child.text())
            }
        }
        return result
    }
}
