package dev.baseline.ui.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Drag state for the edit-mode row list. [delta] is how far the finger moved; [scrolled] is how far the page scrolled
 * since the drag began (auto-scroll). Their sum is the dragged row's offset from its slot, so the row stays under the
 * finger and the target index counts the scrolled distance.
 */
internal class RowDrag {
    var from by mutableStateOf<Int?>(null)
    var delta by mutableFloatStateOf(0f)
    var scrolled by mutableFloatStateOf(0f)
    val offset get() = delta + scrolled

    /** Laid-out row heights by row id; written from layout, read from gestures and the scroll loop. */
    val heights = mutableMapOf<String, Float>()

    fun start(index: Int) {
        from = index
        delta = 0f
        scrolled = 0f
    }

    fun reset() {
        from = null
        delta = 0f
        scrolled = 0f
    }
}

/** Top of each row's slot, in the list's own coordinates. */
internal fun slotTops(heights: List<Float>, gap: Float): List<Float> {
    var y = 0f
    return heights.map { h -> y.also { y += h + gap } }
}

/** The index row [from] lands on when it sits [offset] px from its own slot: the slot holding its centre. */
internal fun dragTarget(heights: List<Float>, gap: Float, from: Int, offset: Float): Int {
    val tops = slotTops(heights, gap)
    val centre = tops[from] + heights[from] / 2 + offset
    return heights.indices.firstOrNull { centre < tops[it] + heights[it] + gap / 2 } ?: heights.lastIndex
}

/** How far a row that is not being dragged moves to make room, when row [from] is dragged over to [target]. */
internal fun makeRoom(heights: List<Float>, gap: Float, from: Int, target: Int, index: Int): Float {
    val step = heights[from] + gap
    return when {
        target > from && index in from + 1..target -> -step
        target < from && index in target until from -> step
        else -> 0f
    }
}
