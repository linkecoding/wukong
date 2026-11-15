package com.wukong.phone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.MotionEvent
import android.widget.*
import org.json.JSONObject

class VirtualScreenPanel(context: Context, private val compact: Boolean = false, private val taskInline: Boolean = false) : LinearLayout(context) {
    private val status = TextView(context)
    private var title: TextView?=null
    private var manualRow: LinearLayout?=null
    private var manualText: LinearLayout?=null
    private var stopButton: Button?=null
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background=WukongUi.shape(context,R.color.paper,14); clipToOutline=true }
    private val control = WukongUi.button(context, "", true) { VirtualScreenState.control(!VirtualScreenState.manual) }
    private val manualInputs = mutableListOf<android.view.View>()
    private var downX = 0; private var downY = 0; private var downTime = 0L
    private val changed: () -> Unit = { refresh() }
    var onDrag: ((Float, Float) -> Unit)? = null
    init {
        orientation = VERTICAL; setPadding(dp(if(taskInline)0 else 8), dp(8), dp(if(taskInline)0 else 8), dp(if(taskInline)0 else 8)); background = if(taskInline) null else WukongUi.shape(context, R.color.surface, 16, R.color.line)
        status.textSize = if (compact) 11f else 14f; status.setTextColor(context.getColor(R.color.ink))
        if (compact) {
            val header = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            header.addView(ImageView(context).apply { setImageResource(R.drawable.ic_wukong); importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(8) })
            val labels=LinearLayout(context).apply { orientation=VERTICAL }
            title=TextView(context).apply { textSize=13f; setTextColor(context.getColor(R.color.ink)); setTypeface(typeface,android.graphics.Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            labels.addView(title); labels.addView(status)
            header.addView(labels, LayoutParams(0, -2, 1f))
            header.addView(ImageButton(context).apply {
                id = R.id.virtual_preview_close
                setImageResource(R.drawable.ic_close); contentDescription = context.getString(R.string.ui_close_floating_preview)
                background = WukongUi.shape(context, R.color.paper, 12)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ink))
                setOnClickListener { VirtualScreenState.service?.closeFloatingPreview() }
            }, LayoutParams(dp(48), dp(48)))
            addView(header)
        } else if(!taskInline) addView(status)
        if (compact) {
            var lastX = 0f; var lastY = 0f
            getChildAt(0).setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) onDrag?.invoke(event.rawX-lastX,event.rawY-lastY)
                lastX=event.rawX; lastY=event.rawY; true
            }
        }
        val screenHeight = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        addView(image, LayoutParams(-1, dp(if (compact) minOf(220, (screenHeight-220).toInt()).coerceAtLeast(100) else 320)))
        control.isAllCaps = false; control.setSingleLine(); control.textSize = 13f; control.minHeight = dp(48)
        control.setOnClickListener { VirtualScreenState.control(!VirtualScreenState.manual) }
        val primary=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        primary.addView(control,LayoutParams(0,dp(48),1f))
        if(compact) {
            primary.addView(WukongUi.button(context,context.getString(R.string.ui_wukong_2),false) { context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }.apply { textSize=12f; minWidth=0; minimumWidth=0; setPadding(dp(8),0,dp(8),0) },LayoutParams(dp(64),dp(48)).apply { marginStart=dp(4) })
            stopButton=WukongUi.button(context,context.getString(R.string.ui_stop),false) { TaskRuntime.stop() }.apply { textSize=12f; minWidth=0; minimumWidth=0; setPadding(dp(6),0,dp(6),0) }
            primary.addView(stopButton,LayoutParams(dp(52),dp(48)))
        }
        addView(primary, LayoutParams(-1,dp(48)).apply { topMargin=dp(8); bottomMargin=dp(4) })
        val row = LinearLayout(context)
        fun button(label: String, action: () -> Unit) { row.addView(WukongUi.button(context, label, false, action).apply {
            text = label; textSize = 11f; isAllCaps = false; minimumWidth = 0; minWidth = 0; setPadding(0, 0, 0, 0); setOnClickListener { action() }
        }.also { if (row.childCount < 2) manualInputs += it }, LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }) }
        button(context.getString(R.string.ui_back)) { VirtualScreenState.input(JSONObject().put("kind", "back")) }
        button(context.getString(R.string.ui_home)) { VirtualScreenState.input(JSONObject().put("kind", "home")) }
        if(!compact && !taskInline) button(context.getString(R.string.ui_wukong_2)) { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        manualRow=row; addView(row)
        if (!compact) {
            val text = SetupViews(context).field(context.getString(R.string.ui_text_for_the_focused_field_manual_control)).apply { isSaveEnabled = false }
            manualInputs += text
            val inputs=LinearLayout(context).apply { orientation=VERTICAL }; manualText=inputs
            inputs.addView(text, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
            inputs.addView(WukongUi.button(context, context.getString(R.string.ui_send_text)) {
                val value = text.text.toString()
                if (value.isNotBlank()) { text.setText(""); VirtualScreenState.input(JSONObject().put("kind", "text").put("text", value)) }
            }.also { manualInputs += it }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(inputs)
            if(!taskInline) addView(WukongUi.button(context, context.getString(R.string.ui_close_display)) { VirtualScreenState.stop(context) })
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
        title?.text=if(TaskRuntime.active && TaskRuntime.displayId>0) TaskRuntime.goal else context.getString(R.string.ui_wukong_virtual_display)
        status.text = if(compact && TaskRuntime.active && TaskRuntime.displayId>0) UiMessages.text(context,TaskRuntime.event.message).take(80) else UiMessages.text(context, VirtualScreenState.message)
        status.maxLines=2; status.ellipsize=android.text.TextUtils.TruncateAt.END
        manualRow?.visibility=if(VirtualScreenState.manual) VISIBLE else GONE
        manualText?.visibility=if(VirtualScreenState.manual) VISIBLE else GONE
        stopButton?.visibility=if(TaskRuntime.active) VISIBLE else GONE
        stopButton?.isEnabled=!TaskRuntime.stopRequested
        image.setImageBitmap(VirtualScreenState.bitmap)
        control.text = if (VirtualScreenState.manual) { context.getString(R.string.ui_resume_agent) } else context.getString(R.string.chat_take_over)
        control.isEnabled = VirtualScreenState.displayId > 0
        manualInputs.forEach { it.isEnabled = VirtualScreenState.displayId > 0 && VirtualScreenState.manual }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); VirtualScreenState.listen(changed); refresh() }
    override fun onDetachedFromWindow() { VirtualScreenState.unlisten(changed); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
