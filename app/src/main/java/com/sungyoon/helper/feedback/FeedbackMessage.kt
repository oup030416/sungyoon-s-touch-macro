package com.sungyoon.helper.feedback

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class FeedbackMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val appVersion: String,
    val versionCode: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun validate() {
        require(UUID_PATTERN.matches(id))
        require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
        require(Regex("[0-9A-Za-z._+-]{1,40}").matches(appVersion) && versionCode > 0 && createdAt in 1..8_640_000_000_000_000L)
    }

    companion object {
        const val MAX_TEXT_LENGTH = 10_000
        const val MAX_PENDING = 100
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

class FeedbackQueueFullException : IllegalStateException("Feedback queue is full")
