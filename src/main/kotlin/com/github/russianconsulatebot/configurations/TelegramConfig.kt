package com.github.russianconsulatebot.configurations

import com.pengrad.telegrambot.TelegramBot
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class TelegramConfig(
    private val threadsConfig: ThreadsConfig
) {

    @Bean(destroyMethod = "shutdown")
    fun telegramBot(@Value("\${telegram.api_key:}") apiKey: String): TelegramBot {
        val log = LoggerFactory.getLogger("ok-http-client")

        val interceptor = HttpLoggingInterceptor { message -> log.trace(message) }
        interceptor.level = HttpLoggingInterceptor.Level.BASIC

        val okHttpDispatcher = Dispatcher(threadsConfig.okHttpClientExecutorService())

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .dispatcher(okHttpDispatcher)
            .build()

        return TelegramBot.Builder(apiKey)
            .okHttpClient(okHttpClient)
            .build()
    }
}