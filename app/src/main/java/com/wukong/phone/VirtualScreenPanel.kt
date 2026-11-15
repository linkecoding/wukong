package com.wukong.phone

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.widget.*
import org.json.JSONObject

/** Hallmark · compact monitor · cream/moss · critique P4 H5 E4 S4 R5 V4. */
class VirtualScreenPanel(context: Context, private val compact: Boolean = false, private val taskInline: Boolean = false) : LinearLayout(context) {
    private val status = TextView(context)
    private var title: TextView?=null
    private var manualRow: LinearLayout?=null
    private var manualText: LinearLayout?=null
    private var stopButton: View?=null
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background=WukongUi.shape(context,R.color.paper,14); clipToOutline=true }
    private val control = WukongUi.button(context, "", true) { VirtualScreenState.control(!VirtualScreenState.manual) }
    private val manualInputs = mutableListOf<android.view.View>()
    private var downX = 0; private var downY = 0; private var downTime = 0L
    private val changed: () -> Unit = { refresh() }
    var onDrag: ((Float, Float) -> Unit)? = null
    var onResize: ((Float, Float) -> Unit)? = null
    var onGeometrySettled: (() -> Unit)? = null
    val previewHeight get() = image.layoutParams.height
    fun setPreviewHeight(height: Int) { image.layoutParams = image.layoutParams.apply { this.height=height } }
    init {
        orientation = VERTICAL; setPadding(dp(if(taskInline)0 else 8), dp(8), dp(if(taskInline)0 else 8), dp(if(taskInline)0 else 8)); background = if(taskInline) null else WukongUi.shape(context, R.color.surface, if(compact)20 else 16, R.color.line)
        status.textSize = if (compact) 11f else 14f; status.setTextColor(context.getColor(if(compact)R.color.muted else R.color.ink))
        if (compact) {
            val header = LinearLayout(context).apply { id=R.id.virtual_preview_header; gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight=dp(48) }
            header.addView(ImageView(context).apply { setImageResource(R.drawable.ic_notification); imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent)); importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(dp(20),dp(20)).apply { marginEnd=dp(8) })
            val labels=LinearLayout(context).apply { orientation=VERTICAL }
            title=TextView(context).apply { textSize=13f; setTextColor(context.getColor(R.color.ink)); setTypeface(typeface,android.graphics.Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            labels.addView(title); labels.addView(status)
            header.addView(labels, LayoutParams(0, -2, 1f))
            header.addView(ImageButton(context).apply {
                id = R.id.virtual_preview_close
                setImageResource(R.drawable.ic_preview_close); contentDescription = context.getString(R.string.ui_close_floating_preview)
                background = WukongUi.feedback(context,android.R.color.transparent,24,canDisable=false)
                setPadding(dp(15), dp(15), dp(15), dp(15))
                imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.muted))
                setOnClickListener { VirtualScreenState.service?.closeFloatingPreview() }
            }, LayoutParams(dp(48), dp(48)))
            addView(header)
        } else if(!taskInline) addView(status)
        if (compact) {
            val header=getChildAt(0)
            header.contentDescription=context.getString(R.string.ui_wukong_2)
            header.isFocusable=true
            header.setOnClickListener { openWukong() }
            var lastX=0f; var lastY=0f; var downRawX=0f; var downRawY=0f; var dragging=false
            val slop=ViewConfiguration.get(context).scaledTouchSlop
            header.setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX=event.rawX;lastY=event.rawY;downRawX=lastX;downRawY=lastY;dragging=false }
                    MotionEvent.ACTION_MOVE -> {
                        if(kotlin.math.abs(event.rawX-downRawX)+kotlin.math.abs(event.rawY-downRawY)>slop)dragging=true
                        if(dragging)onDrag?.invoke(event.rawX-lastX,event.rawY-lastY)
                        lastX=event.rawX;lastY=event.rawY
                    }
                    MotionEvent.ACTION_UP -> {
                        if(!dragging)view.performClick() else {
                            onDrag?.invoke(event.rawX-lastX,event.rawY-lastY);onGeometrySettled?.invoke()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> { if(dragging)onGeometrySettled?.invoke();dragging=false }
                }
                true
            }
        }
        val screenHeight = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        addView(image, LayoutParams(-1, dp(if (compact) minOf(300, (screenHeight-240).toInt()).coerceAtLeast(100) else 320)))
        control.isAllCaps = false; control.setSingleLine(); control.textSize = 13f; control.minHeight = dp(48)
        control.setOnClickListener { VirtualScreenState.control(!VirtualScreenState.manual) }
        val primary=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        if(compact)control.background=WukongUi.feedback(context,R.color.accent,24)
        primary.addView(control,LayoutParams(0,dp(48),1f))
        if(compact) {
            stopButton=ImageButton(context).apply {
                contentDescription=context.getString(R.string.ui_stop_task);setImageResource(R.drawable.ic_preview_stop)
                setPadding(dp(15),dp(15),dp(15),dp(15));imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.muted))
                background=WukongUi.feedback(context,android.R.color.transparent,24,canDisable=true)
                setOnClickListener { TaskRuntime.stop() }
            }
            primary.addView(stopButton,LayoutParams(dp(48),dp(48)).apply { marginStart=dp(4) })
        }
        addView(primary, LayoutParams(-1,dp(48)).apply { topMargin=dp(8); bottomMargin=dp(4) })
        val row = LinearLayout(context)
        fun button(label: String, action: () -> Unit) {
            val view = if(compact) ImageButton(context).apply {
                setImageResource(if(row.childCount==0)R.drawable.ic_back else R.drawable.ic_preview_home)
                contentDescription=label;imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.muted))
                setPadding(dp(14),dp(14),dp(14),dp(14));background=WukongUi.feedback(context,android.R.color.transparent,24,canDisable=true)
                setOnClickListener { action() }
            } else WukongUi.button(context,label,false,action).apply {
                textSize=11f;isAllCaps=false;minimumWidth=0;minWidth=0;setPadding(0,0,0,0)
            }
            if(row.childCount<2)manualInputs+=view
            row.addView(view,LayoutParams(0,dp(48),1f).apply { marginEnd=dp(4) })
        }
        button(context.getString(R.string.ui_back)) { VirtualScreenState.input(JSONObject().put("kind", "back")) }
        button(context.getString(R.string.ui_home)) { VirtualScreenState.input(JSONObject().put("kind", "home")) }
        manualInputs.last().id=R.id.virtual_preview_home
        if(compact) {
            val hint=TextView(context).apply { text=context.getString(R.string.preview_resize_hint);textSize=11f;setTextColor(context.getColor(R.color.muted));gravity=android.view.Gravity.CENTER_VERTICAL }
            row.addView(hint,LayoutParams(0,dp(48),1f))
            val grip=ImageButton(context).apply {
                id=R.id.virtual_preview_resize;contentDescription=context.getString(R.string.preview_resize)
                setImageResource(R.drawable.ic_preview_resize);imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.muted))
                setPadding(dp(15),dp(15),dp(15),dp(15));background=WukongUi.feedback(context,android.R.color.transparent,24,canDisable=false)
            }
            var lastX=0f;var lastY=0f
            grip.setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX=event.rawX;lastY=event.rawY }
                    MotionEvent.ACTION_MOVE -> { onResize?.invoke(event.rawX-lastX,event.rawY-lastY);lastX=event.rawX;lastY=event.rawY }
                    MotionEvent.ACTION_UP -> { onResize?.invoke(event.rawX-lastX,event.rawY-lastY);onGeometrySettled?.invoke();view.performClick() }
                    MotionEvent.ACTION_CANCEL -> onGeometrySettled?.invoke()
                };true
            }
            row.addView(grip,LayoutParams(dp(48),dp(48)))
        }
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
        image.id=R.id.virtual_preview_image
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
        title?.text=context.getString(R.string.app_name)
        status.text = if(compact)context.getString(when {
            VirtualScreenState.frameError!=null -> R.string.preview_connection_lost
            VirtualScreenState.manual -> R.string.preview_manual
            TaskRuntime.stopRequested&&TaskRuntime.active -> R.string.ui_stopping
            TaskRuntime.pending!=null -> R.string.ui_waiting_for_approval
            TaskRuntime.active&&TaskRuntime.displayId>0 -> R.string.preview_working
            else -> R.string.preview_ready
        }) else UiMessages.text(context,VirtualScreenState.message)
        if(compact)status.setTextColor(context.getColor(if(VirtualScreenState.frameError!=null)R.color.danger else R.color.muted))
        status.maxLines=if(compact)1 else 2; status.ellipsize=android.text.TextUtils.TruncateAt.END
        manualRow?.visibility=if(compact||VirtualScreenState.manual) VISIBLE else GONE
        if(compact)manualRow?.let { row ->
            row.getChildAt(0).visibility=if(VirtualScreenState.manual) VISIBLE else GONE
            row.getChildAt(1).visibility=if(VirtualScreenState.manual) VISIBLE else GONE
            row.getChildAt(2).visibility=if(VirtualScreenState.manual) GONE else VISIBLE
        }
        manualText?.visibility=if(VirtualScreenState.manual) VISIBLE else GONE
        stopButton?.visibility=if(TaskRuntime.active) VISIBLE else GONE
        stopButton?.isEnabled=!TaskRuntime.stopRequested
        image.setImageBitmap(VirtualScreenState.bitmap)
        control.text = if (VirtualScreenState.manual) { context.getString(R.string.ui_resume_agent) } else context.getString(R.string.chat_take_over)
        control.isEnabled = VirtualScreenState.displayId > 0
        manualInputs.forEach { it.isEnabled = VirtualScreenState.displayId > 0 && VirtualScreenState.manual }
    }
    private fun openWukong() { context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); VirtualScreenState.listen(changed); refresh() }
    override fun onDetachedFromWindow() { VirtualScreenState.unlisten(changed); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
