package de.hinterland.wayfinder.core

import kotlin.random.Random

/**
 * Picks the next point of the wander route.
 *
 * - In order (default) or random, never the point the robot is standing at.
 * - A point that fails twice in a row (blocked, unreachable) is skipped for a few minutes,
 *   so one blocked corridor does not stop the whole tour.
 */
class WanderPlanner(
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val skipMs: Long = 5 * 60_000L,
) {
    /** Point the robot reached last (null = unknown, e.g. after guiding a visitor) */
    var current: String? = null
        private set
    private var lastTarget: String? = null
    private val failures = mutableMapOf<String, Int>()
    private val skippedUntil = mutableMapOf<String, Long>()

    fun next(route: List<String>, randomOrder: Boolean): String? {
        val now = clock()
        val usable = route.distinct().filter { (skippedUntil[it] ?: Long.MIN_VALUE) <= now }
        val candidates = usable.filter { it != current }
        if (candidates.isEmpty()) return null
        if (randomOrder) {
            val fresh = candidates.filter { it != lastTarget }.ifEmpty { candidates }
            return fresh[random.nextInt(fresh.size)]
        }
        // Continue after the point tried last (so a blocked point is not retried right away), in route order
        val anchor = lastTarget ?: current
        val start = route.indexOf(anchor)
        for (step in 1..route.size) {
            val p = route[(start + step).mod(route.size)]
            if (p in candidates) return p
        }
        return candidates.first()
    }

    fun arrived(point: String) {
        current = point
        lastTarget = point
        failures.remove(point)
    }

    fun failed(point: String) {
        lastTarget = point
        val count = (failures[point] ?: 0) + 1
        if (count >= 2) {
            skippedUntil[point] = clock() + skipMs
            failures.remove(point)
        } else {
            failures[point] = count
        }
    }

    /** The robot moved somewhere else (guiding a visitor), so the current point is unknown. */
    fun moved(to: String?) {
        current = to
    }
}
