package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.radiobutton.MaterialRadioButton

/** Hallmark · focused native sheet · cream/moss · critique P4 H5 E4 S4 R5 V4. */
internal object ExecutionOptionsSheet {
    fun show(activity: Activity, selectedPackage: String?, onSelected: (String?) -> Unit): BottomSheetDialog {
        val sheet=BottomSheetDialog(activity,R.style.Theme_Wukong_BottomSheet)
        val context=sheet.context
        var selected=selectedPackage
        fun dp(n:Int)=WukongUi.dp(context,n)
        fun text(value:CharSequence,size:Float=14f,muted:Boolean=false)=TextView(context).apply {
            text=value;textSize=size;setTextColor(context.getColor(if(muted)R.color.muted else R.color.ink))
        }
        fun icon(drawable:Int,description:Int,action:()->Unit)=ImageButton(context).apply {
            setImageResource(drawable);contentDescription=context.getString(description);setPadding(dp(14),dp(14),dp(14),dp(14))
            imageTintList=ColorStateList.valueOf(context.getColor(R.color.muted));background=WukongUi.feedback(context,android.R.color.transparent,24,canDisable=false)
            setOnClickListener { action() }
        }
        @Suppress("DEPRECATION") val apps=activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .map { it.activityInfo.packageName to it.loadLabel(activity.packageManager).toString() }.distinctBy { it.first }
            .sortedWith { a,b -> java.text.Collator.getInstance(context.resources.configuration.locales[0]).compare(a.second,b.second) }
        val automatic="" to context.getString(R.string.ui_home_screen_all_apps)
        if(selected!=null&&apps.none { it.first==selected }) { selected=null;onSelected(null) }
        fun appIcon(pkg:String)=if(pkg.isBlank())context.getDrawable(R.drawable.ic_notification) else runCatching { activity.packageManager.getApplicationIcon(pkg) }.getOrNull()
        var measuredPicker:LinearLayout?=null
        val root=object:LinearLayout(context) {
            override fun onMeasure(widthSpec:Int,heightSpec:Int) {
                measuredPicker?.takeIf { it.visibility==View.VISIBLE }?.let { page ->
                    if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)
                        page.layoutParams=page.layoutParams.apply { height=minOf(dp(560),(View.MeasureSpec.getSize(heightSpec)-dp(48)).coerceAtLeast(0)) }
                }
                super.onMeasure(widthSpec,heightSpec)
            }
        }.apply { id=R.id.execution_options_sheet;orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),dp(20)) }
        val handle=BottomSheetDragHandleView(context).apply { setImageResource(R.drawable.ic_sheet_handle);imageTintList=ColorStateList.valueOf(context.getColor(R.color.control)) }
        root.addView(handle,LinearLayout.LayoutParams(dp(64),dp(28)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        val pages=FrameLayout(context);root.addView(pages)
        val options=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val picker=LinearLayout(context).apply { id=R.id.execution_app_picker;orientation=LinearLayout.VERTICAL;visibility=View.GONE;isFocusableInTouchMode=true }
        measuredPicker=picker
        pages.addView(options);pages.addView(picker)
        val pickerBack=object:OnBackPressedCallback(false) { override fun handleOnBackPressed() {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
            picker.visibility=View.GONE;options.visibility=View.VISIBLE;isEnabled=false;sheet.behavior.isDraggable=true
        } }
        sheet.onBackPressedDispatcher.addCallback(pickerBack)
        val heading=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(text(context.getString(R.string.chat_execution),20f).apply { setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(icon(R.drawable.ic_preview_close,R.string.execution_close) { sheet.dismiss() },LinearLayout.LayoutParams(dp(48),dp(48)))
        options.addView(heading)
        val modes=RadioGroup(context).apply { orientation=RadioGroup.VERTICAL }
        fun mode(id:Int,label:Int)=MaterialRadioButton(context).apply {
            this.id=id;setText(label);textSize=16f;minHeight=dp(60);setPadding(dp(12),0,dp(16),0)
            buttonTintList=ColorStateList.valueOf(context.getColor(R.color.accent));isEnabled=!TaskRuntime.active
            layoutParams=RadioGroup.LayoutParams(-1,dp(60)).apply { topMargin=dp(8) }
        }
        val phone=mode(R.id.execution_phone,R.string.chat_location_phone);val virtual=mode(R.id.execution_virtual,R.string.chat_location_virtual)
        modes.addView(phone);modes.addView(virtual);modes.check(if(VirtualScreenState.enabled(context))virtual.id else phone.id);options.addView(modes)
        val state=text("",13f,true).apply { setPadding(dp(4),dp(12),dp(4),dp(20)) };options.addView(state)
        val changed:()->Unit={
            for(button in listOf(phone,virtual))if(button.tag!=button.isChecked) { button.tag=button.isChecked;button.background=WukongUi.feedback(context,if(button.isChecked)R.color.soft_success else R.color.paper,16,if(button.isChecked)R.color.accent else R.color.line) }
            state.text=context.getString(when { !VirtualScreenState.enabled(context)->R.string.chat_location_phone_detail;VirtualScreenState.displayId>0->R.string.chat_display_ready;else->R.string.chat_display_preparing })
            if(VirtualScreenState.enabled(context)&&VirtualScreenState.displayId==0&&VirtualScreenState.service==null)state.text=UiMessages.text(context,VirtualScreenState.message)
        }
        modes.setOnCheckedChangeListener { _,checked ->
            if(!TaskRuntime.active) {
                val enabled=checked==virtual.id;VirtualScreenState.enabled(context,enabled)
                if(enabled)runCatching { VirtualScreenState.start(activity) }.onFailure { state.text=it.message } else VirtualScreenState.stop(activity)
                onSelected(selected);changed()
            }
        }
        options.addView(text(context.getString(R.string.ui_starting_app_optional),13f,true).apply { setPadding(dp(4),dp(4),0,dp(8)) })
        val selectedRow=LinearLayout(context).apply {
            id=R.id.execution_start_app;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(76);setPadding(dp(14),dp(12),dp(10),dp(12))
            background=WukongUi.feedback(context,R.color.paper,16,R.color.line);isEnabled=!TaskRuntime.active;isFocusable=true
        }
        val selectedIcon=ImageView(context);selectedRow.addView(selectedIcon,LinearLayout.LayoutParams(dp(32),dp(32)).apply { marginEnd=dp(12) })
        val selectedLabels=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val selectedName=text("",16f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        selectedLabels.addView(selectedName);selectedLabels.addView(text(context.getString(R.string.execution_app_scope),12f,true).apply { maxLines=2 })
        selectedRow.addView(selectedLabels,LinearLayout.LayoutParams(0,-2,1f))
        selectedRow.addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron);imageTintList=ColorStateList.valueOf(context.getColor(R.color.muted)) },LinearLayout.LayoutParams(dp(20),dp(20)))
        options.addView(selectedRow)
        fun refreshSelected() {
            selectedName.text=apps.firstOrNull { it.first==selected }?.second ?: automatic.second
            selectedIcon.setImageDrawable(appIcon(selected.orEmpty()))
            selectedIcon.imageTintList=if(selected==null)ColorStateList.valueOf(context.getColor(R.color.accent)) else null
            selectedRow.contentDescription=context.getString(R.string.ui_starting_app_optional)+": "+selectedName.text
        }
        refreshSelected()
        val pickerHeader=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        pickerHeader.addView(icon(R.drawable.ic_back,R.string.ui_back) { pickerBack.handleOnBackPressed() },LinearLayout.LayoutParams(dp(48),dp(48)))
        pickerHeader.addView(text(context.getString(R.string.execution_choose_app),20f).apply { setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(0,-2,1f))
        picker.addView(pickerHeader)
        val search=EditText(context).apply {
            id=R.id.execution_app_search;hint=context.getString(R.string.execution_search_apps);textSize=15f;setSingleLine();setTextColor(context.getColor(R.color.ink));setHintTextColor(context.getColor(R.color.muted))
            setPadding(dp(14),dp(12),dp(14),dp(12));background=WukongUi.feedback(context,R.color.paper,16,R.color.line)
            inputType=android.text.InputType.TYPE_CLASS_TEXT;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;isSaveEnabled=false
        }
        picker.addView(search,LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(8);bottomMargin=dp(8) })
        val list=ListView(context).apply { id=R.id.execution_app_list;divider=null;setSelector(android.R.color.transparent);isVerticalScrollBarEnabled=true }
        var filtered=listOf(automatic)+apps
        val adapter=object:BaseAdapter() {
            override fun getCount()=filtered.size
            override fun getItem(position:Int)=filtered[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                val app=filtered[position];val checked=app.first==selected.orEmpty()
                return LinearLayout(context).apply {
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(64);setPadding(dp(12),dp(8),dp(12),dp(8));isSelected=checked
                    background=WukongUi.feedback(context,if(checked)R.color.soft_success else R.color.surface,14)
                    addView(ImageView(context).apply { setImageDrawable(appIcon(app.first));if(app.first.isBlank())imageTintList=ColorStateList.valueOf(context.getColor(R.color.accent));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginEnd=dp(12) })
                    addView(text(app.second,16f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
                    addView(ImageView(context).apply { setImageResource(R.drawable.ic_check);visibility=if(checked)View.VISIBLE else View.INVISIBLE;imageTintList=ColorStateList.valueOf(context.getColor(R.color.accent));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(20),dp(20)))
                }
            }
        }
        list.adapter=adapter
        val listBox=FrameLayout(context)
        listBox.addView(list,FrameLayout.LayoutParams(-1,-1))
        val empty=text(context.getString(R.string.ui_no_apps_available),14f,true).apply { gravity=Gravity.CENTER;visibility=View.GONE }
        listBox.addView(empty,FrameLayout.LayoutParams(-1,-1));list.emptyView=empty
        picker.addView(listBox,LinearLayout.LayoutParams(-1,0,1f))
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                val query=s.toString().trim();filtered=(listOf(automatic)+apps).filter { query.isBlank()||it.second.contains(query,true)||it.first.contains(query,true) };adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        list.setOnItemClickListener { _,_,position,_ ->
            selected=filtered[position].first.ifBlank { null };onSelected(selected);refreshSelected();adapter.notifyDataSetChanged();pickerBack.handleOnBackPressed()
        }
        selectedRow.setOnClickListener {
            if(TaskRuntime.active)return@setOnClickListener
            search.setText("");adapter.notifyDataSetChanged();options.visibility=View.GONE;picker.visibility=View.VISIBLE;pickerBack.isEnabled=true
            picker.layoutParams=FrameLayout.LayoutParams(-1,minOf(dp(560),(context.resources.displayMetrics.heightPixels*.7f).toInt()))
            sheet.behavior.isDraggable=false;picker.requestFocus();sheet.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.setContentView(root)
        sheet.setOnShowListener {
            sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.behavior.state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;sheet.behavior.skipCollapsed=true;sheet.behavior.maxWidth=dp(640)
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background=WukongUi.shape(context,R.color.surface,24).apply { val radius=dp(24).toFloat();cornerRadii=floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f) }
            VirtualScreenState.listen(changed);changed()
        }
        sheet.setOnDismissListener { VirtualScreenState.unlisten(changed);pickerBack.remove() }
        sheet.show();return sheet
    }
}
