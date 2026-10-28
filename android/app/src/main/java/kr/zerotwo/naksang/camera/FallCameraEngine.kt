package kr.zerotwo.naksang.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One in-memory analysis frame. [compensationDegrees] is the extra clockwise
 * turn applied so a sideways phone still shows an upright person to the pose
 * model. It is 0 when the phone is upright, and the bitmap is then the same
 * one the portrait preview is showing. [previewWidth] and [previewHeight] are
 * the size before that extra turn (90° and 270° swap the bitmap).
 */
data class CameraAnalysisFrame(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val compensationDegrees: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val compensationGeneration: Int,
)

/**
 * Analysis runs around [ANALYSIS_WIDTH]×[ANALYSIS_HEIGHT] and a few frames per second.
 * Frames are decoded in memory, handed to the pose reader, and recycled. Nothing
 * is written to disk or sent.
 *
 * The preview surface is optional and only attached on the placement screen.
 * When a [ViewPort] is supplied it is kept after the preview leaves, so the
 * watching crop matches the framing the user just saw. The lifecycle is owned
 * here so detection continues after the activity pauses.
 *
 * Video replay does not come through here, so device orientation never rotates
 * those frames.
 */
class FallCameraEngine(private val context: Context) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val executor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var viewPort: ViewPort? = null
    private var started = false
    @Volatile private var front = true
    private var lastFrameAt = 0L

    /**
     * Snapped physical orientation, 0/90/180/270. Written on the main thread.
     * Read on the analyzer thread together with [compensationGeneration]:
     * the generation is published second, and read first, so a frame never
     * pairs a new generation with the previous quadrant.
     */
    @Volatile var deviceQuadrant: Int = 0

    @Volatile var compensationGeneration: Int = 0

    var fpsProvider: () -> Int = { 5 }
    var onFrame: ((CameraAnalysisFrame) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLensLabel: ((String) -> Unit)? = null

    /** Last lens or zoom choice, for the model-test screen. */
    @Volatile var lensLabel: String = "렌즈 확인 중"
        private set

    private var zoomObserver: Observer<ZoomState>? = null
    private var zoomSource: LiveData<ZoomState>? = null
    private var appliedMinZoom: Float? = null

    fun setLens(frontCamera: Boolean) {
        if (front == frontCamera) return
        front = frontCamera
        if (started) mainExecutor.execute { bind() }
    }

    fun setPreview(provider: Preview.SurfaceProvider?, port: ViewPort? = null) {
        val nextPort = port ?: viewPort
        val changed = surfaceProvider !== provider || !sameViewPort(viewPort, nextPort)
        surfaceProvider = provider
        viewPort = nextPort
        if (started && changed) mainExecutor.execute { bind() }
    }

    fun start() {
        if (started) return
        started = true
        mainExecutor.execute {
            ensureResumed()
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        provider = future.get()
                        bind()
                    } catch (e: Exception) {
                        Log.e(TAG, "camera provider", e)
                        onError?.invoke("카메라를 열지 못했어요")
                    }
                },
                mainExecutor,
            )
        }
    }

    fun stop() {
        if (!started && provider == null) return
        started = false
        mainExecutor.execute {
            provider?.unbindAll()
            if (registry.currentState == Lifecycle.State.RESUMED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (registry.currentState == Lifecycle.State.STARTED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Suppress("DEPRECATION")
    private fun bind() {
        val cameraProvider = provider ?: return
        if (!started) return
        ensureResumed()
        clearZoomWatch()
        val group = buildUseCases()
        if (!front && bindWidestBack(cameraProvider, group)) return
        val primary = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val fallback = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val bound = bindSelector(cameraProvider, group, primary, fallback)
        if (bound == null) {
            publishLens("렌즈를 열지 못했어요")
            return
        }
        val frontBound = bound == CameraSelector.DEFAULT_FRONT_CAMERA
        if (front && frontBound) {
            publishLens("전면 기본 렌즈")
        } else if (!front) {
            publishLens("기본 렌즈(넓은 화각 실패)")
        } else {
            publishLens("후방 기본 렌즈(전면 실패)")
        }
    }

    @Suppress("DEPRECATION")
    private fun buildUseCases(): UseCaseGroup {
        val port = viewPort
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .build()
        if (port != null) analysis.targetRotation = port.rotation
        analysis.setAnalyzer(executor, ::analyze)
        val preview = surfaceProvider?.let { surface ->
            Preview.Builder().build().also { previewUseCase ->
                if (port != null) previewUseCase.targetRotation = port.rotation
                previewUseCase.setSurfaceProvider(surface)
            }
        }
        return UseCaseGroup.Builder().apply {
            if (port != null) setViewPort(port)
            if (preview != null) addUseCase(preview)
            addUseCase(analysis)
        }.build()
    }

    /**
     * Prefer the back camera with the shortest focal length. If that lens is
     * not distinct, or binding it throws, the caller falls back to the default
     * camera. Analysis stays at [ANALYSIS_WIDTH]×[ANALYSIS_HEIGHT].
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindWidestBack(cameraProvider: ProcessCameraProvider, group: UseCaseGroup): Boolean {
        return try {
            val focals = backFocalLengths(cameraProvider)
            when (chooseBackLens(focals)) {
                BackLensChoice.ULTRA_WIDE -> {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(this, widestBackSelector(), group)
                    val focal = minFocalMm(camera.cameraInfo)
                    val focalText = if (focal != null) " · 초점 ${formatFocal(focal)}mm" else ""
                    publishLens("후방 초광각$focalText")
                    Log.i(TAG, "lens ${lensLabel}")
                    true
                }
                BackLensChoice.MIN_ZOOM -> {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    publishLens("후방 기본 렌즈 · 줌 확인 중")
                    watchMinZoom(camera)
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wide back camera failed", e)
            false
        }
    }

    private fun bindSelector(
        cameraProvider: ProcessCameraProvider,
        group: UseCaseGroup,
        primary: CameraSelector,
        fallback: CameraSelector,
    ): CameraSelector? {
        return try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, primary, group)
            primary
        } catch (first: Exception) {
            Log.w(TAG, "primary camera failed, trying the other lens", first)
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, fallback, group)
                fallback
            } catch (second: Exception) {
                Log.e(TAG, "bind failed", second)
                onError?.invoke("카메라를 열지 못했어요")
                null
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun widestBackSelector(): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                val best = infos.minByOrNull { minFocalMm(it) ?: Float.MAX_VALUE }
                if (best == null) infos else listOf(best)
            }
            .build()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun backFocalLengths(cameraProvider: ProcessCameraProvider): List<Float?> {
        return cameraProvider.availableCameraInfos.mapNotNull { info ->
            val facing = runCatching { info.lensFacing }.getOrNull() ?: return@mapNotNull null
            if (facing != CameraSelector.LENS_FACING_BACK) return@mapNotNull null
            minFocalMm(info)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun minFocalMm(info: CameraInfo): Float? {
        return try {
            val lengths = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            lengths?.filter { it > 0f }?.minOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "focal length", e)
            null
        }
    }

    private fun watchMinZoom(camera: Camera) {
        val live = camera.cameraInfo.zoomState
        val current = live.value
        if (current != null) {
            applyMinZoom(camera, current.minZoomRatio)
        }
        val observer = Observer<ZoomState> { zoom ->
            applyMinZoom(camera, zoom.minZoomRatio)
        }
        zoomSource = live
        zoomObserver = observer
        live.observe(this, observer)
    }

    private fun applyMinZoom(camera: Camera, minZoom: Float) {
        val already = appliedMinZoom
        if (already != null && minZoom >= already - 0.001f) return
        appliedMinZoom = minZoom
        val labelZoom = "후방 기본 렌즈 · 최소 줌 ${formatZoom(minZoom)}"
        if (minZoom >= 0.999f) {
            publishLens(labelZoom)
            Log.i(TAG, "lens $labelZoom")
            return
        }
        val future = camera.cameraControl.setZoomRatio(minZoom)
        future.addListener(
            {
                val ok = runCatching { future.get() }.isSuccess
                val label = if (ok) labelZoom else "후방 기본 렌즈(줌 실패)"
                publishLens(label)
                Log.i(TAG, "lens $label")
            },
            mainExecutor,
        )
    }

    private fun clearZoomWatch() {
        val source = zoomSource
        val observer = zoomObserver
        if (source != null && observer != null) source.removeObserver(observer)
        zoomSource = null
        zoomObserver = null
        appliedMinZoom = null
    }

    private fun publishLens(label: String) {
        lensLabel = label
        val listener = onLensLabel ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener(label)
        } else {
            mainExecutor.execute { listener(label) }
        }
    }

    private fun formatFocal(mm: Float): String = String.format(Locale.US, "%.1f", mm)

    private fun formatZoom(ratio: Float): String = String.format(Locale.US, "%.2f", ratio)

    private fun analyze(image: ImageProxy) {
        try {
            val interval = 1000L / fpsProvider().coerceIn(2, 12)
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < interval) return
            lastFrameAt = now
            val oriented = image.toUprightBitmap() ?: return
            val listener = onFrame
            if (listener == null) {
                oriented.bitmap.recycle()
            } else {
                listener(
                    CameraAnalysisFrame(
                        bitmap = oriented.bitmap,
                        timestampMs = now,
                        compensationDegrees = oriented.compensationDegrees,
                        previewWidth = oriented.previewWidth,
                        previewHeight = oriented.previewHeight,
                        compensationGeneration = oriented.compensationGeneration,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze", e)
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toUprightBitmap(): OrientedBitmap? {
        val src = rgbaToBitmap() ?: return null
        // cropRect is in the unrotated buffer. Crop before rotating so the
        // display bitmap matches the shared ViewPort (FILL_CENTER with the preview).
        val cropped = applyCrop(src, cropRect)
        val displayed = rotateBitmap(cropped, imageInfo.rotationDegrees)
        val previewWidth = displayed.width
        val previewHeight = displayed.height
        // Read generation before the quadrant. The main thread writes the
        // quadrant first and the generation second.
        val generation = compensationGeneration
        val quadrant = deviceQuadrant
        val compensation = analysisCompensationDegrees(quadrant, front)
        val upright = rotateBitmap(displayed, compensation)
        return OrientedBitmap(
            bitmap = upright,
            compensationDegrees = compensation,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            compensationGeneration = generation,
        )
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val rotation = degrees.mod(360)
        if (rotation == 0) return src
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated != src) src.recycle()
        return rotated
    }

    private class OrientedBitmap(
        val bitmap: Bitmap,
        val compensationDegrees: Int,
        val previewWidth: Int,
        val previewHeight: Int,
        val compensationGeneration: Int,
    )

    private fun applyCrop(src: Bitmap, crop: Rect): Bitmap {
        if (src.width <= 1 || src.height <= 1 || crop.width() <= 1 || crop.height() <= 1) return src
        val left = crop.left.coerceIn(0, src.width - 1)
        val top = crop.top.coerceIn(0, src.height - 1)
        val right = crop.right.coerceIn(left + 1, src.width)
        val bottom = crop.bottom.coerceIn(top + 1, src.height)
        if (left == 0 && top == 0 && right == src.width && bottom == src.height) return src
        val out = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        if (out != src) src.recycle()
        return out
    }

    private fun ImageProxy.rgbaToBitmap(): Bitmap? {
        return try {
            val plane = planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            if (paddedWidth == width) {
                bitmap
            } else {
                val tight = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                tight
            }
        } catch (e: Exception) {
            Log.e(TAG, "rgba", e)
            null
        }
    }

    private fun ensureResumed() {
        when (registry.currentState) {
            Lifecycle.State.INITIALIZED -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.CREATED -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.STARTED -> registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            Lifecycle.State.RESUMED -> Unit
            Lifecycle.State.DESTROYED -> Unit
        }
    }

    private fun sameViewPort(a: ViewPort?, b: ViewPort?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.rotation == b.rotation && a.scaleType == b.scaleType && a.aspectRatio == b.aspectRatio
    }

    companion object {
        private const val TAG = "NaksangCamera"

        /**
         * Analysis size hint in the camera target-rotation frame (same convention
         * as the previous 320×240). CameraX picks the closest supported size.
         * Change these two numbers to retune resolution.
         */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480
    }
}
