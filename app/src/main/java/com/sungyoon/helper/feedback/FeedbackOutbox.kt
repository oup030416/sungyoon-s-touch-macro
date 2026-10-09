package com.sungyoon.helper.feedback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A failed decode/write leaves the original file intact; never silently reset private messages. */
class FeedbackOutbox(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("pending_messages")
    private val json = Json { encodeDefaults = true }

    suspend fun pending(): List<FeedbackMessage> = decode(store.data.first()[key])

    suspend fun append(message: FeedbackMessage) {
        message.validate()
        store.edit { preferences ->
            val messages = decode(preferences[key])
            val existing = messages.firstOrNull { it.id == message.id }
            if (existing != null) {
                require(existing == message) { "Feedback ID cannot be reused with different content" }
                return@edit
            }
            if (messages.size >= FeedbackMessage.MAX_PENDING) throw FeedbackQueueFullException()
            preferences[key] = json.encodeToString(messages + message)
        }
    }

    suspend fun acknowledge(id: String) {
        store.edit { preferences ->
            val messages = decode(preferences[key])
            preferences[key] = json.encodeToString(messages.filterNot { it.id == id })
        }
    }

    private fun decode(raw: String?): List<FeedbackMessage> =
        if (raw == null) emptyList() else json.decodeFromString<List<FeedbackMessage>>(raw).also { messages ->
            require(messages.size <= FeedbackMessage.MAX_PENDING && messages.map { it.id }.distinct().size == messages.size)
            messages.forEach(FeedbackMessage::validate)
        }
}
