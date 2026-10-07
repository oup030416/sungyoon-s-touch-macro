package com.sungyoon.helper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EncodedValueCacheTest {
    @Test
    fun emptyAndUpdatedValuesNeverShareDifferentSerializedKeys() {
        val cache = EncodedValueCache(emptyList<String>())
        cache.update("one", listOf("one"))
        assertEquals(listOf("one"), cache.getOrDecode("one") { error("Cache missed") })
        assertEquals(emptyList<String>(), cache.getOrDecode("") { emptyList() })
        assertEquals(listOf("two"), cache.getOrDecode("two") { listOf(it) })
    }

    @Test
    fun concurrentPublishAndDecodeKeepEachResultPairedWithRequestedKey() {
        val cache = EncodedValueCache("")
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(4)
        try {
            val workers = List(4) { worker ->
                threads.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(10_000) { iteration ->
                        val key = if ((worker + iteration) % 3 == 0) "" else "point-$worker-${iteration % 5}"
                        cache.update(key, key)
                        assertEquals(key, cache.getOrDecode(key) { it })
                    }
                }
            }
            start.countDown()
            workers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    fun slowOlderDecodeCannotMakeNewerKeyReturnOlderCoordinates() {
        val cache = EncodedValueCache("")
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newSingleThreadExecutor()
        try {
            val old = threads.submit<String> {
                cache.getOrDecode("old") {
                    decoding.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    it
                }
            }
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            cache.update("new", "new")
            assertEquals("new", cache.getOrDecode("new") { error("Cache missed") })
            release.countDown()
            assertEquals("old", old.get(5, TimeUnit.SECONDS))
            assertEquals("new", cache.getOrDecode("new") { it })
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }
}
