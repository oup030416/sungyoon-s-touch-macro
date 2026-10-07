package com.sungyoon.helper.data

import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.PresetPoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Concurrent store writes and IO readers must never associate coordinates with another JSON key. */
class CoordinateStoreCacheTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun pointReadersAlwaysReceiveTheCoordinatesOfTheirOwnSnapshot() {
        val serializer = ListSerializer(HighlightingPoint.serializer())
        val cases = listOf(10f, 2519f).map { x ->
            val points = listOf(HighlightingPoint(id = "point-$x", x = x, y = 500f, index = 0, delayMs = 1000L))
            json.encodeToString(serializer, points) to points
        }
        exerciseConcurrentCache(PointsStore, "decodePoints", cases)
    }

    @Test
    fun presetReadersAlwaysReceiveThePointerLayoutOfTheirOwnSnapshot() {
        val serializer = ListSerializer(PresetEntry.serializer())
        val cases = listOf(10f, 2519f).map { x ->
            val entries = listOf(PresetEntry(
                id = "preset-$x", name = "preset", createdAtEpochMs = 1L, autoNameOrdinal = 0,
                points = listOf(PresetPoint(index = 0, x = x, y = 500f)),
            ))
            json.encodeToString(serializer, entries) to entries
        }
        exerciseConcurrentCache(PresetStore, "decodeEntries", cases)
    }

    private fun <T> exerciseConcurrentCache(store: Any, decodeName: String, cases: List<Pair<String, List<T>>>) {
        val decode = store.javaClass.getDeclaredMethod(decodeName, String::class.java).apply { isAccessible = true }
        val update = store.javaClass.getDeclaredMethod("updateCache", String::class.java, List::class.java)
            .apply { isAccessible = true }
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker -> workers.submit {
                start.await()
                repeat(10_000) { iteration ->
                    val (raw, expected) = cases[(iteration + worker) % cases.size]
                    if (worker < 2) update.invoke(store, raw, expected)
                    else assertEquals(expected, decode.invoke(store, raw))
                }
            } }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
