package com.github.russianconsulatebot.configurations

import com.pengrad.telegrambot.TelegramBot
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Configuration
class TelegramConfig {

    @Bean(destroyMethod = "shutdown")
    fun telegramBot(@Value("\${telegram.api_key:}") apiKey: String) : TelegramBot {
        val log = LoggerFactory.getLogger("ok-http-client")

        val interceptor = HttpLoggingInterceptor { message -> log.trace(message) }
        interceptor.level = HttpLoggingInterceptor.Level.BASIC

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .dispatcher(Dispatcher(telegramUpdatesExecutor()))
            .build()

        return TelegramBot.Builder(apiKey)
            .okHttpClient(okHttpClient)
            .build()
    }

    @Bean
    fun telegramUpdatesExecutor(): ExecutorService {
        return Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "telegram-updates")}
    }

    @Bean
    fun telegramUpdatesDispatcher(): ExecutorCoroutineDispatcher {
        return telegramUpdatesExecutor().asCoroutineDispatcher()
    }
}