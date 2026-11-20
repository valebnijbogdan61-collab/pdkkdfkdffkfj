package com.example.gamebutton.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

/** Accessibility-сервіс: тапає по координатах, незалежно від підсвічування в грі. */
class TapInjector : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    private fun dispatch(points: List<PointF>): Boolean {
        val b = GestureDescription.Builder()
        points.take(18).forEachIndexed { i, p ->
            val path = Path().apply { moveTo(p.x + Random.nextInt(-2, 3), p.y + Random.nextInt(-2, 3)) }
            b.addStroke(GestureDescription.StrokeDescription(path, i * 300L, 90L))
        }
        return dispatchGesture(b.build(), null, null)
    }

    companion object {
        @Volatile private var instance: TapInjector? = null
        fun isEnabled() = instance != null
        fun tapSequence(points: List<PointF>): Boolean = instance?.dispatch(points) ?: false
    }
}
