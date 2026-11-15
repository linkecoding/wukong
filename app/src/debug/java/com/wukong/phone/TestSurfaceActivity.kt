package com.wukong.phone

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView

/** Debug-only deterministic surface. Never included in release builds. */
class TestSurfaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = EditText(this).apply { setHint(R.string.test_query); isSingleLine = true }
        val result = TextView(this).apply { setText(R.string.test_waiting) }
        val search = Button(this).apply { setText(R.string.test_search); setOnClickListener { result.text = getString(R.string.test_result, input.text) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 80, 24, 24)
            addView(input); addView(search); addView(result)
            val rows = LinearLayout(this@TestSurfaceActivity).apply { orientation = LinearLayout.VERTICAL }
            repeat(40) { index -> rows.addView(TextView(this@TestSurfaceActivity).apply {
                text = getString(R.string.test_row, index); minHeight = (48 * resources.displayMetrics.density).toInt()
            }) }
            addView(ScrollView(this@TestSurfaceActivity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
}
