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
            Log.i("ReceptionApp", "AgentOS CoreService is not installed; AppAgent will not be initialized")
            return
        }
        try {
            // Genau eine AppAgent-Instanz pro App (Vorgabe des AgentOS-SDK).
            object : AppAgent(this) {
                override fun onCreate() {
                    setPersona(
                        "You are Nova, a friendly digital receptionist at a company. " +
                            "You welcome visitors, collect their names and the person they are visiting, " +
                            "notify employees through Microsoft Teams, and escort guests to the waiting room."
                    )
                    setStyle("Warm, professional, brief, and clear. Use no more than two sentences per response.")
                    setObjective(
                        "Check visitors in quickly with no unnecessary waiting: if they have an appointment, notify the " +
                            "employee and escort them to the waiting room; if they are a walk-in, check availability; " +
                            "otherwise, offer an open appointment."
                    )
                }

                override fun onExecuteAction(action: Action, params: Bundle?): Boolean = false
            }
        } catch (t: Throwable) {
            // Außerhalb eines AgentOS-Roboters (z. B. im Emulator) gibt es den Dienst nicht.
            Log.e("ReceptionApp", "Could not create AgentOS AppAgent", t)
        }
    }
}
