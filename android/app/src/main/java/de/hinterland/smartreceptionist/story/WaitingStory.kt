package de.hinterland.smartreceptionist.story

/**
 * Kurze Geschichte über die Founders Foundation, die der Roboter im Warteraum erzählt.
 *
 * Reines Kotlin ohne Android-Abhängigkeiten → läuft auch in den Unit-Tests. Die Fakten stammen von
 * foundersfoundation.de; wer einen anderen Text möchte, trägt ihn im Admin-Bereich ein
 * ([parts] nimmt dann dessen Absätze statt des Standardtextes).
 */
class StoryLines(
    val offer: String,
    val title: String,
    val optYes: String,
    val optNo: String,
    val defaultParts: List<String>,
)

object WaitingStory {
    private val german = StoryLines(
        offer = "Möchten Sie, während Sie warten, ein wenig darüber hören, wo Sie hier sind?",
        title = "Über die Founders Foundation",
        optYes = "Ja, gerne",
        optNo = "Nein, danke",
        defaultParts = listOf(
            "Sie sind bei der Founders Foundation, einer gemeinnützigen Organisation in Bielefeld. " +
                "Unser Motto lautet: Empowering Entrepreneurs.",
            "Wir bilden die nächste Generation von Gründerinnen und Gründern mitten im deutschen Mittelstand aus, " +
                "mit Programmen wie Startup School, LAB und Accelerator. " +
                "Und wir übernehmen keine Anteile an den Startups, die wir unterstützen.",
            "Außerdem bringen wir junge und etablierte Unternehmen zusammen und veranstalten die Konferenz " +
                "Hinterland of Things. Danke fürs Zuhören und einen angenehmen Aufenthalt!",
        ),
    )

    private val english = StoryLines(
        offer = "While you wait, would you like to hear a little about where you are?",
        title = "About Founders Foundation",
        optYes = "Yes, please",
        optNo = "No, thanks",
        defaultParts = listOf(
            "You are at Founders Foundation, a non-profit organisation in Bielefeld. " +
                "Our motto is: Empowering Entrepreneurs.",
            "We help train the next generation of founders right in the heart of the German Mittelstand, " +
                "with programmes like Startup School, LAB and the Accelerator. " +
                "And we take no equity in the startups we support.",
            "We also connect young companies with established ones, and we host the Hinterland of Things " +
                "conference. Thank you for listening, and enjoy your stay!",
        ),
    )

    fun lines(isGerman: Boolean): StoryLines = if (isGerman) german else english

    /** Teile der Geschichte: Absätze des eigenen Textes (durch Leerzeilen getrennt) oder der Standardtext. */
    fun parts(isGerman: Boolean, customText: String): List<String> {
        val custom = customText.split(Regex("\\n\\s*\\n")).map { it.trim().replace(Regex("\\s*\\n\\s*"), " ") }.filter { it.isNotEmpty() }
        return custom.ifEmpty { lines(isGerman).defaultParts }
    }
}
