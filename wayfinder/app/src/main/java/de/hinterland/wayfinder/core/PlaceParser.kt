package de.hinterland.wayfinder.core

import org.json.JSONArray

/** Parses the answer of RobotAPI getPlaceList: [{"name":"Main Stage","x":1.2,"y":3.4,"status":0,...}, ...] */
object PlaceParser {

    /** null = not a place list (error text instead of JSON) */
    fun parse(message: String?): List<MapPlace>? {
        val text = message?.trim().orEmpty()
        if (!text.startsWith("[")) return null
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) return@mapNotNull null
                MapPlace(
                    name = name,
                    x = o.optDouble("x", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                    y = o.optDouble("y", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                    status = o.optInt("status", 0),
                    type = o.optInt("typeId", o.optInt("type", 0)),
                )
            }.distinctBy { it.name }
        } catch (t: Throwable) {
            null
        }
    }
}
