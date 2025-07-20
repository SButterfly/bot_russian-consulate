package com.github.russianconsulatebot.utils

import com.pengrad.telegrambot.Callback
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend inline fun <T : BaseRequest<T, R>, R : BaseResponse> TelegramBot.executeAsync(request: BaseRequest<T, R>): R {
    return suspendCancellableCoroutine { continuation ->
        // TODO what should I do with generics to avoid 'as'?
        val cancellable = this.execute(request as T, object : Callback<T, R> {
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