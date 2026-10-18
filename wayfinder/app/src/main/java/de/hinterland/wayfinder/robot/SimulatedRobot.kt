package de.hinterland.wayfinder.robot

import de.hinterland.wayfinder.core.MapPlace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Pretend robot for the emulator or a normal tablet: a small map, drives take a few seconds.
 * Lets the team try the whole app (and the admin screen) without the real robot.
 */
class SimulatedRobot(private val timeScale: Double = 0.35) : Robot {

    private val _state = MutableStateFlow(RobotState(connected = true, simulated = true))
    override val state: StateFlow<RobotState> = _state.asStateFlow()

    private val map = listOf(
        MapPlace("Reception", 0.0, 0.0, type = MapPlace.TYPE_RECEPTION),
        MapPlace("Main Stage", 12.0, 4.0),
        MapPlace("Workshop Room", 8.0, -6.0),
        MapPlace("Coffee Bar", -5.0, 7.0),
        MapPlace("Exit", -9.0, -2.0),
        MapPlace("Stairs", 3.0, 9.0),
        MapPlace("Charging Pile", 1.0, -1.0, type = MapPlace.TYPE_CHARGING),
    )
    private var position = map.first()
    private var battery = 86

    override suspend fun places(): List<MapPlace> {
        delay(300)
        return map
    }

    override suspend fun navigate(place: String, speed: Double, tolerance: Double, onEvent: (NavEvent) -> Unit): NavResult {
        val target = map.firstOrNull { it.name == place }
            ?: return NavResult.Failed(-108, "The point does not exist on the current map")
        val meters = hypot(target.x - position.x, target.y - position.y)
        if (meters <= tolerance) return NavResult.Arrived
        onEvent(NavEvent.STARTED)
        val totalMs = (meters / speed.coerceAtLeast(0.1) * 1000 * timeScale).toLong().coerceIn(1_500, 20_000)
        if (Random.nextInt(4) == 0) {
            delay(totalMs / 3)
            onEvent(NavEvent.BLOCKED)
            delay(1_800)
            onEvent(NavEvent.UNBLOCKED)
            delay(totalMs * 2 / 3)
        } else {
            delay(totalMs)
        }
        position = target
        battery = (battery - 1).coerceAtLeast(5)
        return NavResult.Arrived
    }

    override fun stopNavigation() {}

    override suspend fun facesWithin(meters: Double): Int = 0

    override suspend fun battery(): Int = battery

    override suspend fun isLocalized(): Boolean = true

    override suspend fun hasChargingPile(): Boolean = true

    override suspend fun goCharge(): NavResult {
        delay(3_000)
        battery = 100
        return NavResult.Arrived
    }

    override fun resetHead() {}
}
