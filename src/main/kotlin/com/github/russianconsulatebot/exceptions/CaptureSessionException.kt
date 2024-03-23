package com.github.russianconsulatebot.exceptions

import java.awt.image.BufferedImage

/**
 * Thrown when capture is wrong.
 */
class CaptureSessionException(message: String, val image: BufferedImage) : SessionException(message)