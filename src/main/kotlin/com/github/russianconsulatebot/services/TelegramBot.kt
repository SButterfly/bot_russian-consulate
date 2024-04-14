package com.github.russianconsulatebot.services

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.logging.LogLevel
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.max

@Service
class TelegramBot(
    @Value("\${telegram.api_key:}") val apiKey: String,
    private val lastChecks: LastChecks,
) {
    lateinit var bot: Bot
    private val log = LoggerFactory.getLogger(this::class.java)

    @PostConstruct
    fun start() {
        bot = bot {
            token = apiKey
            timeout = 30
            logLevel = LogLevel.Network.Body

            dispatch {
                command("start") {
                    val result = bot.sendMessage(
                        chatId = ChatId.fromId(update.message!!.chat.id),
                        text = "Bot started. Your char_id is ${update.message!!.chat.id}"
                    )

                    result.fold(
                        {
                            // do something here with the response
                        },
                        {
                            // do something with the error
                        },
                    )
                }

                text("log") {
                    val successful = lastChecks.successfulNumberOfAttempts
                    val totalNumber = lastChecks.totalNumberOfAttempts
                    val rate = successful * 100 / (totalNumber.takeIf { it != 0 } ?: 1)
                    val stats = "Successful attempts: $successful/$totalNumber ($rate%)"

                    val checks = lastChecks.get().joinToString("\n") { "* $it" }
                    // No more, that 4096 symbols in total, so keep last entries
                    val trimmedText = checks.substring(max(checks.length - 3900, 0), checks.length)

                    val result = bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Last checks:\n$trimmedText\n\n$stats"
                    )
                    result.fold(
                        {
                            // do something here with the response
                        },
                        {
                            log.error("Got an error while sending a response to telegram API: {}", it)
                            // do something with the error
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Error while sending a response to telegram API: $it"
                            )
                        },
                    )
                }

                text("ping") {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Pong. Your char_id is ${update.message!!.chat.id}")
                }

                telegramError {
                    log.error("Error in telegram bot: {}", error.getErrorMessage())
                }
            }
        }

        if (apiKey.isNotBlank()) {
            bot.startPolling()
        }
    }

    @PreDestroy
    fun destroy() {
        if (apiKey.isNotBlank()) {
            bot.stopPolling()
        }
    }

    suspend fun sendMessage(chatId: Long, message: String, isSilent: Boolean = false) {
        bot.sendMessage(
            ChatId.fromId(chatId),
            text = message,
            disableNotification = isSilent
        )
    }
}