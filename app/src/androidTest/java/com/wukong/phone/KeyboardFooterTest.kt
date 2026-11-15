package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual IME show/hide regression: never types into or saves the user's fields. */
@RunWith(AndroidJUnit4::class)
class KeyboardFooterTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            i.runOnMainSync { ready = condition() }
            if (!ready) Thread.sleep(100)
        }
        assertTrue("Keyboard/footer state did not arrive", ready)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_keyboard") != "true") return
        Thread.sleep(500)
        val request = File(context.filesDir, "screen-review-request")
        val done = File(context.filesDir, "screen-review-done")
        done.delete(); request.writeText(name)
        val deadline = System.currentTimeMillis() + 15000
        while (!done.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Host must capture $name", done.exists()); request.delete()
    }
    private fun exercise(activity: Activity, fieldId: Int, footerId: Int, name: String) {
        val field = activity.findViewById<EditText>(fieldId)
        val footer = activity.findViewById<View>(footerId)
        val root = activity.findViewById<View>(android.R.id.content)
        val previous = field.text.toString()
        i.runOnMainSync {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setTurnScreenOn(true)
        }
        await { footer.isLaidOut && footer.height > 0 }
        val footerBefore = Rect()
        i.runOnMainSync { footer.getGlobalVisibleRect(footerBefore) }
        val ime = activity.getSystemService(InputMethodManager::class.java)
        // A real touch returns physical-display focus from an existing virtual preview.
        val target = Rect()
        i.runOnMainSync { field.getGlobalVisibleRect(target) }
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, target.centerX().toFloat(), target.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            i.sendPointerSync(event); event.recycle()
        }
        await { activity.hasWindowFocus() }
        i.runOnMainSync { field.requestFocus(); ime.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        await { ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true && footer.isShown }
        i.waitForIdleSync()
        i.runOnMainSync {
            assertTrue(field.hasFocus())
            val footerDuring = Rect(); footer.getGlobalVisibleRect(footerDuring)
            assertEquals("Footer must stay anchored instead of moving above keyboard", footerBefore, footerDuring)
            val visibleField = Rect(); val visibleWindow = Rect()
            field.getWindowVisibleDisplayFrame(visibleWindow)
            assertTrue("Focused field remains visible", field.getGlobalVisibleRect(visibleField))
            val decor = activity.window.decorView
            val position = IntArray(2); decor.getLocationOnScreen(position)
            val imeHeight = ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            assertTrue("Visible input stays above keyboard", visibleField.bottom <= position[1] + decor.height - imeHeight + 2)
            assertEquals(previous, field.text.toString())
        }
        capture("$name-keyboard")
        i.runOnMainSync { ime.hideSoftInputFromWindow(field.windowToken, 0) }
        await { ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == false && footer.isShown }
        capture("$name-keyboard-closed")
        i.runOnMainSync { assertEquals(previous, field.text.toString()) }
    }
    @Test fun bottomNavigationAndModelSaveFooterStayAnchoredUnderTheKeyboard() {
        assumeTrue(!TaskRuntime.active)
        val draft = TaskDraftStore(context); val beforeGoal = draft.goal(); val beforePackage = draft.packageName()
        val beforeModel = SettingsStore(context).load()?.toJson()?.toString()
        var a: Activity? = null
        try {
            a = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
            exercise(a, R.id.task_goal, R.id.main_navigation, "tasks")
            i.runOnMainSync { a!!.finish() }; i.waitForIdleSync()
            a = i.startActivitySync(Intent(context, SetupActivity::class.java).putExtra("model_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
            exercise(a, R.id.model_name, R.id.setup_actions, "model")
            assertTrue("IME changes must preserve saved configuration", beforeModel == SettingsStore(context).load()?.toJson()?.toString())
            assertEquals(beforeGoal, draft.goal()); assertEquals(beforePackage, draft.packageName())
        } finally {
            i.runOnMainSync { a?.finish() }
        }
    }
}
