package com.github.russianconsulatebot.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.github.russianconsulatebot.exceptions.CaptureSessionException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
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
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Solves capture on the image.
     */
    suspend fun solve(image: BufferedImage): String {
        log.info("Start solving captcha")
        val mimeType = "image/jpeg"
        val base64 = toBase64(image, "jpeg")

        /* You can test the call manually with
            curl -X POST https://tomofi-easyocr.hf.space/api/predict/ \
                -H 'Content-Type: application/json' \
                -d "{\"data\": [\"data:image/jpeg;base64,$(base64 -i myFile.jpeg)\",[\"en\"]]}"
        */

        val response = webClient.post()
            .uri("https://tomofi-easyocr.hf.space/api/predict/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue("{\"data\":[\"data:$mimeType;base64,$base64\",[\"en\"]]}"))
            .retrieve()
            .awaitBody<JsonNode>()

        val code = parseResponse(response, image)
        log.info("Code was post processed. Code: {}", code)
        return code
    }

    private fun parseResponse(jsonNode: JsonNode, image: BufferedImage): String {
        // The response is in the form
        // {
        //  "data": [
        //    "data:image/jpeg;base64,BASE64",
        //    {
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
        // }
        val dataArray = jsonNode.get("data") as ArrayNode
        if (dataArray.size() == 1) {
            throw CaptureSessionException("Failed to parse symbols in capture. No array with data.", image)
        }
        val resultsArrayNode = dataArray.get(1)["data"] as ArrayNode
        if (resultsArrayNode.isEmpty) {
            throw CaptureSessionException("Failed to parse symbols in capture. No probabilities", image)
        }

        val probabilitiesList = resultsArrayNode.map { Pair(it[0].asText(), it[1].doubleValue()) }
        log.debug("Got list with probabilities: {}", probabilitiesList)

        val codeCandidate = probabilitiesList
            // sort by the length of the result, because the right answer it a bigger number
            // and then by probability: higher -> better
            .sortedWith(
                compareByDescending<Pair<String, Double>> { pair -> pair.first.length }
                    .thenByDescending { pair -> pair.second }
            )
            .map { pair -> pair.first }
            .first()

        // Postprocess

        // 1. keep only numbers
        val stringBuilder = StringBuilder()
        for (c in codeCandidate) {
            if (c.isDigit()) {
                stringBuilder.append(c)
            }
        }

        if (stringBuilder.length != 6) {
            throw CaptureSessionException("Expected to parse 6 symbols on capture, got '$stringBuilder'", image)
        }

        return stringBuilder.toString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun toBase64(image: BufferedImage, formatName: String): String {
        val arrayOutputStream = ByteArrayOutputStream()
        ImageIO.write(image, formatName, arrayOutputStream)
        val base64 = Base64.encode(arrayOutputStream.toByteArray())
        return base64
    }
}