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
    fun businessLogicExecutor(): ScheduledExecutorService {
        // Experiment: Use one thread for all business process to use full power of COROUTINES !!!
        return Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "business-logic-thread")}
    }

    @Bean
    fun businessLogicCoroutineDispatcher(): ExecutorCoroutineDispatcher {
        return businessLogicExecutor().asCoroutineDispatcher()
    }
}