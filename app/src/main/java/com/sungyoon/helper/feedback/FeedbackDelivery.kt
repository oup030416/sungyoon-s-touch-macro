package com.sungyoon.helper.feedback

/** Transport success is the sole acknowledgement path. Scheduling never removes an outbox record. */
class FeedbackDelivery(
    private val outbox: FeedbackOutbox,
    private val transport: FeedbackTransport
) {
    suspend fun deliver(id: String): Boolean {
        val message = outbox.pending().firstOrNull { it.id == id } ?: return true
        if (!transport.send(message)) return false
        outbox.acknowledge(id)
        return true
    }
}
