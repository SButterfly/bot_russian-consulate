package com.github.russianconsulatebot.configurations

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration
class ThreadsConfig {

    /**
     * Ok http client executor service for async execution of requests.
     */
    @Bean
    fun okHttpClientExecutorService(): ExecutorService {
        // Experiment: Use virtual threads for ok-http-dispatcher
        val threadFactory = Thread.ofVirtual()
            .name("ok-http-dispatcher-", 0)
            .factory()
        return Executors.newCachedThreadPool(threadFactory)
    }

    /**
     * For scheduled tasks to check current state of the system.
     */
    @Bean
    fun businessLogicExecutor(): ScheduledExecutorService {
        // Experiment: Use one thread for all business process to use full power of COROUTINES !!!
        val threadFactory = Thread.ofPlatform()
            .name("business-logic-thread-", 0)
            .factory()
        return Executors.newSingleThreadScheduledExecutor(threadFactory)
    }

    @Bean
    fun businessLogicCoroutineDispatcher(): ExecutorCoroutineDispatcher {
        return businessLogicExecutor().asCoroutineDispatcher()
    }
}