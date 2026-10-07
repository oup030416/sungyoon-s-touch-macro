package com.sungyoon.helper.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class SetItemType { TOUCH, RESERVED, WAIT }

@Serializable
data class ReservationConfig(
    val runSeconds: Int = 60,
    val restSeconds: Int = 30,
    val repeatCount: Int = 1,
) {
    fun normalized(): ReservationConfig = copy(
        runSeconds = runSeconds.coerceIn(1, 3600),
        restSeconds = restSeconds.coerceIn(1, 3600),
        repeatCount = repeatCount.coerceIn(1, 9999),
    )
}

@Serializable
sealed class SetItem {
    abstract val id: String
    abstract val name: String
    abstract val type: SetItemType
    abstract val points: List<HighlightingPoint>

    val isExecutable: Boolean
        get() = when (this) {
            is Touch, is Reserved -> points.isNotEmpty()
            is Wait -> durationMs >= Wait.MIN_DURATION_MS
        }

    @Serializable
    @SerialName("touch")
    data class Touch(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val points: List<HighlightingPoint> = emptyList(),
    ) : SetItem() {
        override val type: SetItemType get() = SetItemType.TOUCH
    }

    @Serializable
    @SerialName("reserved")
    data class Reserved(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val points: List<HighlightingPoint> = emptyList(),
        val reservation: ReservationConfig = ReservationConfig(),
    ) : SetItem() {
        override val type: SetItemType get() = SetItemType.RESERVED
    }

    @Serializable
    @SerialName("wait")
    data class Wait(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        val durationMs: Long = 1000L,
    ) : SetItem() {
        override val type: SetItemType get() = SetItemType.WAIT
        override val points: List<HighlightingPoint> get() = emptyList()

        companion object {
            const val MIN_DURATION_MS = 100L
            const val MAX_DURATION_MS = 3_600_000L
            const val DURATION_STEP_MS = 100L

            fun isValidDuration(durationMs: Long): Boolean =
                durationMs in MIN_DURATION_MS..MAX_DURATION_MS && durationMs % DURATION_STEP_MS == 0L
        }
    }

    fun renamed(newName: String): SetItem = when (this) {
        is Touch -> copy(name = newName)
        is Reserved -> copy(name = newName)
        is Wait -> copy(name = newName)
    }

    fun withPoints(newPoints: List<HighlightingPoint>): SetItem = when (this) {
        is Touch -> copy(points = newPoints.sortedBy { it.index }.toList())
        is Reserved -> copy(points = newPoints.sortedBy { it.index }.toList())
        is Wait -> this
    }

    /** Copies pointer IDs as well, so edits never alias the original item. */
    fun duplicate(newName: String): SetItem {
        val newId = UUID.randomUUID().toString()
        val copiedPoints = points.map { it.copy(id = UUID.randomUUID().toString()) }
        return when (this) {
            is Touch -> copy(id = newId, name = newName, points = copiedPoints)
            is Reserved -> copy(
                id = newId,
                name = newName,
                points = copiedPoints,
                reservation = reservation.copy(),
            )
            is Wait -> copy(id = newId, name = newName)
        }
    }
}
