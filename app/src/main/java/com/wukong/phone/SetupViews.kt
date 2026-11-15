package com.wukong.phone

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.View
import android.widget.*

/** Shared native form styling, using the app's existing design system. */
internal class SetupViews(val context: Context) {
    fun dp(value: Int) = WukongUi.dp(context, value)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Float = 15f, muted: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(context.getColor(if (muted) R.color.muted else R.color.ink))
        if (size >= 18f) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(4).toFloat(), 1f)
    }
    fun field(hint: String, value: String = "", secret: Boolean = false) = EditText(context).apply {
        this.hint = hint; setText(value); textSize = 15f; setSingleLine(); minHeight = dp(52)
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.ink)); setHintTextColor(context.getColor(R.color.muted))
        setPadding(dp(12), dp(12), dp(12), dp(12)); backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), WukongUi.shape(context, R.color.surface, 12, R.color.accent, 2))
            addState(intArrayOf(), WukongUi.shape(context, R.color.surface, 12, R.color.control))
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (secret) isSaveEnabled = false
    }
    fun back(parent: LinearLayout, action: () -> Unit) {
        parent.addView(WukongUi.button(context, "", false, action).apply {
            contentDescription = context.getString(R.string.setup_back)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_back, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.muted))
            compoundDrawablePadding = 0
            background = WukongUi.feedback(context, R.color.paper)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(12) })
    }
    fun add(parent: LinearLayout, view: View, top: Int = 0, bottom: Int = 12) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
    }
    fun button(label: Int, primary: Boolean = false, action: () -> Unit) = WukongUi.button(context, context.getString(label), primary, action)
}
