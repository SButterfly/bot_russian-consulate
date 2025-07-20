package com.github.russianconsulatebot.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Service to parse capture.
 * Uses https://huggingface.co/spaces/tomofi/EasyOCR ocr model.
 *
 * TODO replace http call with local parsing
 */
@Service
class AntiCaptureService(
    val webClient: WebClient,
    val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun solve(image: File): String {
        val mimeType = withContext(Dispatchers.IO) {
            Files.probeContentType(image.toPath())
        }
        val code = solve(image.readBytes(), mimeType)

        return code
    }

    /**
     * Solves capture on the image.
     */
    /* You can test the call manually with
        curl -X POST https://tomofi-easyocr.hf.space/api/predict/ \
            -H 'Content-Type: application/json' \
            -d "{\"data\": [\"data:image/jpeg;base64,$(base64 -i myFile.jpeg)\",[\"en\"]]}"
    */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun solve(byteArray: ByteArray, mimeType: String): String {
        log.info("Start solving captcha")

        val base64 = Base64.encode(byteArray)

        val response = webClient.post()
            .uri("https://tomofi-easyocr.hf.space/api/predict/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue("{\"data\":[\"data:$mimeType;base64,$base64\",[\"en\"]]}"))
            .retrieve()
            .awaitBody<String>()

        // The response is in the form
        // {
        //   "data": [
        //      "data:image/jpeg;base64,BASE64",
        //      {
        //      "headers": [
        //        1,
        //        2
        //      ],
        //      "data": [
        //        [
        //          "022880",
        //          0.8627397480794481
        //        ]
        //      ]
        //    }
        //  ],
        //  "durations": [
        //    2.23872971534729
        //  ],
        //  "avg_durations": [
        //    20.300637995726184
        //  ],
        //  "flag_index": null,
        //  "updated_state": null
        //}
        val jsonNode = objectMapper.readTree(response)
        val parsedJsonNode = (jsonNode.get("data") as ArrayNode).get(1)
        val valueJsonNode = ((parsedJsonNode["data"] as ArrayNode).get(0) as ArrayNode).get(0)
        val code = valueJsonNode.asText()

        log.info("Captcha solved. Code: {}", code)

        return code
    }
}