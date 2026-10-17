package de.hinterland.smartreceptionist.badge

/** Sätze rund um Foto und Besucherausweis (Deutsch + Englisch). */
interface BadgeLines {
    val askPhoto: String
    val photoPrivacy: String
    val optPhotoYes: String
    val optNoPhoto: String
    val lookAtCamera: String
    val lookAtCameraTitle: String
    val smile: String
    val reviewQuestion: String
    val optPhotoOk: String
    val optRetake: String
    val cameraUnavailable: String
    val printing: String
    val printingTitle: String
    fun printed(pickupHint: String): String
    val printFailed: String
    val noPhotoOk: String
    val badgeTitle: String
}

object GermanBadgeLines : BadgeLines {
    override val askPhoto = "Für Ihren Besucherausweis mache ich kurz ein Foto von Ihnen. Ist das in Ordnung?"
    override val photoPrivacy = "Das Foto wird nur für Ihren Ausweis verwendet und nach dem Druck gelöscht."
    override val optPhotoYes = "Ja, Foto machen"
    override val optNoPhoto = "Ohne Foto"
    override val lookAtCamera = "Bitte schauen Sie in meine Kamera und lächeln Sie!"
    override val lookAtCameraTitle = "Bitte in die Kamera schauen"
    override val smile = "Bitte lächeln!"
    override val reviewQuestion = "So sieht Ihr Foto aus. Passt das?"
    override val optPhotoOk = "✔ Passt"
    override val optRetake = "Neues Foto"
    override val cameraUnavailable = "Meine Kamera ist gerade leider nicht verfügbar. Ihr Ausweis wird ohne Foto gedruckt."
    override val printing = "Einen Moment, ich erstelle Ihren Besucherausweis."
    override val printingTitle = "Ihr Besucherausweis"
    override fun printed(pickupHint: String) =
        "Ihr Besucherausweis wird jetzt gedruckt. Bitte nehmen Sie ihn $pickupHint mit und tragen Sie ihn gut sichtbar."
    override val printFailed =
        "Der Ausweis konnte leider gerade nicht gedruckt werden. Bitte melden Sie sich kurz bei einer Kollegin oder einem Kollegen."
    override val noPhotoOk = "Kein Problem, dann ohne Foto."
    override val badgeTitle = "Ihr Besucherausweis"
}

object EnglishBadgeLines : BadgeLines {
    override val askPhoto = "For your visitor badge I would like to take a quick photo of you. Is that okay?"
    override val photoPrivacy = "The photo is only used for your badge and deleted after printing."
    override val optPhotoYes = "Yes, take a photo"
    override val optNoPhoto = "Without photo"
    override val lookAtCamera = "Please look into my camera and smile!"
    override val lookAtCameraTitle = "Please look into the camera"
    override val smile = "Smile!"
    override val reviewQuestion = "This is your photo. Is it okay?"
    override val optPhotoOk = "✔ Looks good"
    override val optRetake = "Take another"
    override val cameraUnavailable = "Sorry, my camera is not available right now. Your badge will be printed without a photo."
    override val printing = "One moment, I am creating your visitor badge."
    override val printingTitle = "Your visitor badge"
    override fun printed(pickupHint: String) =
        "Your visitor badge is being printed now. Please pick it up $pickupHint and wear it visibly."
    override val printFailed = "Sorry, the badge could not be printed right now. Please ask a member of staff."
    override val noPhotoOk = "No problem, without a photo then."
    override val badgeTitle = "Your visitor badge"
}

fun badgeLinesFor(language: String): BadgeLines = if (language == "en") EnglishBadgeLines else GermanBadgeLines
