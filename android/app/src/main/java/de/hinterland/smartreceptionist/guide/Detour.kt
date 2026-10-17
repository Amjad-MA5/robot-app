package de.hinterland.smartreceptionist.guide

/**
 * Wegweiser-Funktion, Teil 3: Der Besucher fragt unterwegs nach einem anderen Ort
 * („Wo ist die Toilette?“, während der Roboter in den Warteraum fährt).
 *
 * Reines Kotlin → läuft auch in den Unit-Tests. Es wird nur auf eindeutige Wünsche reagiert:
 * genau ein passender Kartenpunkt, der nicht das aktuelle Ziel ist. Alles andere
 * (Gespräche, Motorgeräusche, die eigene Stimme des Roboters) wird ignoriert.
 */
object Detour {
    fun request(text: String, catalog: PlaceCatalog, currentTarget: String): Destination? {
        val destination = GuideNlu.detect(text, catalog, inCheckIn = false)?.candidates?.singleOrNull() ?: return null
        if (destination.name.equals(currentTarget, ignoreCase = true)) return null
        // Am Empfang ist der Roboter zu Hause – dorthin fährt er ohnehin zurück
        if (destination.kind == PlaceKind.RECEPTION) return null
        return destination
    }
}
