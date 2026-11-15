package com.wukong.phone

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Debug-only opaque canvas deliberately exposes no semantic UI targets. */
class CanvasSurfaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
            var result = "悟空 OCR 测试 READY"
            var downY = 0f; var downTime = 0L
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.WHITE)
                paint.color = Color.BLACK; canvas.drawText(result, 50f, 180f, paint)
                for ((y, label) in listOf(200f to "点击测试 TAP", 500f to "长按测试 LONG", 900f to "滑动测试 SWIPE")) {
                    paint.color = Color.LTGRAY; canvas.drawRect(40f, y, width - 40f, y + 180f, paint)
                    paint.color = Color.BLACK; canvas.drawText(label, 70f, y + 100f, paint)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN) { downY = event.y; downTime = event.eventTime }
                if (event.action == MotionEvent.ACTION_UP) {
                    result = when {
                        kotlin.math.abs(event.y - downY) > 100 -> "滑动成功 SWIPED"
                        downY in 500f..680f && event.eventTime - downTime >= 400 -> "长按成功 PRESSED"
                        downY in 200f..380f -> "点击成功 TAPPED"
                        else -> result
                    }; invalidate()
                }
                return true
            }
        })
    }
}
