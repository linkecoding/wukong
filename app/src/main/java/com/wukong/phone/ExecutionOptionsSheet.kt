package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.radiobutton.MaterialRadioButton

/** Native Material bottom sheet: explicit location choices, live readiness, optional starting app. */
internal object ExecutionOptionsSheet {
    fun show(activity: Activity, selectedPackage: String?, onSelected: (String?) -> Unit): BottomSheetDialog {
        val sheet=BottomSheetDialog(activity,R.style.Theme_Wukong_BottomSheet)
        var selected=selectedPackage
        val context=sheet.context
        fun dp(n:Int)=WukongUi.dp(context,n)
        fun label(value:Int,size:Float=14f,muted:Boolean=false)=TextView(context).apply {
            setText(value);textSize=size;setTextColor(context.getColor(if(muted)R.color.muted else R.color.ink))
        }
        val content=LinearLayout(context).apply { id=R.id.execution_options_sheet;orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),dp(24)) }
        content.addView(BottomSheetDragHandleView(context).apply { imageTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.control)) },LinearLayout.LayoutParams(dp(64),dp(32)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        content.addView(label(R.string.chat_execution,20f).apply { setTypeface(typeface,Typeface.BOLD) })
        val modes=RadioGroup(context).apply { orientation=RadioGroup.VERTICAL;setPadding(0,dp(16),0,dp(8)) }
        val phone=MaterialRadioButton(context).apply { id=R.id.execution_phone;setText(R.string.chat_location_phone);textSize=16f;minHeight=dp(56);buttonTintList=android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent));isEnabled=!TaskRuntime.active }
        val virtual=MaterialRadioButton(context).apply { id=R.id.execution_virtual;setText(R.string.chat_location_virtual);textSize=16f;minHeight=dp(56);buttonTintList=phone.buttonTintList;isEnabled=!TaskRuntime.active }
        modes.addView(phone);modes.addView(virtual)
        modes.check(if(VirtualScreenState.enabled(context))R.id.execution_virtual else R.id.execution_phone)
        content.addView(modes)
        val state=label(R.string.chat_display_preparing,13f,true).apply { setPadding(dp(4),0,dp(4),dp(20)) };content.addView(state)
        val changed:()->Unit={
            state.text=context.getString(when { !VirtualScreenState.enabled(context)->R.string.chat_location_phone_detail;VirtualScreenState.displayId>0->R.string.chat_display_ready;else->R.string.chat_display_preparing })
            if(VirtualScreenState.enabled(context)&&VirtualScreenState.displayId==0&&VirtualScreenState.service==null)state.text=UiMessages.text(context,VirtualScreenState.message)
        }
        modes.setOnCheckedChangeListener { _,checked ->
            if(TaskRuntime.active)return@setOnCheckedChangeListener
            val enabled=checked==R.id.execution_virtual
            VirtualScreenState.enabled(context,enabled)
            if(enabled)runCatching { VirtualScreenState.start(activity) }.onFailure { state.text=it.message }
            else VirtualScreenState.stop(activity)
            onSelected(selected);changed()
        }
        content.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.line)) },LinearLayout.LayoutParams(-1,dp(1)))
        content.addView(label(R.string.ui_starting_app_optional,13f,true).apply { setPadding(0,dp(20),0,dp(8)) })
        @Suppress("DEPRECATION") val apps=listOf("" to context.getString(R.string.ui_home_screen_all_apps))+activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .map { it.activityInfo.packageName to it.loadLabel(activity.packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        content.addView(Spinner(context).apply {
            adapter=WukongUi.choiceAdapter(context,apps.map { it.second });minimumHeight=dp(56);setSelection(apps.indexOfFirst { it.first==selectedPackage }.coerceAtLeast(0));isEnabled=!TaskRuntime.active
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { selected=apps[position].first.ifBlank { null };onSelected(selected) }
            }
        },LinearLayout.LayoutParams(-1,dp(56)))
        sheet.setContentView(content)
        sheet.setOnShowListener {
            sheet.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed=true;sheet.behavior.maxWidth=dp(640)
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background=WukongUi.shape(context,R.color.surface,24).apply { val radius=dp(24).toFloat();cornerRadii=floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f) }
            VirtualScreenState.listen(changed);changed()
        }
        sheet.setOnDismissListener { VirtualScreenState.unlisten(changed) }
        sheet.show()
        return sheet
    }
}
