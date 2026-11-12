package io.embodit.chefboard.ui

import io.embodit.chefboard.ui.stage.Button3D
import io.embodit.chefboard.ui.stage.Hit
import io.embodit.chefboard.ui.stage.Names
import io.embodit.chefboard.ui.stage.input.parseLoggedPath
import io.embodit.chefboard.ui.stage.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** build.md「命中目标 → 格子回放」: every hit chain the system can report resolves to the right target. */
class HitReplayTest {
    @Test fun everyPickableResolves() {
        // a hit on a visible child reports the child first, then its parents (TAP log `path=a < b < c`)
        for (s in 0..3) {
            for (part in listOf("bodyLow:$s", "bodyHigh:$s", "bezel:$s", "dial:$s", "arc:$s", "pointer:$s", "knob:$s"))
                assertEquals(Hit.TimerHit(s), resolvePath(listOf(part, "timerVis:$s", Names.timer(s), "stageRoot"), Names::resolve))
            assertEquals(Hit.TimerHit(s), resolvePath(listOf("arc:$s", "arcHolder:$s", "timerVis:$s", Names.timer(s)), Names::resolve))
        }
        for (i in 0..4) for (part in listOf("spotRing:$i", "spotDot:$i", "spotFill:$i"))
            assertEquals(Hit.Spot(i), resolvePath(listOf(part, Names.spot(i), "kitchenRoot", "stageRoot"), Names::resolve))
        for (b in Button3D.entries) for (part in listOf("btnBody:${b.id}", "btnFace:${b.id}"))
            assertEquals(Hit.Btn(b), resolvePath(listOf(part, Names.btn(b), "stageRoot"), Names::resolve))
        assertEquals(Hit.Plate, resolvePath(listOf("plateFace", "plate", "rackRoot", "kitchenRoot"), Names::resolve))
        assertEquals(Hit.Plate, resolvePath(parseLoggedPath("plateBody < plate < rackRoot < kitchenRoot < stageRoot"), Names::resolve))
    }

    @Test fun nonTargetsResolveToNothing() {
        assertNull(resolvePath(listOf("rackBoard", "rackRoot", "kitchenRoot", "stageRoot"), Names::resolve))
        assertNull(resolvePath(listOf("well:2", "rackRoot"), Names::resolve))
        assertNull(resolvePath(listOf("tag:1", "stageRoot"), Names::resolve))
        assertNull(resolvePath(listOf("timer:9"), Names::resolve))
    }
}
