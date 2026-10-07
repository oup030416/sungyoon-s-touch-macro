package com.sungyoon.helper.data

/** Publish the serialized key and its decoded value together across store and collector threads. */
internal class EncodedValueCache<T>(initialValue: T) {
    private data class Snapshot<T>(val raw: String, val value: T)

    @Volatile
    private var snapshot = Snapshot("", initialValue)

    fun getOrDecode(raw: String, decode: (String) -> T): T {
        val cached = snapshot
        if (cached.raw == raw) return cached.value
        return decode(raw).also { update(raw, it) }
    }

    fun update(raw: String, value: T) {
        snapshot = Snapshot(raw, value)
    }
}
