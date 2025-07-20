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
import com.github.russianconsulatebot.utils.PageParser
import com.github.russianconsulatebot.utils.Slot
import com.github.russianconsulatebot.utils.executeAsync
import com.github.russianconsulatebot.utils.parseDocument
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service


const val SESSION_ID_COOKIE = "ASP.NET_SessionId"

/**
 * A client that helps to navigate the website.
 */
@Service
class ConsulateHttpClient(
    private val consulateOkHttpClient: OkHttpClient,
    private val captureParserService: CaptureParserService,
    private val antiCaptureService: AntiCaptureService,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Starts a new session.
     * Logins in the system and returns session information.
     */
    suspend fun startSession(baseUrl: String, userInfo: UserInfo): SessionInfo {
        // Get initial page with captcha
        val document = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("$baseUrl/queue/visitor.aspx")
                    .build()
            )
            .executeAsync()
            .parseDocument()

        // Parse the page
        val pageState = parsePageState(document)
        val (sessionId, image) = captureParserService.parseSessionIdAndCaptureImage(baseUrl, document)
        val captchaCode = antiCaptureService.solve(image)

        // Submit form
        log.info("Submitting form with userInfo=$userInfo, captureCode=$captchaCode, and sessionId=$sessionId")
        val menuPage = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("$baseUrl/queue/visitor.aspx")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=$sessionId")
                    .post(
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("__EVENTTARGET", "")
                            .addFormDataPart("__EVENTARGUMENT", "")
                            .addFormDataPart("__VIEWSTATE", pageState.viewState)
                            .addFormDataPart("__EVENTVALIDATION", pageState.eventValidation)
                            .addFormDataPart("ctl00\$MainContent\$txtFam", userInfo.firstName)
                            .addFormDataPart("ctl00\$MainContent\$txtIm", userInfo.secondName)
                            .addFormDataPart("ctl00\$MainContent\$txtOt", userInfo.patronymic ?: "")
                            .addFormDataPart("ctl00\$MainContent\$txtTel", userInfo.phoneNumber)
                            .addFormDataPart("ctl00\$MainContent\$txtEmail", userInfo.email)
                            .addFormDataPart("ctl00\$MainContent\$DDL_Day", userInfo.dateOfBirthStr)
                            .addFormDataPart("ctl00\$MainContent\$DDL_Month", userInfo.monthOfBirthStr)
                            .addFormDataPart("ctl00\$MainContent\$TextBox_Year", userInfo.yearOfBirthStr)
                            .addFormDataPart("ctl00\$MainContent\$DDL_Mr", userInfo.title.name)
                            .addFormDataPart("ctl00\$MainContent\$txtCode", captchaCode)
                            .addFormDataPart("ctl00\$MainContent\$ButtonA", "Далее")
                            .build()
                    )
                    .build()
            )
            .executeAsync()
            .parseDocument()

        // Parse menu page
        val errorTextElement = menuPage.selectFirst("#ctl00_MainContent_lblCodeErr")
        if (errorTextElement != null) {
            val errorText = errorTextElement.childTexts().joinToString("\n")
            if (errorText.contains("Символы с картинки введены неправильно")) {
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
     * Pass to the calendar page with availabilities.
     *
     * @return calendar path e.g. /queue/SPCalendar.aspx
     */
    suspend fun passToOrderPage(sessionInfo: SessionInfo, consulateType: String): String {
        log.info("Start passing forms for $consulateType and sessionInfo $sessionInfo")

        // Get the first page with option, which passport type we should choose
        val orderPage = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}/queue/Rlist.aspx?nm=$consulateType")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .build()
            )
            .executeAsync()
            .parseDocument()

        val orderPageState = parsePageState(orderPage)

        // Submit the chosen option
        val submitResponse = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}/queue/Rlist.aspx?nm=$consulateType")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .post(
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("__EVENTTARGET", "")
                            .addFormDataPart("__EVENTARGUMENT", "")
                            .addFormDataPart("__VIEWSTATE", orderPageState.viewState)
                            .addFormDataPart("__PREVIOUSPAGE", orderPageState.previousPage)
                            .addFormDataPart("__EVENTVALIDATION", orderPageState.eventValidation)
                            .addFormDataPart("ctl00\$MainContent\$ButtonQueue", "Записаться на прием")
                            .build()
                    )
                    .build()
            )
            .executeAsync()

        // It should redirect to the page with Calendar
        if (submitResponse.code != HttpStatus.FOUND.value()) {
            val submitDocument = submitResponse.parseDocument()
            if (submitDocument
                    .selectFirst(":containsOwn(Превышено ограничение на количество вопросов)") != null
            ) {
                throw TooManyQuestionsSessionException("To many questions")
            }
            throw SessionException("The response should be redirected to another page")
        }

        val calendarPath = submitResponse.headers[HttpHeaders.LOCATION]
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
    }

    /**
     * A special method to pass to form for passport on 10 years.
     *
     * @return calendar path e.g. /queue/SPCalendar.aspx
     */
    suspend fun passToPassportOrderPage(sessionInfo: SessionInfo, consulateType: String): String {
        log.info("Start passing forms for $consulateType and sessionInfo $sessionInfo")

        // Get the first page with option, which passport type we should choose
        val orderPage = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}/queue/bpssp.aspx?nm=$consulateType")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .build()
            )
            .executeAsync()
            .parseDocument()

        val orderPageState = parsePageState(orderPage)

        // Submit with adult params
        val submitResponse = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}/queue/bpssp.aspx?nm=$consulateType")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .post(
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("__EVENTTARGET", "")
                            .addFormDataPart("__EVENTARGUMENT", "")
                            .addFormDataPart("__VIEWSTATE", orderPageState.viewState)
                            .addFormDataPart("__PREVIOUSPAGE", orderPageState.previousPage)
                            .addFormDataPart("__EVENTVALIDATION", orderPageState.eventValidation)
                            .addFormDataPart("ctl00\$MainContent\$RList", "$consulateType;PSSP")
                            .addFormDataPart("ctl00\$MainContent\$CheckBoxID", "on")
                            .addFormDataPart("ctl00\$MainContent\$ButtonA", "Далее")
                            .build()
                    )
                    .build()
            )
            .executeAsync()

        // It should redirect to the page with Confirmation
        if (submitResponse.code != HttpStatus.FOUND.value()) {
            val submitDocument = submitResponse.parseDocument()
            if (submitDocument
                    .selectFirst(":containsOwn(Превышено ограничение на количество вопросов)") != null
            ) {
                throw TooManyQuestionsSessionException("To many questions")
            }
            throw SessionException("The response should be redirected to another page")
        }

        val secondConfirmPage = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}/queue/Rlist.aspx")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .build()
            )
            .executeAsync()
            .parseDocument()

        val confirmationState = parsePageState(secondConfirmPage)

        // TODO Add check, that such order already exists in the system

        log.info("Sending second confirmation")
        val submitResponse2 = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}/queue/Rlist.aspx")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .post(
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("__EVENTTARGET", "")
                            .addFormDataPart("__EVENTARGUMENT", "")
                            .addFormDataPart("__VIEWSTATE", confirmationState.viewState)
                            .addFormDataPart("__PREVIOUSPAGE", confirmationState.previousPage)
                            .addFormDataPart("__EVENTVALIDATION", confirmationState.eventValidation)
                            .addFormDataPart("ctl00\$MainContent\$ButtonQueue", "Записаться на прием")
                            .build()
                    )
                    .build()
            )
            .executeAsync()

        require(submitResponse2.code == HttpStatus.FOUND.value())

        val calendarPath = submitResponse2.headers[HttpHeaders.LOCATION]
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
    }

    /**
     * Starts a session and returns a path to calendar page.
     */
    suspend fun startCheckingAndOrder(baseUrl: String, order: Order): String {
        log.info("Starting a new session...")

        // Get page to check status of order
        val orderPage = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("$baseUrl/queue/orderinfo.aspx")
                    .build()
            )
            .executeAsync()
            .parseDocument()

        val (eventValidation, viewState) = parsePageState(orderPage)
        val (sessionId, image) = captureParserService.parseSessionIdAndCaptureImage(baseUrl, orderPage)
        val captchaCode = antiCaptureService.solve(image)

        // Submit form
        log.info("Submitting form...")
        val confirmationPageResponse = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("$baseUrl/queue/orderinfo.aspx")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionId}")
                    .post(
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("__EVENTARGUMENT", "")
                            .addFormDataPart("__VIEWSTATE", viewState)
                            .addFormDataPart("__EVENTVALIDATION", eventValidation)
                            .addFormDataPart("ctl00\$MainContent\$txtID", order.orderNumber)
                            .addFormDataPart("ctl00\$MainContent\$txtUniqueID", order.code)
                            .addFormDataPart("ctl00\$MainContent\$txtCode", captchaCode)
                            .addFormDataPart("ctl00\$MainContent\$ButtonA", "Далее")
                            .addFormDataPart("ctl00\$MainContent\$FeedbackClientID", "0")
                            .addFormDataPart("ctl00\$MainContent\$FeedbackOrderID", "0")
                            .build()
                    )
                    .build()
            )
            .executeAsync()

        require(confirmationPageResponse.code == HttpStatus.OK.value())

        val confirmationPage = confirmationPageResponse.parseDocument()
        if (confirmationPage.selectFirst(":containsOwn(Ваша заявка требует  подтверждения)") != null) {
            throw SessionException("The order is not confirmed yet")
        }

        // Reparse some params
        val (eventValidation2, viewState2) = parsePageState(confirmationPage)

        // Submit again:
        log.debug("Submitting form again...")
        val confirmationPageResponse2 = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("$baseUrl/queue/orderinfo.aspx")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionId}")
                    .post(
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("__EVENTTARGET", "")
                            .addFormDataPart("__EVENTARGUMENT", "")
                            .addFormDataPart("__VIEWSTATE", viewState2)
                            .addFormDataPart("__EVENTVALIDATION", eventValidation2)
                            .addFormDataPart("ctl00\$MainContent\$ButtonB.x", "133")
                            .addFormDataPart("ctl00\$MainContent\$ButtonB.y", "30")
                            .build()
                    )
                    .build()
            )
            .executeAsync()
        require(confirmationPageResponse2.code == HttpStatus.FOUND.value())

        val calendarPath = confirmationPageResponse2.headers[HttpHeaders.LOCATION]
        log.info("Calendar path: $calendarPath")

        return calendarPath!!
    }

    /**
     * Parses order from the order page.
     */
    suspend fun parseOrder(sessionInfo: SessionInfo, orderPagePath: String): Order {
        log.info("Checking an order at {}", sessionInfo)

        val orderPage = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}${orderPagePath}")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .build()
            )
            .executeAsync()
            .parseDocument()

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
    suspend fun checkSlots(sessionInfo: SessionInfo, calendarPagePath: String): List<Slot> {
        log.info("Checking an order at {}", sessionInfo)
        val orderResponse = consulateOkHttpClient
            .newCall(
                Request.Builder()
                    .url("${sessionInfo.baseUrl}${calendarPagePath}")
                    .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                    .build()
            )
            .executeAsync()

        // The page can return redirected status to the start page
        if (orderResponse.code == HttpStatus.FOUND.value()) {
            throw ExpiredSessionException("Redirected to the start page")
        }

        // If the page was redirected to the "login" page we will see this text block
        val orderPage = orderResponse.parseDocument()
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
            return emptyList()
        } else {
            log.info("Found available slots! Try to parse them")
            val slots = parseSlots(sessionInfo, calendarPagePath, orderPage)
            return slots
        }
    }

    private suspend fun parseSlots(sessionInfo: SessionInfo, calendarPagePath: String, calendarPage: Document): List<Slot> {
        // try to parse a page
        val calendarInfo = PageParser.parseCalendar(calendarPage)
        val calendarPageState = parsePageState(calendarPage)
        log.info("Parsed a page and got this calendar info: {}", calendarInfo)

        // create result list
        val resultSlots = mutableListOf<Slot>()

        // process first day
        val selectedDay = calendarInfo.selectedDay
        val selectedDaySlots = PageParser.parseSlots(calendarPage)
        resultSlots.addAll(selectedDaySlots)
        log.debug("Found {} slots in the selected day: {}", selectedDaySlots.size, selectedDay.date)

        // process other days
        val availableDays = calendarInfo.availableDays
        for (day in availableDays) {
            if (day == selectedDay) {
                continue
            }

            log.debug("Requesting slots for {} day", day.date)
            val dayPage = consulateOkHttpClient
                .newCall(
                    Request.Builder()
                        .url("${sessionInfo.baseUrl}${calendarPagePath}")
                        .addHeader(HttpHeaders.COOKIE, "$SESSION_ID_COOKIE=${sessionInfo.sessionId}")
                        .post(
                            MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("__EVENTTARGET", day.eventTarget)
                                .addFormDataPart("__EVENTARGUMENT", day.eventArgument)
                                .addFormDataPart("__VIEWSTATE", calendarPageState.viewState)
                                .addFormDataPart("__EVENTVALIDATION", calendarPageState.eventValidation)
                                .build()
                        )
                        .build()
                )
                .executeAsync()
                .parseDocument()

            val slots = PageParser.parseSlots(dayPage)
            resultSlots.addAll(slots)
            log.debug("Found {} slots in the day: {}", slots.size, day.date)
        }
        return resultSlots
    }

    private fun parsePageState(document: Document): PageState {
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
