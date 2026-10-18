package de.hinterland.wayfinder.flow

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.core.MapPlace
import de.hinterland.wayfinder.core.NoBrain
import de.hinterland.wayfinder.core.Settings
import de.hinterland.wayfinder.robot.AgentBrain
import de.hinterland.wayfinder.robot.AgentVoice
import de.hinterland.wayfinder.robot.RealRobot
import de.hinterland.wayfinder.robot.Robot
import de.hinterland.wayfinder.robot.SimulatedRobot
import de.hinterland.wayfinder.robot.SimulatedVoice
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus

/** Wires the controller to the real robot (or the simulation) and keeps it alive across the activity. */
class GuideViewModel(app: Application) : AndroidViewModel(app) {

    val onRobot: Boolean = RealRobot.isRobotOs(app)

    private val store = SettingsStore(app)
    val settings: Settings get() = store.current

    val robot: Robot = if (onRobot) RealRobot(app.applicationContext) else SimulatedRobot()

    /**
     * Errors in a task must never crash the robot app: log them, the controller recovers on its own.
     * viewModelScope already has a SupervisorJob, so one failing coroutine does not stop the others.
     */
    private val safeScope: CoroutineScope = viewModelScope + CoroutineExceptionHandler { _, t -> AppLog.e("Unexpected error", t) }

    private val controller = GuideController(
        scope = safeScope,
        robot = robot,
        voice = if (onRobot) AgentVoice() else SimulatedVoice(),
        brain = if (onRobot) AgentBrain { store.current.useLlm } else NoBrain,
        settingsSource = { store.current },
        clock = { SystemClock.elapsedRealtime() },
    )

    val ui: StateFlow<UiState> = controller.ui

    init {
        AppLog.i(if (onRobot) "Running on the robot" else "No robot found: simulation mode")
    }

    fun onForeground() = controller.onForeground()
    fun onBackground() = controller.onBackground()
    fun onAnyTouch() = controller.onAnyTouch()
    fun onRoomTapped(id: String) = controller.onRoomTapped(id)
    fun onStop() = controller.onStop()
    fun onWayButton() = controller.onWayButton()
    fun onCloseCard() = controller.onCloseCard()
    fun onSpeech(text: String, final: Boolean) = controller.onSpeech(text, final)
    fun onAgentStatus(status: String) = controller.onAgentStatus(status)

    fun setAdminOpen(open: Boolean) = controller.setAdminOpen(open)

    fun saveSettings(settings: Settings) {
        store.save(settings)
        controller.onSettingsChanged()
    }

    fun reloadPlaces() = controller.reloadPlaces()
    fun knownPlaces(): List<MapPlace>? = controller.knownPlaces()
    fun wanderBlocker(): String? = controller.wanderBlocker()
    fun startWanderingNow() = controller.startWanderingNow()
    suspend fun testDrive(point: String): String = controller.testDrive(point)
}
