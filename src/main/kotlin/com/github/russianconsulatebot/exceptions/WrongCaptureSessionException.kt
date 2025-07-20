package com.github.russianconsulatebot.exceptions

import com.github.kotlintelegrambot.entities.Message

/**
 * Thrown when capture is wrong.
 */
class WrongCaptureSessionException(message: String) : SessionException(message) {
}