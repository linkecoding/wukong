package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in screenshots of actual phone windows; never saves or submits model edits. */
@RunWith(AndroidJUnit4::class)
class ScreenReviewTest {
    @Test fun reviewEveryPage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("review_screens") == "true")
        val i = InstrumentationRegistry.getInstrumentation(); val c = i.targetContext
        assumeTrue(!TaskRuntime.active && VirtualScreenState.service == null)
        val language = UiLanguage.code(c); val drafts = TaskDraftStore(c)
        val oldGoal = drafts.goal(); val oldPackage = drafts.packageName()
        val virtualEnabled = VirtualScreenState.enabled(c)
        val savedModel = SettingsStore(c).load()?.toJson()?.toString()
        var a: Activity? = null
        fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        fun views() = all(a!!.findViewById(android.R.id.content))
        fun click(id: Int) { i.runOnMainSync { views().filterIsInstance<Button>().single { it.text.toString() == a!!.getString(id) }.performClick() }; i.waitForIdleSync() }
        fun shot(name: String) {
            i.waitForIdleSync()
            i.runOnMainSync { WindowInspector.getGlobalWindowViews().flatMap { all(it) }.forEach { it.invalidate() } }
            Thread.sleep(700)
            val request = File(c.filesDir,"screen-review-request")
            val done = File(c.filesDir,"screen-review-done")
            done.delete(); request.writeText(name)
            val deadline = System.currentTimeMillis() + 15000
            while(!done.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Host must capture $name", done.exists())
            request.delete()

        }
        fun page(name: String) {
            i.runOnMainSync { views().filterIsInstance<ScrollView>().firstOrNull()?.scrollTo(0,0) }; shot("$name-top")
            i.runOnMainSync { views().filterIsInstance<ScrollView>().firstOrNull()?.fullScroll(View.FOCUS_DOWN) }; shot("$name-bottom")
        }
        try {
            for(lang in listOf("en","zh-CN")) {
                UiLanguage.set(c,lang); drafts.clear()
                a = i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); page("$lang-tasks")
                click(R.string.setup_show_options); page("$lang-options")
                click(R.string.ui_history); page("$lang-history")
                click(R.string.ui_logs); page("$lang-logs")
                val file = RunLogStore(c).files().firstOrNull()
                if(file != null) { i.runOnMainSync { MainActivity::class.java.getDeclaredMethod("logDetail",String::class.java).apply { isAccessible=true }.invoke(a,file.name) }; page("$lang-log-detail")
                    i.runOnMainSync {
                        views().first { it.tag == "run-event" }.performClick()
                        assertTrue("Expanded log payload visible", views().first { it.tag == "event-payload" }.visibility == View.VISIBLE)
                    }; page("$lang-log-expanded")
                }
                click(R.string.ui_settings); page("$lang-settings")
                i.runOnMainSync { a!!.finish() }; i.waitForIdleSync()
                a = i.startActivitySync(Intent(c,SetupActivity::class.java).putExtra("model_only",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); i.waitForIdleSync()
                i.runOnMainSync { views().filterIsInstance<ModelConfigurationView>().single().key.setText("") }; page("$lang-model")
                click(R.string.setup_advanced); page("$lang-model-advanced")
                i.runOnMainSync { a!!.finish() }; i.waitForIdleSync()
                a = i.startActivitySync(Intent(c,WirelessSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); page("$lang-connection")
                click(R.string.connect_advanced); page("$lang-connection-advanced")
                i.runOnMainSync { a!!.finish() }; i.waitForIdleSync()
                a = i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); i.waitForIdleSync()
                i.runOnMainSync { MainActivity::class.java.getDeclaredMethod("chooseTemplate",TaskTemplates.Template::class.java).apply { isAccessible=true }.invoke(a,TaskTemplates.all.first()) }; shot("$lang-example-dialog")
                i.runOnMainSync { WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<Button>().first { it.text.toString() == a!!.getString(R.string.ui_cancel) }.performClick(); a!!.finish() }; i.waitForIdleSync()
            }
            a = i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i.runOnMainSync { VirtualScreenState.enabled(c,true); VirtualScreenState.start(a!!) }
            val deadline = System.currentTimeMillis()+15000
            while(VirtualScreenState.bitmap == null && System.currentTimeMillis()<deadline) Thread.sleep(100)
            assertTrue("Virtual preview available", VirtualScreenState.bitmap != null)
            click(R.string.ui_tasks); page("virtual-embedded")
            i.runOnMainSync { VirtualScreenState.control(true) }
            val manualDeadline = System.currentTimeMillis()+5000
            while(!VirtualScreenState.manual && System.currentTimeMillis()<manualDeadline) Thread.sleep(100)
            Thread.sleep(1200); page("virtual-manual")
            i.runOnMainSync { VirtualScreenState.control(false); a!!.moveTaskToBack(true) }; shot("virtual-floating")
            i.runOnMainSync { VirtualScreenState.stop(c) }
            assertTrue("Screenshots must preserve saved model", savedModel == SettingsStore(c).load()?.toJson()?.toString())
        } finally {
            i.runOnMainSync { a?.finish() }; i.waitForIdleSync(); VirtualScreenState.stop(c); VirtualScreenState.enabled(c,virtualEnabled); drafts.save(oldGoal,oldPackage); UiLanguage.set(c,language)
        }
    }
}
