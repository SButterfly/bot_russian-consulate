package com.github.russianconsulatebot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.config.EnableWebFlux

@SpringBootApplication
@EnableScheduling
class RussianConsulateBotApplication

fun main(args: Array<String>) {
	runApplication<RussianConsulateBotApplication>(*args)
}
