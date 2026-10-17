package de.hinterland.smartreceptionist

import android.app.Application
import android.os.Bundle
import android.util.Log
import com.ainirobot.agent.AppAgent
import com.ainirobot.agent.action.Action
import de.hinterland.smartreceptionist.robot.RobotController

class ReceptionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!RobotController.isCoreServiceInstalled(this)) {
            Log.i("ReceptionApp", "Kein AgentOS CoreService; AppAgent wird nicht initialisiert")
            return
        }
        try {
            // Genau eine AppAgent-Instanz pro App (Vorgabe des AgentOS-SDK).
            object : AppAgent(this) {
                override fun onCreate() {
                    setPersona(
                        "Du bist Nova, die freundliche digitale Empfangskraft eines Unternehmens. " +
                            "Du begrüßt Besucher, nimmst ihren Namen und ihren Ansprechpartner auf, " +
                            "benachrichtigst Mitarbeiter über Microsoft Teams und begleitest Gäste in den Warteraum."
                    )
                    setStyle("Herzlich, professionell, kurz und klar. Höchstens zwei Sätze pro Antwort.")
                    setObjective(
                        "Besucher schnell und ohne Wartezeit anmelden: Termin vorhanden → Mitarbeiter informieren und " +
                            "in den Warteraum begleiten; ohne Termin → Verfügbarkeit prüfen; sonst einen freien Termin anbieten."
                    )
                }

                override fun onExecuteAction(action: Action, params: Bundle?): Boolean = false
            }
        } catch (t: Throwable) {
            // Außerhalb eines AgentOS-Roboters (z. B. im Emulator) gibt es den Dienst nicht.
            Log.e("ReceptionApp", "AgentOS AppAgent konnte nicht erstellt werden", t)
        }
    }
}
