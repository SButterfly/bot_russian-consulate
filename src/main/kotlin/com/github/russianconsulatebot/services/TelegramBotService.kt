package com.github.russianconsulatebot.services

import com.github.russianconsulatebot.utils.executeAsync
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.GetUpdates
import com.pengrad.telegrambot.request.SendMessage
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.max

@Service
class TelegramBotService(
    private val bot: TelegramBot,
    private val lastChecks: LastChecks,
    private val telegramUpdatesDispatcher: ExecutorCoroutineDispatcher,
) {
    private val log = LoggerFactory.getLogger(TelegramBotService::class.java)

    @PostConstruct
    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        GlobalScope.launch(context = telegramUpdatesDispatcher) {
            var request = GetUpdates().timeout(30) // secs
            while (true) {
                val response = bot.executeAsync(request)

                if (!response.isOk || response.updates().isNullOrEmpty()) {
                    if (!response.isOk) {
                        log.error(
                            "GetUpdates failed with error_code {} {}",
                            response.errorCode(),
                            response.description()
                        )
                    }
                    continue
                }

                val updates = response.updates()
                for (update: Update in updates) {
                    try {
                        processUpdate(update)
                    } catch (e: Exception) {
                        log.error("Failed to process an update: $update", e)
                    }
                }

                val lastUpdateId = updates.last().updateId()
                request = request.offset(lastUpdateId + 1)
            }
        }
    }

    private suspend fun processUpdate(update: Update) {
        val message = update.message()

        when (message.text()) {
            "/start" -> {
                val request = SendMessage(
                    message.chat().id(),
                    "Bot started. Your char_id is ${update.message()!!.chat().id()}"
                )
                bot.executeAsync(request)
            }

            "/log" -> {
                val checks = lastChecks.get().joinToString("\n") { "* $it" }
                // No more, that 4096 symbols in total, so keep last entries
                val trimmedText = checks.substring(max(checks.length - 4000, 0), checks.length)

                val request = SendMessage(message.chat().id(), "Last checks:\n$trimmedText")
                val result = bot.executeAsync(request)
                if (!result.isOk) {
                    log.error(
                        "Got an error while sending a response to telegram API: {} {}",
                        result.errorCode(),
                        result.description()
                    )
                    bot.executeAsync(
                        SendMessage(
                            message.chat().id(),
                            "Error while sending a response to telegram API: $result"
                        )
                    )
                }
            }

            "/ping" -> {
                val request =
                    SendMessage(message.chat().id(), "Pong. Your char_id is ${update.message().chat().id()}")
                bot.executeAsync(request)
            }

            else -> {
                val request = SendMessage(
                    message.chat().id(), "Unsupported message. " +
                        "Supported only: /start, /log, /ping"
                )
                val response = bot.executeAsync(request)
                if (!response.isOk) {
                    log.error("Got an error while sending a response to telegram API: {}", response)
                }
            }
        }
    }

    suspend fun sendMessage(chatId: Long, message: String) = withContext(telegramUpdatesDispatcher) {
        val result = bot.executeAsync(SendMessage(chatId, message))
        if (!result.isOk) {
            throw IllegalStateException("Failed to send a ${result.errorCode()}")
        }
    }
}