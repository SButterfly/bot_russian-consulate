package com.github.russianconsulatebot.services

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN_V2
import com.github.kotlintelegrambot.logging.LogLevel
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

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
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Last checks:\n${lastChecks.get().joinToString("\n") { "* $it" }}"
                    )
                }

                text("ping") {
                    bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Pong")
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

    fun sendMessage(chatId: Long, message: String, isSilent: Boolean = false) {
        bot.sendMessage(
            ChatId.fromId(chatId),
            text = message,
            disableNotification = isSilent
        )
    }

    fun sendMarkdownMessage(chatId: Long, message: String, isSilent: Boolean = false) {
        bot.sendMessage(
            ChatId.fromId(chatId),
            text = message,
            disableNotification = isSilent,
            parseMode = MARKDOWN_V2,
        )
    }
}