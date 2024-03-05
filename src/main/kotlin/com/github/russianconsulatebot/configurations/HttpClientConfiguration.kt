package com.github.russianconsulatebot.configurations

import com.github.russianconsulatebot.webclient.DocumentHttpMessageReader
import io.netty.handler.logging.LogLevel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.logging.AdvancedByteBufFormat

@Configuration
class HttpClientConfiguration {

    @Bean
    fun webClient() : WebClient {
        // log request and response
        val httpClient = HttpClient.create()
            .wiretap(HttpClientConfiguration::class.java.canonicalName, LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeaders {
                it.set(
                    HttpHeaders.ACCEPT,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
                it.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br")
                it.set(HttpHeaders.ACCEPT_LANGUAGE, "ru,ru-RU;q=0.9,en-US;q=0.8,en;q=0.7,zh;q=0.6")
                it.set(HttpHeaders.CACHE_CONTROL, "max-age=0")
                it.set(HttpHeaders.CONNECTION, "keep-alive")
                it.set(
                    HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                it.set("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                it.set("sec-ch-ua-mobile", "?0")
                it.set("sec-ch-ua-platform", "\"Windows\"")
            }
            .codecs {
                it.customCodecs()
                    .register(DocumentHttpMessageReader())
            }
            .build()
    }
}