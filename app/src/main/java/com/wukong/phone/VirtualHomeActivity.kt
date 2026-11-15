package com.wukong.phone

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.widget.*

/** Safe launcher content for our independent display, without moving the physical launcher. */
class VirtualHomeActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(WukongUi.dp(this@VirtualHomeActivity, 24), WukongUi.dp(this@VirtualHomeActivity, 48), WukongUi.dp(this@VirtualHomeActivity, 24), WukongUi.dp(this@VirtualHomeActivity, 24)); setBackgroundColor(getColor(R.color.paper)) }
        column.addView(TextView(this).apply { text = getString(R.string.ui_wukong_virtual_display); textSize = 24f })
        @Suppress("DEPRECATION") val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        for (app in apps) column.addView(WukongUi.button(this, app.loadLabel(packageManager).toString()) {}.apply {
            text = app.loadLabel(packageManager); isAllCaps = false
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName) ?: return@setOnClickListener
                @Suppress("DEPRECATION") val options = ActivityOptions.makeBasic().apply { launchDisplayId = windowManager.defaultDisplay.displayId }
                startActivity(intent, options.toBundle())
            }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
