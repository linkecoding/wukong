package com.wukong.phone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button

/** Hallmark · modern-minimal · Workbench · shared native Android design tokens. */
object WukongUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, fill: Int, radius: Int = 12, stroke: Int? = null, width: Int = 1) = GradientDrawable().apply {
        setColor(context.getColor(fill))
        cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, width), context.getColor(stroke))
    }

    fun feedback(context: Context, fill: Int, radius: Int = 12, stroke: Int? = null, canDisable: Boolean = true): RippleDrawable {
        val states = StateListDrawable().apply {
            if (canDisable) addState(intArrayOf(-android.R.attr.state_enabled), shape(context, R.color.line, radius))
            addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf(
                shape(context, R.color.paper, radius, R.color.ink, 2),
                shape(context, fill, (radius - 4).coerceAtLeast(0))
            )).apply {
                val inset = dp(context, 4)
                setLayerInset(1, inset, inset, inset, inset)
                paddingMode = LayerDrawable.PADDING_MODE_STACK
            })
            addState(intArrayOf(android.R.attr.state_hovered), shape(context, fill, radius, R.color.accent))
            addState(intArrayOf(), shape(context, fill, radius, stroke))
        }
        return RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.soft_accent)), states, shape(context, R.color.surface, radius)).apply {
            state = intArrayOf(android.R.attr.state_enabled)
        }
    }

    fun choiceAdapter(context: Context, labels: List<String>) = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
        private fun style(view: android.view.View) = (view as android.widget.TextView).apply {
            textSize = 15f; setTextColor(context.getColor(R.color.ink))
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        }
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) = style(super.getView(position, convertView, parent))
        override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) = style(super.getDropDownView(position, convertView, parent))
    }

    fun actionRow(context: Context, label: String, icon: Int? = null, action: () -> Unit) = button(context, label, false, action).apply {
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        textSize = 14f
        setTextColor(context.getColor(R.color.ink))
        setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10))
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon?.let { context.getDrawable(it)?.mutate() }, null, context.getDrawable(R.drawable.ic_chevron)?.mutate(), null)
        compoundDrawablePadding = dp(context, 12)
        compoundDrawableTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
    }

    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 14f; isAllCaps = false; setSingleLine()
        minHeight = dp(context, 48); minimumHeight = dp(context, 48); minimumWidth = 0; minWidth = 0
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null; elevation = 0f; backgroundTintList = null
        background = feedback(context, if (primary) R.color.accent else android.R.color.transparent, 12)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.muted), context.getColor(if (primary) R.color.accent_ink else R.color.accent))))
        setOnClickListener { action() }
    }
}
