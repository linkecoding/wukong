package com.wukong.phone

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskHistoryUiTest {
    @Test fun historyOpensDetailsAndClearMenuCancellationPreservesData() {
        val i = InstrumentationRegistry.getInstrumentation(); val c = i.targetContext
        assumeTrue(HistoryStore(c).read().isNotEmpty() && !TaskRuntime.active)
        val beforeHistory = c.getSharedPreferences("history",0).getString("records",null)
        val beforeFiles = RunLogStore(c).files().map { it.name }
        val activity = i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        fun views() = all(activity.findViewById(android.R.id.content))
        try {
            i.runOnMainSync { views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.ui_history) }.performClick() }
            i.waitForIdleSync()
            i.runOnMainSync { views().first { it.tag == "history-entry" }.performClick() }
            i.waitForIdleSync()
            i.runOnMainSync {
                assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.ui_task_detail) })
                assertFalse(views().filterIsInstance<Button>().any { it.text.toString() == activity.getString(R.string.ui_refresh_run) || it.text.toString() == activity.getString(R.string.ui_back_to_logs) })
                @Suppress("DEPRECATION")
                activity.onBackPressed()
                assertTrue("Legacy Back returns to history", views().any { it.tag == "history-entry" })
                views().first { it.tag == "history-entry" }.performClick()
                views().filterIsInstance<Button>().first { it.contentDescription == activity.getString(R.string.setup_back) }.performClick()
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                assertTrue(views().any { it.tag == "history-entry" })
                activity.findViewById<View>(R.id.page_menu).performClick()
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                val label = activity.getString(R.string.ui_clear_logs)
                val list = WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<ListView>().first { list -> (0 until list.count).any { list.adapter.getItem(it).toString() == label } }
                val index = (0 until list.count).first { list.adapter.getItem(it).toString() == label }
                list.performItemClick(list.getChildAt(index-list.firstVisiblePosition),index,list.adapter.getItemId(index))
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<Button>().first { it.text.toString() == activity.getString(R.string.ui_cancel) }.performClick()
            }
            assertTrue("Cancelling must preserve history", beforeHistory == c.getSharedPreferences("history",0).getString("records",null))
            assertEquals(beforeFiles,RunLogStore(c).files().map { it.name })
        } finally { i.runOnMainSync { activity.finish() } }
    }
}
