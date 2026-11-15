package com.wukong.phone

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Footers stay at the physical bottom; only scrollable content avoids the IME overlay. */
internal object PageInsets {
    fun bind(root: View, content: View, footer: View, liftComposer: Boolean = false) {
        val originalBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(bars.left, bars.top, bars.right, if (liftComposer) maxOf(bars.bottom, ime) else bars.bottom)
            val covered = if (liftComposer) 0 else (ime - bars.bottom - footer.height).coerceAtLeast(0)
            val bottom = originalBottom + covered
            val bringInputIntoView = bottom > content.paddingBottom
            if (bottom != content.paddingBottom) {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
                if (bringInputIntoView) content.post {
                    content.findFocus()?.let { focused ->
                        val cursor = Rect(); focused.getFocusedRect(cursor)
                        focused.requestRectangleOnScreen(cursor, false)
                    }
                }
            }
            insets
        }
        footer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(root)
        }
        ViewCompat.requestApplyInsets(root)
    }
}
