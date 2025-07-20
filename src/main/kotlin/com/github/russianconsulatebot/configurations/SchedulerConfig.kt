package com.github.russianconsulatebot.configurations

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration
class SchedulerConfig {

    @Bean
    fun checkSlotsExecutor(): ScheduledExecutorService {
        return Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "check-slots")}
    }

    @Bean
    fun checkSlotsDispatcher(): ExecutorCoroutineDispatcher {
        return checkSlotsExecutor().asCoroutineDispatcher()
    }
}