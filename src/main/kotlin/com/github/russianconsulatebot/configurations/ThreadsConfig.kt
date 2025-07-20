package com.github.russianconsulatebot.configurations

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class ThreadsConfig {

    /**
     * Ok http client executor service for async execution of requests.
     */
    @Bean
    fun okHttpClientExecutorService(): ExecutorService {
        val number = AtomicInteger()
        return Executors.newCachedThreadPool { r -> Thread(r, "ok-http-dispatcher-" + number.getAndIncrement())}
    }

    /**
     * For scheduled tasks to check current state of the system.
     */
    @Bean
    fun checkSlotsExecutor(): ScheduledExecutorService {
        // No parallelization is expected on the check slots, thus we use a single thread
        return Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "check-slots")}
    }

    @Bean
    fun checkSlotsDispatcher(): ExecutorCoroutineDispatcher {
        return checkSlotsExecutor().asCoroutineDispatcher()
    }

    /**
     * For background checks of the telegram updates.
     */
    @Bean
    fun telegramUpdatesExecutor(): ExecutorService {
        return Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "telegram-bot")}
    }

    @Bean
    fun telegramUpdatesDispatcher(): ExecutorCoroutineDispatcher {
        return telegramUpdatesExecutor().asCoroutineDispatcher()
    }
}