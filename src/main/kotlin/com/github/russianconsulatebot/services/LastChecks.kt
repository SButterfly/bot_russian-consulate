package com.github.russianconsulatebot.services

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

private const val CAPACITY = 60

@Service
class LastChecks {
    private val queue: BlockingQueue<String> = ArrayBlockingQueue(CAPACITY)

    fun push(msg: String) {
        while (queue.size >= CAPACITY) {
            queue.remove()
        }
        queue.add("Check at ${Instant.now()}: $msg")
    }

    fun get(): List<String> {
        return queue.toList()
    }
}