package com.github.russianconsulatebot.services

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.GetUpdates
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.max


@Service
class TelegramBotService(
    private val lastChecks: LastChecks,
    private val bot: TelegramBot,
) {
    private val log = LoggerFactory.getLogger(TelegramBotService::class.java)

    @PostConstruct
    fun start() {
        // TODO rewrite
        bot.setUpdatesListener(
            { updateList ->
                var lastUpdateId : Int = UpdatesListener.CONFIRMED_UPDATES_NONE
                for (update: Update in updateList) {
                    try {
                        processUpdate(update)
                    } catch (e: Exception) {
                        log.error("Failed to process an update: $update", e)
                    }
                    lastUpdateId = update.updateId()
                }
                lastUpdateId
            },
            { e ->
                val response : BaseResponse? = e.response()
                if (response != null) {
                    log.error("Got bad response from telegram ${response.errorCode()} ${response.description()}", e)
                } else {
                    log.error("Got exception from telegram bot", e)
                }
            }, GetUpdates()
                .timeout(30) // seconds
        )
    }

    @PreDestroy
    fun destroy() {
        bot.removeGetUpdatesListener()
    }

    private fun processUpdate(update: Update) {
        val message = update.message()

        when (message.text()) {
            "/start" -> {
                val request =
                    SendMessage(message.chat().id(), "Bot started. Your char_id is ${update.message()!!.senderChat().id()}")
                bot.execute(request)
            }

            "/log" -> {
                val checks = lastChecks.get().joinToString("\n") { "* $it" }
                // No more, that 4096 symbols in total, so keep last entries
                val trimmedText = checks.substring(max(checks.length - 4000, 0), checks.length)

                val request = SendMessage(message.chat().id(), "Last checks:\n$trimmedText")
                val result = bot.execute(request)
                if (!result.isOk) {
                    log.error("Got an error while sending a response to telegram API: {} {}", result.errorCode(), result.description())
                    bot.execute(SendMessage(message.chat().id(), "Error while sending a response to telegram API: $result"))
                }
            }

            "/ping" -> {
                val request = SendMessage(message.chat().id(), "Pong. Your char_id is ${update.message().senderChat().id()}")
                bot.execute(request)
            }

            else -> {
                val request = SendMessage(
                    message.chat().id(), "Unsupported message. " +
                        "Supported only: /start, /log, /ping"
                )
                val response = bot.execute(request)
                if (!response.isOk) {
                    log.error("Got an error while sending a response to telegram API: {}", response)
                }
            }
        }
    }

    fun sendMessage(chatId: Long, message: String) {
        val result = bot.execute(SendMessage(chatId, message))
        if (!result.isOk) {
            throw IllegalStateException("Failed to send a ${result.errorCode()}")
        }
    }
}