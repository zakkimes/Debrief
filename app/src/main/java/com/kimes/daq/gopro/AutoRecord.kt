package com.kimes.daq.gopro

import com.kimes.daq.data.Gate
import com.kimes.daq.data.Sample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * When the camera should record on an autocross course: from [lead] metres before the start line
 * until [tailS] seconds after the finish. Fed every position sample; says true/false when the camera
 * should change, null otherwise.
 *
 * - Rolling toward the start line inside the box [lead] m in front of it (within the line's width,
 *   heading within 60 degrees of the course direction) starts recording. Sitting at the stage line
 *   keeps it going.
 * - Leaving that box for 3 s without crossing the start line stops it (drove past the start area).
 * - Crossing the start line always records, in case the approach was missed (GPS gap).
 * - After the finish line it keeps recording for [tailS] seconds (the slow-down and the run back).
 * - A run that never finishes stops after 20 s stopped or 5 minutes.
 */
class AutoRecord(private val start: Gate, private val finish: Gate, private val lead: Double = LEAD_M, private val tailS: Double = 10.0) {
    companion object {
        /** 50 ft: the camera starts (and the live display counts down) this far before the start line. */
        const val LEAD_M = 15.24
    }

    enum class State { IDLE, APPROACH, RUN, PAST_FINISH }

    var state = State.IDLE
        private set
    private var since = 0.0       // time the current state began
    private var outside = -1.0    // time the car left the approach box
    private var stopped = -1.0

    private fun inApproach(s: Sample): Boolean {
        val dx = s.x - start.x; val dy = s.y - start.y
        val along = dx * start.hx + dy * start.hy
        val lateral = abs(dx * -start.hy + dy * start.hx)
        return along in -lead..0.5 && lateral <= start.halfWidth
    }

    private fun headingOk(s: Sample): Boolean {
        val r = s.heading * PI / 180
        return sin(r) * start.hx + cos(r) * start.hy > 0.5
    }

    private fun go(st: State, t: Double): Boolean? {
        val was = state != State.IDLE
        state = st; since = t; outside = -1.0; stopped = -1.0
        val now = st != State.IDLE
        return if (now != was) now else null
    }

    fun feed(p: Sample?, s: Sample): Boolean? {
        val crossedStart = p != null && s.t - p.t < 1.0 && start.crossing(p.x, p.y, s.x, s.y) != null
        val crossedFinish = p != null && s.t - p.t < 1.0 && finish.crossing(p.x, p.y, s.x, s.y) != null
        when (state) {
            State.IDLE -> {
                if (crossedStart) return go(State.RUN, s.t)
                if (s.speed > 1.0 && headingOk(s) && inApproach(s)) return go(State.APPROACH, s.t)
            }
            State.APPROACH -> {
                if (crossedStart) return go(State.RUN, s.t)
                if (inApproach(s)) outside = -1.0
                else {
                    if (outside < 0) outside = s.t
                    if (s.t - outside > 3.0) return go(State.IDLE, s.t)
                }
            }
            State.RUN -> {
                if (crossedFinish && s.t - since > 3.0) return go(State.PAST_FINISH, s.t)
                if (crossedStart) { since = s.t; stopped = -1.0 } // re-grid or false start: the run starts over
                if (s.speed < 0.8) {
                    if (stopped < 0) stopped = s.t
                    if (s.t - stopped > 20.0) return go(State.IDLE, s.t)
                } else stopped = -1.0
                if (s.t - since > 300.0) return go(State.IDLE, s.t)
            }
            State.PAST_FINISH -> {
                if (s.t - since >= tailS) return go(State.IDLE, s.t)
            }
        }
        return null
    }
}
