package de.hinterland.wayfinder.robot

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi
import com.ainirobot.coreservice.client.person.PersonApi
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.core.MapPlace
import de.hinterland.wayfinder.core.PlaceParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real robot, via the Orionstar RobotAPI (bundled with the AgentOS SDK).
 *
 * Rules from the SDK documentation that this class follows:
 * - Only the app in the foreground gets the chassis. When RobotOS takes over (emergency stop, low battery,
 *   charging, OTA) we get onSuspend and every call is ignored until onRecovery.
 * - The SDK calls BOTH variants of each listener method (with and without extraData), so every listener
 *   overrides exactly one of them.
 * - A new navigation while the old one is still stopping fails with -1/-6, so starting is retried.
 * - Callbacks arrive on a worker thread; results are handed over with thread-safe primitives.
 */
class RealRobot(private val appContext: Context) : Robot {

    private val reqIds = AtomicInteger(1000)
    private val _state = MutableStateFlow(RobotState())
    override val state: StateFlow<RobotState> = _state.asStateFlow()

    @Volatile private var connecting = false
    @Volatile private var connectStartedAt = 0L
    private val navLock = Mutex()

    private fun nextId() = reqIds.incrementAndGet()

    private val moduleCallback = object : ModuleCallbackApi() {
        // Deprecated speech requests of the old RobotOS; must be accepted
        override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean = true

        override fun onSuspend() {
            AppLog.w("RobotOS took over control (onSuspend)")
            _state.update { it.copy(suspended = true) }
        }

        override fun onRecovery() {
            AppLog.i("Control is back (onRecovery)")
            _state.update { it.copy(suspended = false) }
        }

        override fun onHWReport(function: Int, type: String?, message: String?) {
            AppLog.w("Hardware report: $function $type $message")
        }
    }

    override fun connect() {
        if (_state.value.connected) return
        val now = SystemClock.elapsedRealtime()
        if (connecting && now - connectStartedAt < 15_000) return
        connecting = true
        connectStartedAt = now
        try {
            RobotApi.getInstance().connectServer(appContext, object : ApiListener {
                override fun handleApiDisabled() {
                    connecting = false
                    AppLog.w("RobotAPI disabled")
                    _state.update { it.copy(connected = false) }
                }

                override fun handleApiConnected() {
                    connecting = false
                    runCatching { RobotApi.getInstance().setCallback(moduleCallback) }
                        .onFailure { AppLog.e("setCallback failed", it) }
                    AppLog.i("RobotAPI connected")
                    _state.update { it.copy(connected = true, suspended = false) }
                }

                override fun handleApiDisconnected() {
                    connecting = false
                    AppLog.w("RobotAPI disconnected")
                    _state.update { it.copy(connected = false) }
                }
            })
        } catch (t: Throwable) {
            connecting = false
            AppLog.e("connectServer failed", t)
        }
    }

    private val usable: Boolean get() = _state.value.connected && !_state.value.suspended

    override suspend fun places(): List<MapPlace>? {
        if (!_state.value.connected) return null
        val done = CompletableDeferred<List<MapPlace>?>()
        val listener = object : CommandListener() {
            override fun onResult(result: Int, message: String?) {
                done.complete(PlaceParser.parse(message))
            }
        }
        val accepted = runCatching { RobotApi.getInstance().getPlaceList(nextId(), listener) }
            .onFailure { AppLog.e("getPlaceList failed", it) }
            .getOrDefault(-1)
        return withTimeoutOrNull(if (accepted < 0) 2_000L else 6_000L) { done.await() }
    }

    override suspend fun navigate(place: String, speed: Double, tolerance: Double, onEvent: (NavEvent) -> Unit): NavResult {
        if (!_state.value.connected) return NavResult.Failed(NavResult.NOT_CONNECTED, "RobotAPI is not connected")
        if (_state.value.suspended) return NavResult.Failed(NavResult.SUSPENDED, "The robot system has taken over")
        val linear = speed.coerceIn(0.1, 0.85)
        // Ratio recommended by the RobotAPI documentation: angular = 0.4 + (linear - 0.1) / 3 * 4
        val angular = (0.4 + (linear - 0.1) / 3.0 * 4.0).coerceIn(0.4, 1.4)
        return navLock.withLock {
            var attempt = 0
            while (true) {
                val result = withTimeoutOrNull(MAX_DRIVE_MS) { driveOnce(place, tolerance, linear, angular, onEvent) }
                    ?: NavResult.Failed(NavResult.TIMEOUT, "The drive took too long").also { stopNavigation() }
                val busy = result is NavResult.Failed && result.code in BUSY_CODES
                if (busy && attempt < 3 && usable) {
                    // The previous drive is still stopping - try again shortly
                    attempt++
                    AppLog.w("Navigation to '$place' is busy (${(result as NavResult.Failed).code}), retry $attempt")
                    stopNavigation()
                    delay(700L * attempt)
                    continue
                }
                return@withLock result
            }
            @Suppress("UNREACHABLE_CODE")
            NavResult.Cancelled
        }
    }

    private suspend fun driveOnce(place: String, tolerance: Double, linear: Double, angular: Double, onEvent: (NavEvent) -> Unit): NavResult {
        val id = nextId()
        val done = CompletableDeferred<NavResult>()
        val listener = object : ActionListener() {
            override fun onResult(status: Int, response: String?) {
                AppLog.i("Navigation '$place': result $status $response")
                done.complete(
                    when {
                        status == Definition.RESULT_OK && response == "true" -> NavResult.Arrived
                        status == Definition.ACTION_RESPONSE_STOP_SUCCESS -> NavResult.Cancelled
                        else -> NavResult.Failed(status, response?.takeIf { it.isNotBlank() } ?: "Navigation failed")
                    },
                )
            }

            override fun onError(errorCode: Int, errorString: String?) {
                AppLog.w("Navigation '$place': error $errorCode $errorString")
                done.complete(
                    if (errorCode == Definition.ERROR_IN_DESTINATION) NavResult.Arrived
                    else NavResult.Failed(errorCode, describe(errorCode, errorString)),
                )
            }

            override fun onStatusUpdate(status: Int, data: String?) {
                when (status) {
                    Definition.STATUS_START_NAVIGATION -> onEvent(NavEvent.STARTED)
                    Definition.STATUS_NAVI_AVOID, Definition.STATUS_NAVI_OBSTACLES_AVOID -> onEvent(NavEvent.BLOCKED)
                    Definition.STATUS_NAVI_AVOID_END -> onEvent(NavEvent.UNBLOCKED)
                    Definition.STATUS_GOAL_OCCLUDED -> onEvent(NavEvent.DESTINATION_OCCUPIED)
                    Definition.STATUS_GOAL_OCCLUDED_END -> onEvent(NavEvent.DESTINATION_FREE)
                    Definition.STATUS_NAVI_MULTI_ROBOT_WAITING -> onEvent(NavEvent.WAITING_FOR_ROBOT)
                    Definition.STATUS_NAVI_MULTI_ROBOT_WAITING_END -> onEvent(NavEvent.UNBLOCKED)
                    Definition.STATUS_ESTIMATE_LOST -> onEvent(NavEvent.LOCALIZATION_LOST)
                    Definition.STATUS_NAVI_OUT_MAP, Definition.STATUS_NAVI_GLOBAL_PATH_FAILED -> {
                        // Documented: navigation cannot succeed any more, stop it
                        AppLog.w("Navigation '$place': status $status (no path)")
                        runCatching { RobotApi.getInstance().stopNavigation(id) }
                        done.complete(
                            NavResult.Failed(
                                NavResult.UNREACHABLE,
                                if (status == Definition.STATUS_NAVI_OUT_MAP) "The destination is outside the map" else "No path to the destination",
                            ),
                        )
                    }
                }
            }
        }
        try {
            val accepted = runCatching {
                RobotApi.getInstance().startNavigation(id, place, tolerance, OBSTACLE_TIMEOUT_MS, linear, angular, listener)
            }.onFailure { AppLog.e("startNavigation failed", it) }.getOrDefault(-1)
            if (accepted < 0) {
                // Not accepted: usually an error callback follows right away, otherwise give up
                return withTimeoutOrNull(2_000) { done.await() }
                    ?: NavResult.Failed(NavResult.START_FAILED, "Navigation was not accepted ($accepted)")
            }
            return done.await()
        } finally {
            // Coroutine cancelled (stop button, visitor interrupted the tour) or timed out: stop the wheels
            if (!done.isCompleted) runCatching { RobotApi.getInstance().stopNavigation(id) }
        }
    }

    override fun stopNavigation() {
        runCatching { RobotApi.getInstance().stopNavigation(nextId()) }
    }

    override suspend fun facesWithin(meters: Double): Int {
        if (!usable) return 0
        return withContext(Dispatchers.IO) {
            runCatching { PersonApi.getInstance().getAllFaceList(meters)?.size ?: 0 }.getOrDefault(0)
        }
    }

    override suspend fun battery(): Int? {
        if (!_state.value.connected) return null
        return withContext(Dispatchers.IO) {
            runCatching { RobotApi.getInstance().batteryLevel }.getOrNull()?.takeIf { it in 1..100 }
        }
    }

    override suspend fun isLocalized(): Boolean? {
        if (!usable) return null
        return withContext(Dispatchers.IO) { runCatching { RobotApi.getInstance().isRobotEstimate }.getOrNull() }
    }

    override suspend fun hasChargingPile(): Boolean {
        if (!_state.value.connected) return false
        return withContext(Dispatchers.IO) { runCatching { RobotApi.getInstance().isChargePileExits }.getOrDefault(false) }
    }

    override suspend fun goCharge(): NavResult {
        if (!usable) return NavResult.Failed(NavResult.NOT_CONNECTED, "RobotAPI is not available")
        return navLock.withLock {
            val id = nextId()
            val done = CompletableDeferred<NavResult>()
            val listener = object : ActionListener() {
                override fun onResult(status: Int, response: String?) {
                    AppLog.i("Auto charge: result $status $response")
                    done.complete(
                        when (status) {
                            Definition.RESULT_OK -> NavResult.Arrived
                            Definition.ACTION_RESPONSE_STOP_SUCCESS -> NavResult.Cancelled
                            else -> NavResult.Failed(status, response ?: "Charging failed")
                        },
                    )
                }

                override fun onError(errorCode: Int, errorString: String?) {
                    AppLog.w("Auto charge: error $errorCode $errorString")
                    done.complete(NavResult.Failed(errorCode, describe(errorCode, errorString)))
                }
            }
            try {
                val accepted = runCatching { RobotApi.getInstance().startNaviToAutoChargeAction(id, CHARGE_TIMEOUT_MS, listener) }
                    .getOrDefault(-1)
                if (accepted < 0) {
                    withTimeoutOrNull(2_000) { done.await() } ?: NavResult.Failed(NavResult.START_FAILED, "Charging was not accepted")
                } else {
                    withTimeoutOrNull(CHARGE_TIMEOUT_MS + 30_000) { done.await() } ?: NavResult.Failed(NavResult.TIMEOUT, "Charging took too long")
                }
            } finally {
                if (!done.isCompleted) runCatching { RobotApi.getInstance().stopAutoChargeAction(id, true) }
            }
        }
    }

    override fun resetHead() {
        if (!usable) return
        runCatching { RobotApi.getInstance().resetHead(nextId(), object : CommandListener() {}) }
    }

    companion object {
        /** Obstacle timeout: if the robot moves less than 0.1 m in this time, the drive fails (documented: 30 s) */
        private const val OBSTACLE_TIMEOUT_MS = 30_000L
        private const val MAX_DRIVE_MS = 5 * 60_000L
        private const val CHARGE_TIMEOUT_MS = 5 * 60_000L

        private val BUSY_CODES = setOf(
            Definition.ACTION_RESPONSE_ALREADY_RUN,
            Definition.ACTION_RESPONSE_REQUEST_RES_ERROR,
            NavResult.START_FAILED,
        )

        /** True on an Orionstar robot (RobotOS CoreService installed). */
        fun isRobotOs(context: Context): Boolean = try {
            context.packageManager.getPackageInfo("com.ainirobot.coreservice", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }

        fun describe(code: Int, fallback: String?): String = when (code) {
            Definition.ERROR_NOT_ESTIMATE -> "The robot is not localized"
            Definition.ERROR_DESTINATION_NOT_EXIST -> "The point does not exist on the current map"
            Definition.ERROR_DESTINATION_CAN_NOT_ARRAIVE -> "The way is blocked (obstacle timeout)"
            Definition.ERROR_NAVIGATION_AVOID_TIMEOUT -> "The way is blocked (obstacle timeout)"
            Definition.ACTION_RESPONSE_ALREADY_RUN -> "Another drive is still running"
            Definition.ACTION_RESPONSE_REQUEST_RES_ERROR -> "The wheels are busy with another task"
            Definition.ACTION_RESPONSE_RES_UNAVAILBALE -> "The wheels are not available"
            Definition.ERROR_MULTI_ROBOT_WAITING_TIMEOUT -> "Waited too long for another robot"
            Definition.ERROR_WHEEL_OVER_CURRENT_RUN_OUT -> "The wheels are stuck"
            Definition.ERROR_NAVIGATION_FAILED -> "Navigation failed"
            else -> fallback?.takeIf { it.isNotBlank() } ?: "Error $code"
        }
    }
}
