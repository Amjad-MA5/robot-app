package de.hinterland.smartreceptionist.badge

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera for the badge photo. On the robot this is [RobotCamera], in tests a fake.
 *
 * The preview is already rotated (but not mirrored - the UI mirrors it so the visitor sees
 * themselves like in a mirror). The final photo is never mirrored.
 */
interface PhotoCamera {
    /** Current preview frame (about 8 per second), null = no camera running */
    val preview: StateFlow<Bitmap?>

    /** Which camera is running (for admin/log), e.g. "shared-stream" or "camera2:1" */
    val source: String

    /** Starts the camera. true once the first frame arrived; false = no usable camera. */
    suspend fun open(): Boolean

    /** Current frame as portrait JPEG (3:4), null if there is none. */
    suspend fun takePhoto(): ByteArray?

    /** Releases the camera - afterwards the robot's person detection works normally again. */
    fun close()
}

/** Used while no real camera is set (emulator, tests): badge without photo. */
object NoCamera : PhotoCamera {
    override val preview: StateFlow<Bitmap?> = MutableStateFlow(null)
    override val source: String = "none"
    override suspend fun open(): Boolean = false
    override suspend fun takePhoto(): ByteArray? = null
    override fun close() = Unit
}
