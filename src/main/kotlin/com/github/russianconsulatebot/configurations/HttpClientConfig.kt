package com.github.russianconsulatebot.configurations

import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory
import org.springframework.boot.ssl.SslBundles
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

@Configuration
class HttpClientConfig(
    private val threadsConfig: ThreadsConfig,
    private val sslBundles: SslBundles,
) {
    @Bean
    fun okHttpClient(): OkHttpClient {
        val log = LoggerFactory.getLogger("ok-http-client")

        val httpLoggingInterceptor = HttpLoggingInterceptor { message -> log.trace(message) }
        httpLoggingInterceptor.level = HttpLoggingInterceptor.Level.BASIC

        val okHttpDispatcher = Dispatcher(threadsConfig.okHttpClientExecutorService())

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(httpLoggingInterceptor)
            .dispatcher(okHttpDispatcher)
            .build()
        return okHttpClient
    }

    // A separate http client to connect to the kdmid.ru consulate websites, because they used self-signed certificates
    // TODO find a way to use only one client
    @Bean
    fun consulateOkHttpClient(): OkHttpClient {
        val bundle = sslBundles.getBundle("kdmid_ru")
        val sslContext = bundle.createSslContext()

        val log = LoggerFactory.getLogger("ok-http-client")

        val httpLoggingInterceptor = HttpLoggingInterceptor { message -> log.trace(message) }
        httpLoggingInterceptor.level = HttpLoggingInterceptor.Level.BASIC

        val defaultHeadersInterceptor = Interceptor { chain ->
            val request: Request = chain.request()
            val newRequest: Request = request.newBuilder()
                .addHeader(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
                .addHeader("Accept-Language", "ru,ru-RU;q=0.9,en-US;q=0.8,en;q=0.7,zh;q=0.6")
                .addHeader("Cache-Control", "max-age=0")
                .addHeader("Connection", "keep-alive")
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .addHeader(
                    "sec-ch-ua",
                    "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\""
                )
                .addHeader("sec-ch-ua-mobile", "?0")
                .addHeader("sec-ch-ua-platform", "\"Windows\"")
                .build()

            chain.proceed(newRequest)
        }

        val okHttpDispatcher = Dispatcher(threadsConfig.okHttpClientExecutorService())

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(defaultHeadersInterceptor)
            .addInterceptor(httpLoggingInterceptor)
            .dispatcher(okHttpDispatcher)
            .sslSocketFactory(sslContext.socketFactory, bundle.managers.trustManagers[0] as X509TrustManager)
            .build()
        return okHttpClient
    }
}
