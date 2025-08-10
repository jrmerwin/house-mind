package com.example.ld1.model

import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(
    val id: Long = nextId(),
    val text: String,
    val fromUser: Boolean
) {
    companion object {
        private val counter = AtomicLong(System.currentTimeMillis())
        private fun nextId(): Long = counter.incrementAndGet()
    }
}

