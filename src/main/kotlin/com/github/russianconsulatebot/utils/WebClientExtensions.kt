package com.github.russianconsulatebot.utils

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient

suspend inline fun <reified T : Any> WebClient.ResponseSpec.awaitBodyEntity(): ResponseEntity<T> =
    this.toEntity(T::class.java).awaitSingle()