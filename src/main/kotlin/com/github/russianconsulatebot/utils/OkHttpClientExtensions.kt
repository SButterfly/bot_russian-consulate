package com.github.russianconsulatebot.utils

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An extension function to [Call] to execute requests in Async way.
 */
suspend fun Call.executeAsync(): Response {
    return suspendCancellableCoroutine { continuation ->
        this.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation {
            this.cancel()
        }
    }
}

fun Response.parseDocument() : Document = Jsoup.parse(this.body!!.byteStream(), null, "")