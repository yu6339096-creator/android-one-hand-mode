package com.example.touchengine

import kotlin.math.hypot

/** 只负责在新 NavMesh 中保留或恢复焦点，不持有任何 Service 状态。 */
internal object NavMeshFocusResolver {

    fun reconcile(
        previous: NavNode?,
        candidates: List<NavNode>,
        exactTolerancePx: Float = 20f
    ): NavNode? {
        if (previous == null || candidates.isEmpty()) return null
        previous.stableId?.let { stableId ->
            candidates.firstOrNull { it.stableId == stableId }?.let { return it }
        }
        val exact = candidates.firstOrNull { node ->
            hypot(
                (node.centerX - previous.centerX).toDouble(),
                (node.centerY - previous.centerY).toDouble()
            ) < exactTolerancePx
        }
        return exact ?: nearest(candidates, previous.centerX, previous.centerY)
    }

    fun nearest(candidates: List<NavNode>, x: Float, y: Float): NavNode? {
        return candidates.minByOrNull { node ->
            hypot(
                (node.centerX - x).toDouble(),
                (node.centerY - y).toDouble()
            )
        }
    }
}
