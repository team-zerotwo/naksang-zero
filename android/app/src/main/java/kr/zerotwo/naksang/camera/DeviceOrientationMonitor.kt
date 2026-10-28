package kr.zerotwo.naksang.camera

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.OrientationEventListener

/**
 * Reads gravity through [OrientationEventListener] and emits a snapped
 * quadrant. The listener already reports [OrientationEventListener.ORIENTATION_UNKNOWN]
 * when the phone is flat on a table.
 */
class DeviceOrientationMonitor(
    context: Context,
    private val onQuadrant: (Int) -> Unit,
) {
    private var state = OrientationSnap()
    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            val angle = if (orientation == ORIENTATION_UNKNOWN) null else orientation
            val next = PhoneOrientation.step(state, angle, SystemClock.elapsedRealtime())
            if (next == state) return
            val changed = next.snapped != state.snapped
            state = next
            if (changed) onQuadrant(next.snapped)
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }
}
