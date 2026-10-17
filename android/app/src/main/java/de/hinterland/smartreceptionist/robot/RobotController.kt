package de.hinterland.smartreceptionist.robot

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi
import com.ainirobot.coreservice.client.person.PersonApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Ergebnis einer Navigationsfahrt. */
sealed class NavResult {
    data object Arrived : NavResult()
    data object Cancelled : NavResult()
    data class Failed(val code: Int, val message: String) : NavResult()
}

/** Zwischenstände während der Fahrt (für Anzeige/Sprachausgabe). */
enum class NavEvent { STARTED, BLOCKED, UNBLOCKED, MULTI_ROBOT_WAIT }

/**
 * Kapselt die RobotAPI (Navigation, Personenerkennung, Kopf).
 *
 * Wichtig laut Orionstar-Doku: Die App bekommt die Fahrwerk-Rechte nur, solange sie im
 * Vordergrund läuft. Übernimmt das System (Not-Aus, Akku leer, Laden …), kommt onSuspend;
 * danach sind alle Aufrufe wirkungslos, bis onRecovery kommt. Wir blockieren nichts davon.
 */
object RobotController {
    private const val TAG = "RobotController"
    private val reqIds = AtomicInteger(100)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** true, solange das System die Kontrolle übernommen hat (Not-Aus, Laden, Update …) */
    private val _suspended = MutableStateFlow(false)
    val suspended: StateFlow<Boolean> = _suspended

    private var connecting = false

    fun isCoreServiceInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo("com.ainirobot.coreservice", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private val moduleCallback = object : ModuleCallbackApi() {
        override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean = true

        override fun onSuspend() {
            Log.i(TAG, "System hat die Kontrolle übernommen (onSuspend)")
            _suspended.value = true
        }

        override fun onRecovery() {
            Log.i(TAG, "Kontrolle zurück (onRecovery)")
            _suspended.value = false
        }
    }

    fun connect(context: Context) {
        if (!isCoreServiceInstalled(context)) {
            Log.i(TAG, "CoreService is not installed; RobotAPI remains disabled")
            return
        }
        if (_connected.value || connecting) return
        connecting = true
        try {
            RobotApi.getInstance().connectServer(context.applicationContext, object : ApiListener {
                override fun handleApiDisabled() {
                    Log.w(TAG, "RobotAPI deaktiviert")
                    connecting = false
                    _connected.value = false
                }

                override fun handleApiConnected() {
                    Log.i(TAG, "RobotAPI verbunden")
                    connecting = false
                    _connected.value = true
                    RobotApi.getInstance().setCallback(moduleCallback)
                }

                override fun handleApiDisconnected() {
                    Log.w(TAG, "RobotAPI getrennt")
                    connecting = false
                    _connected.value = false
                }
            })
        } catch (t: Throwable) {
            // Läuft die App nicht auf einem Orionstar-Roboter (z. B. Emulator), gibt es keinen Dienst.
            Log.e(TAG, "connectServer fehlgeschlagen", t)
            connecting = false
        }
    }

    private fun nextReq() = reqIds.incrementAndGet()

    /** Anzahl Personen (Gesicht oder Körper) innerhalb von [maxDistance] Metern. */
    fun personsWithin(maxDistance: Double): Int {
        if (!_connected.value) return 0
        return try {
            PersonApi.getInstance().getAllPersons(maxDistance)?.size ?: 0
        } catch (t: Throwable) {
            Log.w(TAG, "getAllPersons fehlgeschlagen", t)
            0
        }
    }

    /** Alle Ortsnamen der aktuellen Karte. */
    suspend fun placeNames(): List<String> {
        if (!_connected.value) return emptyList()
        return withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { cont ->
                val ok = runCatching {
                    RobotApi.getInstance().getPlaceList(nextReq(), object : CommandListener() {
                        override fun onResult(result: Int, message: String?) {
                            val names = runCatching {
                                val arr = JSONArray(message ?: "[]")
                                (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name").takeIf { n -> n.isNotBlank() } }
                            }.getOrDefault(emptyList())
                            if (cont.isActive) cont.resume(names)
                        }
                    })
                }.isSuccess
                if (!ok && cont.isActive) cont.resume(emptyList())
            }
        } ?: emptyList()
    }

    fun isLocalized(): Boolean = _connected.value && runCatching { RobotApi.getInstance().isRobotEstimate() }.getOrDefault(false)

    /**
     * Fährt zum Kartenpunkt [place]. Suspendiert bis Ankunft/Fehler. Wird die Coroutine
     * abgebrochen, wird die Navigation gestoppt.
     */
    suspend fun navigateTo(place: String, speed: Float, onEvent: (NavEvent) -> Unit = {}): NavResult {
        if (!_connected.value) return NavResult.Failed(-1000, "RobotAPI is not connected")
        if (_suspended.value) return NavResult.Failed(-1001, "The robot is currently blocked by the system")
        val linear = speed.toDouble().coerceIn(0.1, 0.85)
        // Empfohlenes Verhältnis laut Doku: angular = 0.4 + (linear − 0.1) / 3 × 4
        val angular = (0.4 + (linear - 0.1) / 3.0 * 4.0).coerceIn(0.4, 1.4)
        val id = nextReq()
        return suspendCancellableCoroutine { cont ->
            val listener = object : ActionListener() {
                override fun onResult(status: Int, response: String?) {
                    Log.i(TAG, "Navigation $place onResult status=$status response=$response")
                    val result = when {
                        status == Definition.RESULT_OK && response == "true" -> NavResult.Arrived
                        status == Definition.ACTION_RESPONSE_STOP_SUCCESS -> NavResult.Cancelled
                        else -> NavResult.Failed(status, response ?: "Navigation fehlgeschlagen")
                    }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onError(errorCode: Int, errorString: String?) {
                    Log.w(TAG, "Navigation $place onError $errorCode $errorString")
                    val result = if (errorCode == Definition.ERROR_IN_DESTINATION) {
                        NavResult.Arrived // steht schon am Ziel
                    } else {
                        NavResult.Failed(errorCode, describeError(errorCode, errorString))
                    }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onStatusUpdate(status: Int, data: String?) {
                    when (status) {
                        Definition.STATUS_START_NAVIGATION -> onEvent(NavEvent.STARTED)
                        Definition.STATUS_NAVI_AVOID -> onEvent(NavEvent.BLOCKED)
                        Definition.STATUS_NAVI_AVOID_END -> onEvent(NavEvent.UNBLOCKED)
                        Definition.STATUS_NAVI_MULTI_ROBOT_WAITING -> onEvent(NavEvent.MULTI_ROBOT_WAIT)
                        Definition.STATUS_NAVI_OUT_MAP, Definition.STATUS_NAVI_GLOBAL_PATH_FAILED -> {
                            runCatching { RobotApi.getInstance().stopNavigation(id) }
                            if (cont.isActive) cont.resume(NavResult.Failed(status, "Destination is unreachable"))
                        }
                    }
                }
            }
            cont.invokeOnCancellation { runCatching { RobotApi.getInstance().stopNavigation(id) } }
            try {
                // coordinateDeviation 0.2 m, Hindernis-Timeout 30 s (Empfehlungen aus der Doku)
                RobotApi.getInstance().startNavigation(id, place, 0.2, 30_000L, linear, angular, listener)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(NavResult.Failed(-1002, t.message ?: "startNavigation fehlgeschlagen"))
            }
        }
    }

    fun stopNavigation() {
        runCatching { RobotApi.getInstance().stopNavigation(nextReq()) }
    }

    fun resetHead() {
        if (!_connected.value) return
        runCatching { RobotApi.getInstance().resetHead(nextReq(), object : CommandListener() {}) }
    }

    /** Installiert eine APK über den RobotOS-Dienst (für In-App-Updates). */
    fun installApk(path: String): Boolean =
        _connected.value && runCatching { RobotApi.getInstance().installApk(nextReq(), path, "smart-receptionist-update") }
            .getOrDefault(false)

    private fun describeError(code: Int, fallback: String?): String = when (code) {
        Definition.ERROR_NOT_ESTIMATE -> "The robot is not localized"
        Definition.ERROR_DESTINATION_NOT_EXIST -> "The destination does not exist on the map"
        Definition.ERROR_DESTINATION_CAN_NOT_ARRAIVE -> "The destination is unreachable (obstacle)"
        Definition.ACTION_RESPONSE_ALREADY_RUN -> "Navigation is already in progress"
        Definition.ACTION_RESPONSE_REQUEST_RES_ERROR -> "The drive system is currently in use"
        Definition.ERROR_MULTI_ROBOT_WAITING_TIMEOUT -> "Timed out waiting for another robot"
        else -> fallback?.takeIf { it.isNotBlank() } ?: "Error $code"
    }
}
