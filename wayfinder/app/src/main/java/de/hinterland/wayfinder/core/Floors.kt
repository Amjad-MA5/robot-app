package de.hinterland.wayfinder.core

import kotlin.math.abs

/**
 * Floor names in English. Floors are counted like in Germany and the UK: 0 = ground floor,
 * 1 = first floor (one floor up), -1 = basement. The robot always adds "one floor up/down",
 * so nobody is confused by American floor numbering.
 */
object Floors {

    private val ORDINALS = listOf("ground", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")

    /** "the first floor", "the ground floor", "the basement", "basement level 2", "floor 14" */
    fun name(floor: Int): String = when {
        floor == 0 -> "the ground floor"
        floor in 1 until ORDINALS.size -> "the ${ORDINALS[floor]} floor"
        floor > 0 -> "floor $floor"
        floor == -1 -> "the basement"
        else -> "basement level ${-floor}"
    }

    /** "one floor up", "two floors down", "this floor" */
    fun relative(floor: Int, from: Int): String {
        val diff = floor - from
        if (diff == 0) return "this floor"
        val count = when (abs(diff)) {
            1 -> "one floor"
            2 -> "two floors"
            3 -> "three floors"
            else -> "${abs(diff)} floors"
        }
        return if (diff > 0) "$count up" else "$count down"
    }

    /** "on the first floor, one floor up" / "in the basement, one floor down" */
    fun spoken(floor: Int, from: Int): String {
        if (floor == from) return "on this floor"
        val preposition = if (floor == -1) "in" else "on"
        return "$preposition ${name(floor)}, ${relative(floor, from)}"
    }

    /** "on the first floor, one floor up, or in the basement, one floor down" */
    fun spokenList(floors: List<Int>, from: Int): String {
        val parts = floors.distinct().sortedByDescending { it }.map { spoken(it, from) }
        return when (parts.size) {
            0 -> "on another floor"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + ", or " + parts.last()
        }
    }

    /** Big label on the floor badge: "1", "0", "-1" */
    fun badge(floor: Int): String = if (floor < 0) "−${-floor}" else "$floor"

    /** Small label under the badge: "First floor", "Basement" */
    fun caption(floor: Int): String = name(floor).removePrefix("the ").replaceFirstChar { it.titlecase() }

    /** Tile subtitle: "Floor 1 · −1" */
    fun short(floors: List<Int>): String {
        val list = floors.distinct().sortedByDescending { it }
        if (list.isEmpty()) return ""
        return (if (list.size == 1) "Floor " else "Floors ") + list.joinToString(" · ") { badge(it) }
    }

    /** Parses "1, -1" or "1 / −1" from the admin screen. Invalid parts are ignored. */
    fun parse(text: String): List<Int> =
        text.replace('−', '-').replace('–', '-')
            .split(',', ';', '/', ' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in -9..99 }
            .distinct()

    fun format(floors: List<Int>): String = floors.joinToString(", ")
}
