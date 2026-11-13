package io.embodit.chefboard.platform.audio

import io.embodit.chefboard.domain.timer.Ev

/**
 * Timer event → mixer cue (design sound.events). Pure, so the unit tests feed the same routing the app uses
 * (q3-1: two timers docking in one frame play 笃 twice → the first copy is cut with a fade).
 * The bell is not here: it plays on the timer entity (spatial) and only falls back to the mixer.
 */
object SoundCues {
    data class Cue(val kind: String, val gain: Float, val slot: Int?)

    fun of(ev: Ev): List<Cue> = when (ev) {
        is Ev.Tick -> listOf(Cue("tick", 0.55f, ev.slot))                                    // 咔 −18 dB class: short, light
        is Ev.Landed -> listOf(Cue("wood", 0.70f, ev.slot), Cue("click", 0.75f, ev.slot)) // 笃 + 嗒 on the landing frame
        is Ev.Docked -> listOf(Cue("wood", 0.65f, ev.slot))                                  // 笃 back in the rack
        is Ev.Paused -> listOf(Cue("click", 0.55f, ev.slot))
        is Ev.Resumed -> listOf(Cue("click", 0.55f, ev.slot))
        is Ev.Refused -> listOf(Cue("tick", 0.40f, null))
        else -> emptyList()
    }
}
