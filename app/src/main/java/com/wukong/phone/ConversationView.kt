package com.wukong.phone

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.wukong.core.Phase
import com.wukong.core.label

/** Hallmark · conversation workbench · cream/moss · P4 H4 E4 S5 R4 V4. Native tokens only. */
internal class ConversationView(context: Context, private val threadId: String,
    private val onSend: () -> Unit, private val onOptions: () -> Unit, private val onDetails: (ConversationTurn) -> Unit,
    private val onExample: (TaskTemplates.Template) -> Unit, private val onContent: () -> Unit, private val store: ConversationStore = ConversationStore(context), private val onActive: () -> Unit = {}) : LinearLayout(context) {
    val input = EditText(context).apply {
        id=R.id.task_goal; hint=context.getString(R.string.chat_input); textSize=16f; minLines=1; maxLines=5
        setTextColor(context.getColor(R.color.ink)); setHintTextColor(context.getColor(R.color.muted))
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(12),dp(12),dp(8),dp(12)); background=null; isSaveEnabled=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private val send = ImageButton(context).apply {
        id=R.id.task_run; contentDescription=context.getString(R.string.chat_send); setImageResource(R.drawable.ic_send)
        setPadding(dp(12),dp(12),dp(12),dp(12)); imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_ink))
        background=WukongUi.feedback(context,R.color.accent,24); setOnClickListener { onSend() }
    }
    private val mode = WukongUi.button(context,"",false) { onOptions() }.apply { id=R.id.chat_options; setSingleLine(); textSize=12f; minHeight=dp(48); setPadding(dp(12),0,dp(12),0) }
    val composer = LinearLayout(context).apply {
        orientation=VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(12)); setBackgroundColor(context.getColor(R.color.paper))
        val box=LinearLayout(context).apply {
            gravity=Gravity.BOTTOM; setPadding(dp(4),dp(4),dp(6),dp(4)); background=WukongUi.shape(context,R.color.surface,24,R.color.control)
            addView(input,LayoutParams(0,-2,1f)); addView(send,LayoutParams(dp(48),dp(48)).apply { gravity=Gravity.BOTTOM })
        }
        addView(box,LayoutParams(-1,-2)); addView(mode,LayoutParams(-2,dp(48)))
    }
    private var version=-1L
    private var liveStatus: TextView?=null
    private var liveSteps: TextView?=null
    private var previewSlot: LinearLayout?=null
    private var screenButton: Button?=null
    private var stop: Button?=null
    private var previewOpen=false
    private var approvalArea: LinearLayout?=null
    private var lastApproval: PendingApproval?=null
    private var activeTurn: String?=null
    private val busy get()=TaskRuntime.active && TaskRuntime.conversationId==threadId
    init { orientation=VERTICAL; refresh() }
    private fun label(value:String,size:Float=14f,bold:Boolean=false,muted:Boolean=false)=TextView(context).apply {
        text=value; textSize=size; setTextColor(context.getColor(if(muted) R.color.muted else R.color.ink)); setLineSpacing(dp(4).toFloat(),1f)
        if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    private fun column()=LinearLayout(context).apply { orientation=VERTICAL }
    private fun padded(view:View,top:Int=0,bottom:Int=12)=addView(view,LayoutParams(-1,-2).apply { topMargin=dp(top); bottomMargin=dp(bottom) })
    private fun render() {
        removeAllViews(); liveStatus=null; liveSteps=null; previewSlot=null; approvalArea=null; lastApproval=null; screenButton=null; stop=null
        val turns=store.get(threadId)?.turns.orEmpty()
        if(TaskRuntime.active && !busy) padded(WukongUi.button(context,context.getString(R.string.chat_return_active),false,onActive))
        if(turns.isEmpty()) {
            val welcome=column().apply {
                setPadding(dp(4),dp(56),dp(4),dp(28))
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_wukong); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(dp(64),dp(64)))
                addView(label(context.getString(R.string.chat_welcome),28f,true),LayoutParams(-1,-2).apply { topMargin=dp(24); bottomMargin=dp(12) })
                addView(label(context.getString(R.string.chat_welcome_detail),15f,muted=true))
            }
            padded(welcome)
            TaskTemplates.all.take(2).forEachIndexed { index,template ->
                padded(WukongUi.actionRow(context,context.getString(template.title),if(index==0)R.drawable.ic_sample_file else R.drawable.ic_sample_search) { onExample(template) },0,4)
            }
            return
        }
        turns.forEach { turn ->
            val bubble=label(turn.goal,16f).apply {
                tag="conversation-request"; setTextIsSelectable(true); setPadding(dp(16),dp(12),dp(16),dp(12))
                background=WukongUi.shape(context,R.color.soft_accent,20)
            }
            val request=LinearLayout(context).apply { gravity=Gravity.END; addView(bubble,LayoutParams(-2,-2).apply { marginStart=dp(32) }) }
            padded(request,16,12)
            val isLive=busy && turn.id==TaskRuntime.conversationTurnId
            val phase=if(isLive)TaskRuntime.event.phase.name else if(turn.phase in listOf("RUNNING","PAUSED","AWAITING_APPROVAL")) "CANCELLED" else turn.phase
            val card=column().apply { tag="conversation-task"; setPadding(dp(16),dp(14),dp(16),dp(14)); background=WukongUi.shape(context,R.color.surface,20,R.color.line) }
            val heading=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            heading.addView(ImageView(context).apply { setImageResource(R.drawable.ic_wukong); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
            val state=label(phase(phase),14f,true).apply { setTextColor(context.getColor(if(phase=="FAILED")R.color.danger else R.color.accent)) }
            heading.addView(state,LayoutParams(0,-2,1f))
            if(turn.elapsedMs>0) heading.addView(label(context.getString(R.string.chat_duration,turn.elapsedMs/1000),12f,muted=true))
            card.addView(heading)
            if(isLive) {
                liveStatus=state
                liveSteps=label("",13f,muted=true).apply { setPadding(0,dp(12),0,dp(8)) }; card.addView(liveSteps)
                approvalArea=column().also { card.addView(it) }
                val actions=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
                screenButton=WukongUi.button(context,context.getString(R.string.chat_show_screen),false) { togglePreview() }.apply { id=R.id.chat_preview; textSize=13f; minWidth=0; minimumWidth=0; setPadding(dp(8),0,dp(8),0) }
                actions.addView(screenButton,LayoutParams(0,dp(48),1f))
                stop=WukongUi.button(context,context.getString(R.string.ui_stop),false) { TaskRuntime.stop() }.apply { textSize=13f; setPadding(dp(12),0,dp(12),0) }
                actions.addView(stop,LayoutParams(-2,dp(48))); card.addView(actions)
                previewSlot=column().apply { id=R.id.chat_preview_slot }; card.addView(previewSlot)
                if(previewOpen) attachPreview()
            } else {
                heading.addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LayoutParams(dp(16),dp(16)).apply { marginStart=dp(8) })
                card.addView(label(context.getString(R.string.chat_run_details),11f,muted=true).apply { setPadding(dp(34),dp(4),0,0) })
                card.isFocusable=true; card.contentDescription=context.getString(R.string.chat_run_details)
                card.background=WukongUi.feedback(context,R.color.surface,20,R.color.line,false)
                card.setOnClickListener { onDetails(turn) }
            }
            padded(card,0,12)
            if(!isLive) padded(label(turn.result.ifBlank { context.getString(R.string.chat_interrupted) },16f).apply { tag="conversation-result"; setTextIsSelectable(true); android.text.util.Linkify.addLinks(this,android.text.util.Linkify.WEB_URLS); setLinkTextColor(context.getColor(R.color.accent)); setPadding(dp(4),0,dp(4),dp(8)) },0,16)
        }
    }
    private fun attachPreview() {
        val slot=previewSlot?:return; slot.removeAllViews()
        if(VirtualScreenState.displayId>0 && TaskRuntime.displayId>0) slot.addView(VirtualScreenPanel(context,taskInline=true),LayoutParams(-1,-2))
        else slot.addView(label(context.getString(R.string.chat_main_display),13f,muted=true).apply { setPadding(0,dp(12),0,dp(8)) })
    }
    private fun togglePreview() { previewOpen=!previewOpen; if(previewOpen)attachPreview() else previewSlot?.removeAllViews(); updatePreviewLabel(); onContent() }
    private fun updatePreviewLabel() { screenButton?.setText(if(previewOpen) R.string.chat_hide_screen else R.string.chat_show_screen) }
    fun refresh() {
        val nextActive=if(busy)TaskRuntime.conversationTurnId else null
        if(version!=store.version() || activeTurn!=nextActive) { version=store.version(); activeTurn=nextActive; render(); onContent() }
        val phase=TaskRuntime.event.phase
        liveStatus?.text=phase(phase.name)
        liveSteps?.text=TaskRuntime.events().takeLast(3).joinToString("\n") { friendly(it.message).take(180) }
        stop?.isEnabled=!TaskRuntime.stopRequested
        send.isEnabled=input.text.isNotBlank() && !TaskRuntime.active && !WirelessActivation.busy
        send.imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(if(send.isEnabled) R.color.accent_ink else R.color.muted)); input.isEnabled=!TaskRuntime.active
        mode.text=context.getString(if(VirtualScreenState.enabled(context)) R.string.chat_mode_virtual else R.string.chat_mode_phone)
        mode.isEnabled=!TaskRuntime.active
        updatePreviewLabel()
        val request=if(busy)TaskRuntime.pending else null
        if(request!==lastApproval) {
            lastApproval=request; approvalArea?.removeAllViews()
            if(request!=null) approvalArea?.apply {
                addView(label(context.getString(R.string.chat_confirm_high_risk),14f,true))
                val targetId=when(val action=request.action) { is com.wukong.core.Action.Tap->action.nodeId; is com.wukong.core.Action.Input->action.nodeId; else->null }
                val target=request.screen.nodes.firstOrNull { it.id==targetId }?.text.orEmpty()
                addView(label((request.screen.packageName+"\n"+target.ifBlank { request.action.label() }).take(400),13f).apply { setPadding(0,dp(8),0,dp(8)) })
                val row=LinearLayout(context)
                row.addView(WukongUi.button(context,context.getString(R.string.ui_decline),false) { TaskRuntime.answer(request,false) },LayoutParams(0,dp(48),1f))
                row.addView(WukongUi.button(context,context.getString(R.string.ui_allow_once),true) { TaskRuntime.answer(request,true) },LayoutParams(0,dp(48),1f))
                addView(row)
            }
        }
    }
    private fun friendly(message:String):String = when {
        message.startsWith("点击节点") -> context.getString(R.string.chat_interacting)
        message.startsWith("向节点") -> context.getString(R.string.chat_typing)
        message=="执行手机 JS 短流程" -> context.getString(R.string.chat_processing)
        message=="查询页面控件" -> context.getString(R.string.chat_observing)
        message=="等待目标条件" -> context.getString(R.string.chat_waiting)
        else -> UiMessages.text(context,message)
    }
    private fun phase(value:String)=context.getString(when(value) {
        "COMPLETED"->R.string.ui_completed; "FAILED"->R.string.ui_task_failed; "CANCELLED"->R.string.ui_task_stopped
        "PAUSED"->R.string.paused_label; "AWAITING_APPROVAL"->R.string.ui_waiting_for_approval; else->R.string.ui_working
    })
    private fun dp(n:Int)=WukongUi.dp(context,n)
}
