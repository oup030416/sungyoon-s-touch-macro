package com.sungyoon.helper.overlay.pointer

import android.content.Context
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.SetStore
import com.sungyoon.helper.model.HighlightingPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

sealed interface PointerEditTarget {
    data object Global : PointerEditTarget
    data class Item(val id: String) : PointerEditTarget
}

/** Each operation carries its target so a later navigation cannot redirect a pending save. */
class PointerEditRepository(private val context: Context) {
    fun pointsFlow(target: PointerEditTarget): Flow<List<HighlightingPoint>> = when (target) {
        PointerEditTarget.Global -> PointsStore.pointsFlow(context)
        is PointerEditTarget.Item -> SetStore.itemsFlow(context).map { items ->
            items.firstOrNull { it.id == target.id }?.points.orEmpty()
        }.distinctUntilChanged()
    }

    suspend fun get(target: PointerEditTarget): List<HighlightingPoint> = pointsFlow(target).first()

    suspend fun clear(target: PointerEditTarget) = replace(target, emptyList())

    suspend fun replace(target: PointerEditTarget, points: List<HighlightingPoint>) {
        when (target) {
            PointerEditTarget.Global -> PointsStore.replaceAll(context, points)
            is PointerEditTarget.Item -> SetStore.replacePoints(context, target.id, points)
        }
    }

    suspend fun add(target: PointerEditTarget, point: HighlightingPoint) {
        when (target) {
            PointerEditTarget.Global -> PointsStore.addPoint(context, point)
            is PointerEditTarget.Item -> SetStore.updatePoints(context, target.id) { points ->
                points + point.copy(index = (points.maxOfOrNull { it.index } ?: -1) + 1)
            }
        }
    }

    suspend fun delete(target: PointerEditTarget, id: String) {
        when (target) {
            PointerEditTarget.Global -> PointsStore.deletePoint(context, id)
            is PointerEditTarget.Item -> SetStore.updatePoints(context, target.id) { points ->
                points.filterNot { it.id == id }
            }
        }
    }

    suspend fun move(target: PointerEditTarget, id: String, endpoint: PointerOverlayRootView.Endpoint, x: Float, y: Float) {
        when (target) {
            PointerEditTarget.Global -> if (endpoint == PointerOverlayRootView.Endpoint.START) {
                PointsStore.updatePointPosition(context, id, x, y)
            } else {
                PointsStore.updateDragEndPosition(context, id, x, y)
            }
            is PointerEditTarget.Item -> SetStore.updatePoints(context, target.id) { points ->
                points.map { point ->
                    if (point.id != id) point
                    else if (endpoint == PointerOverlayRootView.Endpoint.END) {
                        point.copy(actionType = HighlightingPoint.ACTION_TYPE_DRAG, dragToX = x, dragToY = y)
                    } else if (point.actionType == HighlightingPoint.ACTION_TYPE_DRAG) {
                        point.copy(x = x, y = y)
                    } else {
                        point.copy(x = x, y = y, dragToX = x, dragToY = y)
                    }
                }
            }
        }
    }
}
