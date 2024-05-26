package com.github.russianconsulatebot.configurations

import com.pengrad.telegrambot.TelegramBot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramConfig(
    private val httpClientConfig: HttpClientConfig,
) {
    @Bean(destroyMethod = "shutdown")
    fun telegramBot(@Value("\${telegram.api_key:}") apiKey: String): TelegramBot {
        return TelegramBot.Builder(apiKey)
            .okHttpClient(httpClientConfig.okHttpClient())
            .build()
    }
}