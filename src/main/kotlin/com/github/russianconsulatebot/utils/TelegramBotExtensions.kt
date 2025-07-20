package com.github.russianconsulatebot.utils

import com.pengrad.telegrambot.Callback
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An extension function to [TelegramBot] to execute requests in Async way.
 *
 * Since [TelegramBot] uses [OkHttpClient] client, async is implemented by delegating all requests to a separate
 * [OkHttpClient.dispatcher].
 */
suspend inline fun <T : BaseRequest<T, R>, R : BaseResponse> TelegramBot.executeAsync(request: T): R {
    return suspendCancellableCoroutine { continuation ->
        val cancellable = this.execute(request, object : Callback<T, R> {
            override fun onResponse(request: T, response: R) {
                continuation.resume(response)
            }

            override fun onFailure(request: T, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation {
            cancellable.cancel()
        }
    }
}