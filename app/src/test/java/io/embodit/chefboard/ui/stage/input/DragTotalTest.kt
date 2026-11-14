package io.embodit.chefboard.ui.stage.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** SDK 6.1.9 sends per-event drag deltas; DragTotal must add them up (template board-input, 2026-11-14). */
class DragTotalTest {
    @Test fun sixtyTwoMillimetreStepsTotalTwelveCentimetres() {
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-4f)
        assertEquals(60, t.events)
    }

    @Test fun resetClearsThePress() {
        val t = DragTotal()
        t.add(Offset3M(0.05f, 0.01f, -0.02f))
        t.reset()
        val d = t.add(Offset3M(0.001f, 0f, 0f))
        assertEquals(0.001f, d.x, 1e-6f)
        assertEquals(0f, d.y, 1e-6f)
        assertEquals(1, t.events)
    }
}
