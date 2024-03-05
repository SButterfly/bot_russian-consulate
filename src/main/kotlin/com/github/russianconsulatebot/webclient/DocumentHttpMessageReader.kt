package com.github.russianconsulatebot.webclient

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.core.ResolvableType
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.http.codec.HttpMessageReader
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class DocumentHttpMessageReader : HttpMessageReader<Document> {

    private val clazz = Document::class.java

    override fun getReadableMediaTypes(): List<MediaType> {
        return listOf(MediaType.TEXT_HTML)
    }

    override fun canRead(elementType: ResolvableType, mediaType: MediaType?): Boolean {
        return MediaType.TEXT_HTML.includes(mediaType) && ResolvableType.forClass(clazz).isAssignableFrom(elementType)
    }

    override fun read(
        elementType: ResolvableType,
        message: ReactiveHttpInputMessage,
        hints: MutableMap<String, Any>
    ): Flux<Document> {
        throw UnsupportedOperationException("Reading a Flux is not supported");
    }

    override fun readMono(
        elementType: ResolvableType,
        message: ReactiveHttpInputMessage,
        hints: MutableMap<String, Any>
    ): Mono<Document> {
        return DataBufferUtils.join(message.body)
            .map { buffer ->
                val inputStream = buffer.asInputStream()
                Jsoup.parse(inputStream, null, "")
            }
    }
}