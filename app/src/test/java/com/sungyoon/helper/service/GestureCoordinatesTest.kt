package com.sungyoon.helper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GestureCoordinatesTest {
    private val landscape = GestureCoordinateBounds(0, 0, 2520, 1080)

    @Test
    fun edgeTargetsStayAroundScreenPointerBeyondInsetApplicationViewport() {
        val random = Random(7)
        repeat(2000) {
            val (x, y) = randomizedTapTarget(2519f, 1079f, 20f, landscape, random)
            // The old 2441x1017 resource viewport moved this target by at least 79px.
            assertTrue(x >= 2499f && x <= 2519f)
            assertTrue(y >= 1059f && y <= 1079f)
            assertWithinRadius(2519f, 1079f, x, y, 20f)
        }
    }

    @Test
    fun zeroRadiusPreservesAbsoluteCoordinatesWithoutRescalingOrClipping() {
        listOf(0f to 0f, 2519f to 1079f, 2518.5f to 1000.25f, -20f to 40f).forEach { (x, y) ->
            assertEquals(x to y, randomizedTapTarget(x, y, 0f, landscape))
        }
    }

    @Test
    fun randomizedCornersStayOnDisplayAndWithinConfiguredRadius() {
        val random = Random(3)
        listOf(0f to 0f, 2519f to 0f, 0f to 1079f, 2519f to 1079f).forEach { (cx, cy) ->
            repeat(1000) {
                val (x, y) = randomizedTapTarget(cx, cy, 60f, landscape, random)
                assertTrue(x >= 0f && x <= 2519f)
                assertTrue(y >= 0f && y <= 1079f)
                assertWithinRadius(cx, cy, x, y, 60f)
            }
        }
    }

    @Test
    fun boundsRetainDisplayAreaOriginInsteadOfTreatingWidthAsScreenEndpoint() {
        val bounds = GestureCoordinateBounds(200, 100, 1400, 1000)
        val random = Random(9)
        repeat(1000) {
            val (x, y) = randomizedTapTarget(1399f, 999f, 40f, bounds, random)
            assertTrue(x >= 1359f && x <= 1399f)
            assertTrue(y >= 959f && y <= 999f)
            assertWithinRadius(1399f, 999f, x, y, 40f)
        }
        assertEquals(200f to 100f, bounds.constrain(-1f, -1f))
    }

    @Test
    fun targetsUseSuppliedCurrentOrientationAndSizeRatherThanPriorBounds() {
        val portrait = GestureCoordinateBounds(0, 0, 1080, 2520)
        val landscapeTarget = randomizedTapTarget(2200f, 500f, 5f, landscape, Random(1))
        val portraitTarget = randomizedTapTarget(2200f, 500f, 5f, portrait, Random(1))
        assertTrue(landscapeTarget.first > 2195f)
        assertEquals(1079f, portraitTarget.first, 0f)
        assertEquals(landscapeTarget.second, portraitTarget.second, 0f)
    }

    @Test
    fun interiorTargetsRemainRandomWithinDiskInsteadOfChangingPointerCoordinates() {
        val random = Random(5)
        val targets = List(1000) { randomizedTapTarget(1200f, 500f, 20f, landscape, random) }
        targets.forEach { (x, y) -> assertWithinRadius(1200f, 500f, x, y, 20f) }
        assertTrue(targets.any { it.first < 1200f })
        assertTrue(targets.any { it.first > 1200f })
        assertTrue(targets.any { it.second < 500f })
        assertTrue(targets.any { it.second > 500f })
    }

    private fun assertWithinRadius(cx: Float, cy: Float, x: Float, y: Float, radius: Float) {
        val dx = x - cx
        val dy = y - cy
        assertTrue("Target ($x, $y) exceeds radius $radius around ($cx, $cy)",
            dx * dx + dy * dy <= radius * radius + 0.02f)
    }
}
