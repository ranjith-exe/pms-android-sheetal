package `in`.hashday.pms.sheetal

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.hypot

class TwoFingerRefreshGesture(
    private val context: Context,
    private val webView: WebView,
    private val onRefreshTriggered: () -> Unit
) : View.OnTouchListener {

    private var initialY1 = 0f
    private var initialY2 = 0f
    private var initialX1 = 0f
    private var initialX2 = 0f
    private var initialDistance = 0f

    private var isGestureActive = false
    private var hasTriggeredInCurrentGesture = false

    private val density = context.resources.displayMetrics.density
    private val minTriggerDistance = 180f * density // 180 dp downward movement
    private val maxPinchDistanceChange = 50f * density // max finger distance delta before treating as pinch-zoom

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    if (webView.scrollY == 0) {
                        initialX1 = event.getX(0)
                        initialY1 = event.getY(0)
                        initialX2 = event.getX(1)
                        initialY2 = event.getY(1)

                        initialDistance = hypot(
                            (initialX2 - initialX1).toDouble(),
                            (initialY2 - initialY1).toDouble()
                        ).toFloat()

                        isGestureActive = true
                        hasTriggeredInCurrentGesture = false
                    }
                } else {
                    isGestureActive = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isGestureActive && event.pointerCount == 2 && !hasTriggeredInCurrentGesture) {
                    if (webView.scrollY > 0) {
                        isGestureActive = false
                        return false
                    }

                    val currentX1 = event.getX(0)
                    val currentY1 = event.getY(0)
                    val currentX2 = event.getX(1)
                    val currentY2 = event.getY(1)

                    val currentDistance = hypot(
                        (currentX2 - currentX1).toDouble(),
                        (currentY2 - currentY1).toDouble()
                    ).toFloat()

                    // Pinch-to-zoom check: finger spacing changed significantly
                    val distanceChange = abs(currentDistance - initialDistance)
                    if (distanceChange > maxPinchDistanceChange) {
                        // Accidental gesture during zoom / image view pinch
                        isGestureActive = false
                        return false
                    }

                    val deltaY1 = currentY1 - initialY1
                    val deltaY2 = currentY2 - initialY2
                    val avgDeltaY = (deltaY1 + deltaY2) / 2f

                    val deltaX1 = currentX1 - initialX1
                    val deltaX2 = currentX2 - initialX2
                    val avgDeltaX = (deltaX1 + deltaX2) / 2f

                    // Both fingers moving downward together, vertical movement dominates horizontal
                    if (deltaY1 > 0 && deltaY2 > 0 && avgDeltaY > minTriggerDistance && avgDeltaY > (2.0f * abs(avgDeltaX))) {
                        hasTriggeredInCurrentGesture = true
                        isGestureActive = false

                        provideHapticFeedback()
                        onRefreshTriggered()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 2) {
                    isGestureActive = false
                }
            }
        }

        // Return false to let standard WebView events proceed (normal scrolling, links, pinch zoom)
        return false
    }

    private fun provideHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            Log.w("TwoFingerRefresh", "Vibration failed: ${e.message}")
        }
    }
}
