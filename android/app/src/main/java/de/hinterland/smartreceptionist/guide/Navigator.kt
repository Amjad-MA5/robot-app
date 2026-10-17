package de.hinterland.smartreceptionist.guide

import de.hinterland.smartreceptionist.robot.NavEvent
import de.hinterland.smartreceptionist.robot.NavResult
import de.hinterland.smartreceptionist.robot.RobotController

/**
 * Schnittstelle zum Fahrwerk – damit die Wegweiser-Logik auch ohne echten Roboter
 * (Unit-Test, Simulation) laufen kann. Auf dem Roboter steckt [RobotNavigator] dahinter.
 */
interface Navigator {
    val isConnected: Boolean
    suspend fun places(): List<MapPlace>
    /** Abstand des Roboters zum Kartenpunkt in Metern, null wenn unbekannt */
    fun distanceTo(place: String): Double?
    suspend fun navigateTo(place: String, speed: Float, onEvent: (NavEvent) -> Unit): NavResult
}

object RobotNavigator : Navigator {
    override val isConnected: Boolean get() = RobotController.connected.value
    override suspend fun places(): List<MapPlace> = RobotController.mapPlaces()
    override fun distanceTo(place: String): Double? = RobotController.distanceTo(place)
    override suspend fun navigateTo(place: String, speed: Float, onEvent: (NavEvent) -> Unit): NavResult =
        RobotController.navigateTo(place, speed, onEvent)
}

/**
 * Hält die Kartenpunkte vor, damit nicht bei jeder Frage die RobotAPI gefragt werden muss.
 * Neu angelegte Punkte (Robot Portal → Advanced Map Editor → Post) sind spätestens nach
 * [maxAgeMs] bekannt – oder sofort nach einem Neustart der App.
 */
class PlaceCache(
    private val source: suspend () -> List<MapPlace>,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 60_000,
) {
    @Volatile private var places: List<MapPlace> = emptyList()
    @Volatile private var loadedAt: Long = 0

    /** Zuletzt bekannte Punkte (ohne zu warten) */
    val cached: List<MapPlace> get() = places

    suspend fun get(forceRefresh: Boolean = false): List<MapPlace> {
        val stale = now() - loadedAt > maxAgeMs
        if (forceRefresh || stale || places.isEmpty()) {
            val fresh = try {
                source()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
            if (fresh.isNotEmpty()) {
                places = fresh
                loadedAt = now()
            }
        }
        return places
    }
}
