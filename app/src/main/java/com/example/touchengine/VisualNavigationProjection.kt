package com.example.touchengine

import android.graphics.Rect
import kotlin.math.abs

/** Same-scene publication boundary, after Fusion and before incremental graph construction. */
internal object VisualNavigationProjection {
    fun project(committed: List<NavNode>, observed: List<NavNode>): List<NavNode> {
        val oldById = committed.associateBy { it.stableId }
        return NavNodeIdentity.ensureStableIds(observed).map { node ->
            val old = oldById[node.stableId] ?: return@map node
            if (old.windowId != node.windowId) return@map node
            val bounds = if (smallChange(old.bounds, node.bounds)) old.bounds else node.bounds
            val oldInteraction = old.interactionBounds ?: old.bounds
            val newInteraction = node.interactionBounds ?: node.bounds
            val interaction = if (smallChange(oldInteraction, newInteraction)) oldInteraction else newInteraction
            val projected = node.copy(
                bounds = Rect(bounds),
                centerX = bounds.exactCenterX(),
                centerY = bounds.exactCenterY(),
                interactionBounds = Rect(interaction)
            )
            // Keep metadata-only updates in Canvas even when a different target changes.
            if (NavNodeIdentity.sameNavigation(old, projected)) old else projected
        }
    }

    fun sameTargets(first: List<NavNode>, second: List<NavNode>): Boolean {
        if (first.size != second.size) return false
        val byId = first.associateBy { it.stableId }
        return second.all { node ->
            val old = byId[node.stableId]
            old != null && NavNodeIdentity.sameNavigation(old, node)
        }
    }

    // Compare against the published baseline so repeated subthreshold motion accumulates.
    private fun smallChange(first: Rect, second: Rect): Boolean {
        val intersection = maxOf(0, minOf(first.right, second.right) - maxOf(first.left, second.left)).toLong() *
            maxOf(0, minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
        val union = first.width().toLong() * first.height() + second.width().toLong() * second.height() - intersection
        return union > 0 && intersection.toDouble() / union >= 0.82 &&
            maxOf(abs(first.left - second.left), abs(first.top - second.top),
                abs(first.right - second.right), abs(first.bottom - second.bottom)) <= 7
    }
}
