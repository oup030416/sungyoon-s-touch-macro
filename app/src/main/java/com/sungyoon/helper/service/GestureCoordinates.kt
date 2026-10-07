package com.sungyoon.helper.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Bounds in the same absolute display pixels used by accessibility gestures. */
internal data class GestureCoordinateBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left && bottom > top)
    }

    fun constrain(x: Float, y: Float): Pair<Float, Float> =
        x.coerceIn(left.toFloat(), (right - 1).toFloat()) to
            y.coerceIn(top.toFloat(), (bottom - 1).toFloat())
}

/** Keep disk sampling and edge projection, without using an application's inset viewport. */
internal fun randomizedTapTarget(
    x: Float,
    y: Float,
    radiusPx: Float,
    bounds: GestureCoordinateBounds,
    random: Random = Random.Default,
): Pair<Float, Float> {
    if (radiusPx <= 0f) return x to y

    val theta = random.nextDouble(0.0, PI * 2.0)
    val radius = sqrt(random.nextDouble(0.0, 1.0)) * radiusPx
    return bounds.constrain(
        x + (radius * cos(theta)).toFloat(),
        y + (radius * sin(theta)).toFloat(),
    )
}
