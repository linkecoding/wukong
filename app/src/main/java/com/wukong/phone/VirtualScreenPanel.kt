package com.wukong.phone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.MotionEvent
import android.widget.*
import org.json.JSONObject

class VirtualScreenPanel(context: Context, private val compact: Boolean = false) : LinearLayout(context) {
    private val status = TextView(context)
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
    private val control = Button(context)
    private var downX = 0; private var downY = 0; private var downTime = 0L
    private val changed: () -> Unit = { refresh() }
    var onDrag: ((Float, Float) -> Unit)? = null
    init {
        orientation = VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(context.getColor(R.color.paper))
        status.textSize = if (compact) 11f else 14f; status.setTextColor(context.getColor(R.color.ink)); addView(status)
        if (compact) {
            var lastX = 0f; var lastY = 0f
            status.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) onDrag?.invoke(event.rawX-lastX,event.rawY-lastY)
                lastX=event.rawX; lastY=event.rawY; true
            }
        }
        val screenHeight = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        addView(image, LayoutParams(-1, dp(if (compact) minOf(280, (screenHeight-210).toInt()).coerceAtLeast(100) else 390)))
        control.isAllCaps = false; control.textSize = 13f; control.minHeight = dp(42)
        control.setOnClickListener { VirtualScreenState.control(!VirtualScreenState.manual) }; addView(control, LayoutParams(-1, dp(44)))
        val row = LinearLayout(context)
        fun button(label: String, action: () -> Unit) { row.addView(Button(context).apply {
            text = label; textSize = 11f; isAllCaps = false; minimumWidth = 0; minWidth = 0; setPadding(0, 0, 0, 0); setOnClickListener { action() }
        }, LayoutParams(0, dp(42), 1f)) }
        button("返回") { VirtualScreenState.input(JSONObject().put("kind", "back")) }
        button("桌面") { VirtualScreenState.input(JSONObject().put("kind", "home")) }
        button("回悟空") { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        addView(row)
        if (!compact) {
            val text = EditText(context).apply { hint = "接管后：输入文字到虚拟屏的焦点输入框"; setSingleLine(); isSaveEnabled = false }
            addView(text)
            addView(Button(context).apply { this.text = "输入文字"; isAllCaps = false; setOnClickListener {
                val value = text.text.toString(); text.setText(""); VirtualScreenState.input(JSONObject().put("kind", "text").put("text", value))
            } })
            addView(Button(context).apply { this.text = "关闭虚拟屏"; isAllCaps = false; setOnClickListener { VirtualScreenState.stop(context) } })
        }
        image.setOnTouchListener { view, event ->
            if (!VirtualScreenState.manual) return@setOnTouchListener false
            val bitmap = VirtualScreenState.bitmap ?: return@setOnTouchListener false
            val scale = minOf(image.width.toFloat()/bitmap.width, image.height.toFloat()/bitmap.height)
            val x = ((event.x - (image.width - bitmap.width*scale)/2) / (bitmap.width*scale) * 1000).toInt()
            val y = ((event.y - (image.height - bitmap.height*scale)/2) / (bitmap.height*scale) * 1000).toInt()
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (x !in 0..1000 || y !in 0..1000) return@setOnTouchListener false
                downX=x; downY=y; downTime=event.eventTime; view.parent.requestDisallowInterceptTouchEvent(true)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                val small = kotlin.math.abs(x-downX) + kotlin.math.abs(y-downY) < 12
                VirtualScreenState.input(JSONObject().put("kind", "gesture").put("x", downX).put("y", downY)
                    .put("endX", if (small) downX else x.coerceIn(0,1000)).put("endY", if (small) downY else y.coerceIn(0,1000))
                    .put("duration", (event.eventTime-downTime).coerceIn(60,2000)))
                view.parent.requestDisallowInterceptTouchEvent(false); view.performClick()
            }
            true
        }
        refresh()
    }
    private fun refresh() {
        status.text = VirtualScreenState.message
        image.setImageBitmap(VirtualScreenState.bitmap)
        control.text = if (VirtualScreenState.manual) { if (compact) "交还 Agent" else "交还 Agent，重新读屏继续" } else "接管，暂停 Agent"
        control.isEnabled = VirtualScreenState.displayId > 0
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); VirtualScreenState.listen(changed); refresh() }
    override fun onDetachedFromWindow() { VirtualScreenState.unlisten(changed); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
