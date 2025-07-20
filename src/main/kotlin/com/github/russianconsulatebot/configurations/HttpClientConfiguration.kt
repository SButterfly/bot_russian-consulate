package com.github.russianconsulatebot.configurations

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

@Configuration
class HttpClientConfiguration {

    @Bean
    fun webClient() : WebClient {
        return WebClient.create();
    }

    @Bean
    fun createClient(): HttpClient {
        return HttpClient(CIO) {
            install(Logging)

            engine {
                https {
                    // This will not check SSL certificate at all, but that is actually not necessary
                    trustManager = object : X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOf()
                        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
                        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
                    }
                }
            }
            // Configure the User-Agent header
            defaultRequest {
                headers {
                    append(
                        HttpHeaders.Accept,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                    )
                    append(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                    append(HttpHeaders.AcceptLanguage, "ru,ru-RU;q=0.9,en-US;q=0.8,en;q=0.7,zh;q=0.6")
                    append(HttpHeaders.CacheControl, "max-age=0")
                    append(HttpHeaders.Connection, "keep-alive")
                    append(HttpHeaders.Host, "bangkok.kdmid.ru")
                    append(
                        HttpHeaders.UserAgent,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    append("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                    append("sec-ch-ua-mobile", "?0")
                    append("sec-ch-ua-platform", "\"Windows\"")
                }
            }
        }
    }
}