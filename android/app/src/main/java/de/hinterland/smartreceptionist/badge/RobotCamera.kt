package de.hinterland.smartreceptionist.badge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareApi
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareBean
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareListener
import de.hinterland.smartreceptionist.robot.RobotController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Camera settings from the admin area. */
data class CameraConfig(
    /** Rotation in degrees (0/90/180/270), -1 = automatic depending on the camera source */
    val rotation: Int = -1,
    /** "auto" = robot's shared video stream first, then the Android camera; "shared" / "camera2" = only that one */
    val source: String = "auto",
)

/**
 * Camera of the robot - two ways, following the Orionstar RobotAPI docs (section "Camera"):
 *
 * 1. **Shared video stream** (SurfaceShareApi): frames come from the robot's VisionSDK, so person
 *    detection and greeting keep working. 640 x 480, YUV.
 * 2. **Android camera** (Camera2): if the shared stream is not available. Person detection pauses
 *    and comes back after the camera is released. On landscape robots the image is rotated by 90°,
 *    so it is rotated back by 270° by default (can be changed in the admin area).
 *
 * If neither works (no permission, camera busy ...), [open] returns false and the badge is
 * printed without a photo.
 */
class RobotCamera(
    context: Context,
    private val config: () -> CameraConfig,
) : PhotoCamera {
    private val appContext = context.applicationContext

    private val _preview = MutableStateFlow<Bitmap?>(null)
    override val preview: StateFlow<Bitmap?> = _preview

    @Volatile override var source: String = "none"
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var shareBean: SurfaceShareBean? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var rotation = 0
    @Volatile private var lastFrameAt = 0L
    @Volatile private var failed = false

    // -------------------------------------------------------------------- Open
    override suspend fun open(): Boolean {
        close()
        val cfg = config()
        if (cfg.source != "camera2" && RobotController.connected.value) {
            if (openShared(cfg) && awaitFirstFrame(2_500)) return true
            close()
        }
        if (cfg.source != "shared") {
            if (openCamera2(cfg) && awaitFirstFrame(4_000)) return true
            close()
        }
        Log.w(TAG, "No camera available - badge without photo")
        return false
    }

    private suspend fun awaitFirstFrame(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (_preview.value == null && !failed) delay(50)
            _preview.value != null
        } ?: false

    private fun startThread(): Handler {
        val t = HandlerThread("badge-camera").also { it.start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    private fun newReader(width: Int, height: Int, h: Handler): ImageReader {
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        r.setOnImageAvailableListener({ onImage(it) }, h)
        reader = r
        return r
    }

    /** Way 1: shared video stream from the robot's VisionSDK */
    private fun openShared(cfg: CameraConfig): Boolean = runCatching {
        failed = false
        rotation = if (cfg.rotation >= 0) cfg.rotation else 0
        val h = startThread()
        val r = newReader(640, 480, h)
        val bean = SurfaceShareBean().apply { name = "SmartReceptionistBadge" }
        shareBean = bean
        val code = SurfaceShareApi.getInstance().requestImageFrame(r.surface, bean, object : SurfaceShareListener() {
            override fun onError(error: Int, message: String?) {
                Log.w(TAG, "Shared camera stream: error $error $message")
                failed = true
            }
        })
        source = "shared-stream"
        Log.i(TAG, "Shared camera stream requested (result $code)")
        true
    }.getOrElse {
        Log.w(TAG, "Shared camera stream not available", it)
        false
    }

    /** Way 2: regular Android camera (front camera preferred) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera2(cfg: CameraConfig): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission missing")
            return false
        }
        return runCatching {
            failed = false
            val manager = appContext.getSystemService(CameraManager::class.java) ?: return false
            val ids = manager.cameraIdList.toList()
            val id = ids.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: ids.firstOrNull() ?: return false
            val characteristics = manager.getCameraCharacteristics(id)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toList()
                .orEmpty()
            val size = sizes.minByOrNull { abs(it.width * it.height - 640 * 480) + abs(it.width * 3 - it.height * 4) } ?: Size(640, 480)
            // Landscape robot: per the docs the image is off by 90° -> rotate back by 270°
            rotation = if (cfg.rotation >= 0) cfg.rotation else 270
            val h = startThread()
            val r = newReader(size.width, size.height, h)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runCatching {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                                    s.setRepeatingRequest(request.build(), null, h)
                                }.onFailure { failed = true }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                failed = true
                            }
                        }, h)
                    }.onFailure { failed = true }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    failed = true
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera2 error $error")
                    camera.close()
                    failed = true
                }
            }, h)
            source = "camera2:$id ${size.width}x${size.height}"
            true
        }.getOrElse {
            Log.w(TAG, "Camera2 not available", it)
            false
        }
    }

    // ------------------------------------------------------------------ Frames
    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS && _preview.value != null) return
            lastFrameAt = now
            yuvToBitmap(image, rotation)?.let { _preview.value = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not convert frame", t)
        } finally {
            image.close()
        }
    }

    override suspend fun takePhoto(): ByteArray? = withContext(Dispatchers.Default) {
        val frame = _preview.value ?: return@withContext null
        val portrait = cropToPortrait(frame)
        ByteArrayOutputStream().use { out ->
            portrait.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }

    // ----------------------------------------------------------------- Release
    override fun close() {
        shareBean?.let { bean -> runCatching { SurfaceShareApi.getInstance().abandonImageFrame(bean) } }
        shareBean = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
        _preview.value = null
        source = "none"
    }

    companion object {
        private const val TAG = "RobotCamera"
        private const val FRAME_INTERVAL_MS = 120L

        /** YUV_420_888 -> NV21 -> JPEG -> Bitmap (simple and stable on all devices). */
        fun yuvToBitmap(image: Image, rotation: Int): Bitmap? {
            val width = image.width
            val height = image.height
            val nv21 = toNv21(image)
            val jpeg = ByteArrayOutputStream().use { out ->
                YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 85, out)
                out.toByteArray()
            }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            if (rotation % 360 == 0) return bitmap
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        private fun toNv21(image: Image): ByteArray {
            val width = image.width
            val height = image.height
            val out = ByteArray(width * height * 3 / 2)
            val y = image.planes[0]
            val yBuf = y.buffer
            var pos = 0
            for (row in 0 until height) {
                if (y.pixelStride == 1) {
                    yBuf.position(row * y.rowStride)
                    yBuf.get(out, pos, width)
                    pos += width
                } else {
                    for (col in 0 until width) out[pos++] = yBuf.get(row * y.rowStride + col * y.pixelStride)
                }
            }
            val u = image.planes[1]
            val v = image.planes[2]
            val uBuf = u.buffer
            val vBuf = v.buffer
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    out[pos++] = vBuf.get(row * v.rowStride + col * v.pixelStride)
                    out[pos++] = uBuf.get(row * u.rowStride + col * u.pixelStride)
                }
            }
            return out
        }

        /** Centred 3:4 portrait crop - like a passport photo, slightly shifted up. */
        fun cropToPortrait(src: Bitmap): Bitmap {
            val targetRatio = 3f / 4f
            val ratio = src.width.toFloat() / src.height
            return if (ratio > targetRatio) {
                val w = (src.height * targetRatio).roundToInt()
                Bitmap.createBitmap(src, (src.width - w) / 2, 0, w, src.height)
            } else {
                val h = (src.width / targetRatio).roundToInt().coerceAtMost(src.height)
                val top = ((src.height - h) * 0.35f).roundToInt()
                Bitmap.createBitmap(src, 0, top, src.width, h)
            }
        }
    }
}
