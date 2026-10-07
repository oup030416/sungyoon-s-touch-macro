package com.sungyoon.helper.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightingPointTest {
    private val drag = HighlightingPoint(
        x = 10f, y = 20f, index = 0, delayMs = 1000L,
        actionType = HighlightingPoint.ACTION_TYPE_DRAG,
    )

    @Test
    fun coincidentAndTwoPixelEndpointOffsetsUseTapFallback() {
        assertFalse(drag.isEffectiveDrag)
        assertFalse(drag.copy(dragToX = 12f, dragToY = 22f).isEffectiveDrag)
        assertFalse(drag.copy(dragToX = 8f, dragToY = 18f).isEffectiveDrag)
    }

    @Test
    fun movementBeyondEitherAxisThresholdUsesDrag() {
        assertTrue(drag.copy(dragToX = 12.1f).isEffectiveDrag)
        assertTrue(drag.copy(dragToY = 17.9f).isEffectiveDrag)
    }

    @Test
    fun tapTypeDoesNotBecomeDragFromUnusedEndpointCoordinates() {
        assertFalse(drag.copy(actionType = HighlightingPoint.ACTION_TYPE_TAP, dragToX = 100f).isEffectiveDrag)
    }
}
