package com.github.russianconsulatebot.exceptions

/**
 * Abstract exception for all communication errors.
 */
open class SessionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
}