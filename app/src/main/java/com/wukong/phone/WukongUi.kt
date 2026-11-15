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

    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 14f; isAllCaps = false; setSingleLine()
        minHeight = dp(context, 48); minimumHeight = dp(context, 48); minimumWidth = 0; minWidth = 0
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null; elevation = 0f; backgroundTintList = null
        background = feedback(context, if (primary) R.color.accent else R.color.surface, 12, if (primary) null else R.color.line)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.muted), context.getColor(if (primary) R.color.accent_ink else R.color.ink))))
        setOnClickListener { action() }
    }
}
