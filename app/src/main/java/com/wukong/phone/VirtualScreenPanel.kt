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
    private val control = WukongUi.button(context, "", true) { VirtualScreenState.control(!VirtualScreenState.manual) }
    private val manualInputs = mutableListOf<android.view.View>()
    private var downX = 0; private var downY = 0; private var downTime = 0L
    private val changed: () -> Unit = { refresh() }
    var onDrag: ((Float, Float) -> Unit)? = null
    init {
        orientation = VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); background = WukongUi.shape(context, R.color.surface, 16, R.color.line)
        status.textSize = if (compact) 11f else 14f; status.setTextColor(context.getColor(R.color.ink))
        if (compact) {
            val header = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            header.addView(status, LayoutParams(0, -2, 1f))
            header.addView(ImageButton(context).apply {
                id = R.id.virtual_preview_close
                setImageResource(R.drawable.ic_close); contentDescription = context.getString(R.string.ui_close_floating_preview)
                background = WukongUi.shape(context, R.color.paper, 12)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ink))
                setOnClickListener { VirtualScreenState.service?.closeFloatingPreview() }
            }, LayoutParams(dp(48), dp(48)))
            addView(header)
        } else addView(status)
        if (compact) {
            var lastX = 0f; var lastY = 0f
            status.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) onDrag?.invoke(event.rawX-lastX,event.rawY-lastY)
                lastX=event.rawX; lastY=event.rawY; true
            }
        }
        val screenHeight = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        addView(image, LayoutParams(-1, dp(if (compact) minOf(280, (screenHeight-210).toInt()).coerceAtLeast(100) else 390)))
        control.isAllCaps = false; control.textSize = 13f; control.minHeight = dp(48)
        control.setOnClickListener { VirtualScreenState.control(!VirtualScreenState.manual) }; addView(control, LayoutParams(-1, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val row = LinearLayout(context)
        fun button(label: String, action: () -> Unit) { row.addView(WukongUi.button(context, label, false, action).apply {
            text = label; textSize = 11f; isAllCaps = false; minimumWidth = 0; minWidth = 0; setPadding(0, 0, 0, 0); setOnClickListener { action() }
        }.also { if (row.childCount < 2) manualInputs += it }, LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }) }
        button(context.getString(R.string.ui_back)) { VirtualScreenState.input(JSONObject().put("kind", "back")) }
        button(context.getString(R.string.ui_home)) { VirtualScreenState.input(JSONObject().put("kind", "home")) }
        button(context.getString(R.string.ui_wukong_2)) { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        addView(row)
        if (!compact) {
            val text = SetupViews(context).field(context.getString(R.string.ui_text_for_the_focused_field_manual_control)).apply { isSaveEnabled = false }
            manualInputs += text
            addView(text, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
            addView(WukongUi.button(context, context.getString(R.string.ui_send_text)) {
                val value = text.text.toString()
                if (value.isNotBlank()) { text.setText(""); VirtualScreenState.input(JSONObject().put("kind", "text").put("text", value)) }
            }.also { manualInputs += it }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(WukongUi.button(context, context.getString(R.string.ui_close_display)) { VirtualScreenState.stop(context) })
        }
        image.contentDescription = context.getString(R.string.virtual_preview_description)
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
        status.text = UiMessages.text(context, VirtualScreenState.message)
        image.setImageBitmap(VirtualScreenState.bitmap)
        control.text = if (VirtualScreenState.manual) { if (compact) context.getString(R.string.ui_resume_agent) else context.getString(R.string.ui_resume_agent_observe_again) } else context.getString(R.string.ui_take_over_pause_agent)
        control.isEnabled = VirtualScreenState.displayId > 0
        manualInputs.forEach { it.isEnabled = VirtualScreenState.displayId > 0 && VirtualScreenState.manual }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); VirtualScreenState.listen(changed); refresh() }
    override fun onDetachedFromWindow() { VirtualScreenState.unlisten(changed); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
