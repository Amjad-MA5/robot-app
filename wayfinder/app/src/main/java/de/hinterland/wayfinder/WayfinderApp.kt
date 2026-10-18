package de.hinterland.wayfinder

import android.app.Application
import android.os.Bundle
import android.util.Log
import com.ainirobot.agent.AppAgent
import com.ainirobot.agent.action.Action
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.robot.RealRobot

class WayfinderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.sink = { level, message, error ->
            when (level) {
                'E' -> Log.e(TAG, message, error)
                'W' -> Log.w(TAG, message, error)
                else -> Log.i(TAG, message)
            }
        }
        if (!RealRobot.isRobotOs(this)) {
            AppLog.i("AgentOS is not installed: no AppAgent")
            return
        }
        try {
            // Exactly one AppAgent per app (AgentOS SDK rule). The app understands speech itself
            // (planning is switched off in MainActivity), so no actions are registered here.
            object : AppAgent(this) {
                override fun onCreate() {
                    setPersona(
                        "You are a friendly guide robot at an event with presentations. " +
                            "You show visitors the way to rooms and tell them on which floor a room is.",
                    )
                    setStyle("Warm, cheerful and brief. At most two short sentences.")
                    setObjective("Help every visitor find the room they are looking for, quickly and reliably.")
                }

                override fun onExecuteAction(action: Action, params: Bundle?): Boolean = false
            }
        } catch (t: Throwable) {
            AppLog.e("Could not create the AgentOS AppAgent", t)
        }
    }

    private companion object {
        const val TAG = "Wayfinder"
    }
}
