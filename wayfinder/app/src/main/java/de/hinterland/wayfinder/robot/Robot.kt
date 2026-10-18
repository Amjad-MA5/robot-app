package de.hinterland.wayfinder.robot

import de.hinterland.wayfinder.core.MapPlace
import kotlinx.coroutines.flow.StateFlow

/** Result of one drive to a map point. */
sealed interface NavResult {
    data object Arrived : NavResult
    /** Stopped on purpose (stop button, new task) */
    data object Cancelled : NavResult
    data class Failed(val code: Int, val message: String) : NavResult {
        val notLocalized: Boolean get() = code == NOT_LOCALIZED
    }

    companion object {
        /** RobotAPI Definition.ERROR_NOT_ESTIMATE */
        const val NOT_LOCALIZED = -116
        const val NOT_CONNECTED = -1000
        const val SUSPENDED = -1001
        const val START_FAILED = -1002
        const val TIMEOUT = -1003
        const val UNREACHABLE = -1004
    }
}

/** Things that happen while driving; shown on screen, some are spoken. */
enum class NavEvent { STARTED, BLOCKED, UNBLOCKED, DESTINATION_OCCUPIED, DESTINATION_FREE, WAITING_FOR_ROBOT, LOCALIZATION_LOST }

data class RobotState(
    /** RobotAPI connected and this app has the chassis */
    val connected: Boolean = false,
    /** RobotOS has taken over (emergency stop, charging, low battery, OTA ...); every call is ignored meanwhile */
    val suspended: Boolean = false,
    /** No real robot (emulator / tablet): everything is simulated */
    val simulated: Boolean = false,
)

/**
 * The robot's body: map, wheels, eyes and battery. On the robot this is [RealRobot] (Orionstar RobotAPI),
 * elsewhere [SimulatedRobot]. Tests use their own fake.
 */
interface Robot {
    val state: StateFlow<RobotState>

    /** (Re)connects to the robot service if needed. Cheap to call often. */
    fun connect() {}

    /** Points of the current map; null = could not be loaded (not connected, timeout) */
    suspend fun places(): List<MapPlace>?

    /**
     * Drives to the map point [place] and suspends until it arrived or failed. Cancelling the coroutine
     * stops the robot. [tolerance] = meters around the point that count as arrived.
     */
    suspend fun navigate(place: String, speed: Double, tolerance: Double, onEvent: (NavEvent) -> Unit): NavResult

    fun stopNavigation()

    /** Number of faces within [meters] in front of the robot (0 if unknown) */
    suspend fun facesWithin(meters: Double): Int

    /** Battery level 1..100, null = unknown */
    suspend fun battery(): Int?

    /** Does the robot know where it is on the map? null = unknown */
    suspend fun isLocalized(): Boolean?

    suspend fun hasChargingPile(): Boolean

    /** Drives to the charging pile and docks. Arrived = charging. */
    suspend fun goCharge(): NavResult

    fun resetHead()
}
