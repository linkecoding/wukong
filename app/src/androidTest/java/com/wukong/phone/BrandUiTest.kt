package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Regression coverage for navigation state and the new independent app language. */
@RunWith(AndroidJUnit4::class)
class BrandUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun root(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun click(activity: Activity, id: Int) {
        val label = activity.getString(id)
        descendants(root(activity)).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
    }

    @Test fun noSavedLanguageFollowsSystemAndManualOverridesRemainAvailable() {
        val preferences = context.getSharedPreferences("appearance", 0)
        val previous = preferences.getString("language", null)
        try {
            preferences.edit().remove("language").commit()
            assertEquals("system", UiLanguage.code(context))
            val expected = if (android.content.res.Resources.getSystem().configuration.locales[0].language == "zh") "zh-CN" else "en"
            assertEquals(expected, UiLanguage.effectiveCode(context))
            val localized = UiLanguage.wrap(context)
            assertEquals(java.util.Locale.forLanguageTag(expected).language, localized.resources.configuration.locales[0].language)
            UiLanguage.set(context, "en")
            assertEquals("en", UiLanguage.effectiveCode(context))
            UiLanguage.set(context, "zh-CN")
            assertEquals("zh-CN", UiLanguage.effectiveCode(context))
            UiLanguage.set(context, "system")
            assertEquals(expected, UiLanguage.effectiveCode(context))
        } finally {
            val editor = preferences.edit()
            if (previous == null) editor.remove("language") else editor.putString("language", previous)
            editor.commit()
        }
    }

    @Test fun navigationPreservesDraftAndLanguagePersistsWithoutChangingModel() {
        val oldDraft = TaskDraftStore(context).goal()
        val oldDraftPackage = TaskDraftStore(context).packageName()
        val oldLanguage = UiLanguage.code(context)
        val oldVirtual = VirtualScreenState.enabled(context)
        val modelBefore = SettingsStore(context).load()?.toJson()?.toString()
        var activity: Activity? = null
        try {
            UiLanguage.set(context, "en")
            VirtualScreenState.enabled(context, false)
            activity = launch()
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync {
                assertEquals("en", activity!!.resources.configuration.locales[0].language)
                val field = descendants(root(activity!!)).filterIsInstance<EditText>().filter { it.isShown }.single()
                field.setText("Find the Android version")
                click(activity!!, R.string.ui_history)
                for (nav in descendants(root(activity!!)).filterIsInstance<Button>().filter { it.tag is Int && !it.isSelected }) {
                    assertTrue("Navigation remains available", nav.isEnabled)
                    val ripple = nav.background as android.graphics.drawable.RippleDrawable
                    val current = (ripple.getDrawable(0) as android.graphics.drawable.StateListDrawable).current as android.graphics.drawable.GradientDrawable
                    assertEquals("Inactive navigation must not look disabled", activity!!.getColor(R.color.surface), current.color!!.defaultColor)
                }
                click(activity!!, R.string.ui_tasks)
                assertEquals("Find the Android version", descendants(root(activity!!)).filterIsInstance<EditText>().filter { it.isShown }.single().text.toString())
                click(activity!!, R.string.ui_settings)
                val language = descendants(root(activity!!)).filterIsInstance<Spinner>().single()
                assertEquals("English", language.selectedItem)
                language.setSelection(2)
            }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            instrumentation.removeMonitor(monitor)
            assertNotNull("Language switch must recreate the activity", activity)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                click(activity!!, R.string.ui_tasks)
                assertEquals("zh", activity!!.resources.configuration.locales[0].language)
                assertNotNull(activity!!.findViewById<Button>(R.id.task_run))
                assertEquals("zh-CN", UiLanguage.code(context))
                assertEquals("Find the Android version", descendants(root(activity!!)).filterIsInstance<EditText>().filter { it.isShown }.single().text.toString())
                activity!!.finish()
            }
            assertTrue("Language and navigation must preserve the encrypted model configuration", modelBefore == SettingsStore(context).load()?.toJson()?.toString())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            TaskDraftStore(context).save(oldDraft, oldDraftPackage)
            UiLanguage.set(context, oldLanguage)
            VirtualScreenState.enabled(context, oldVirtual)
        }
    }

    @Test fun taskLayoutsFitFourWidthsInBothLanguages() {
        val oldDraft = TaskDraftStore(context).goal()
        val oldDraftPackage = TaskDraftStore(context).packageName()
        val oldLanguage = UiLanguage.code(context)
        val oldVirtual = VirtualScreenState.enabled(context)
        var activity: Activity? = null
        try {
            VirtualScreenState.enabled(context, false)
            for (language in listOf("en", "zh-CN")) {
                UiLanguage.set(context, language)
                activity = launch()
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val screen = root(activity!!)
                    val density = activity!!.resources.displayMetrics.density
                    for (widthDp in listOf(320, 375, 414, 768)) {
                        val width = (widthDp * density).toInt()
                        val height = (900 * density).toInt()
                        screen.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        screen.layout(0, 0, width, height)
                        for (button in descendants(screen).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.width > 0 }) {
                            val available = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                            assertTrue("Clipped action at $widthDp dp ($language): ${button.text}", button.paint.measureText(button.text.toString()) <= available + 1)
                            assertTrue("Touch target too short", button.height >= 48 * density - 1)
                        }
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        screen.draw(Canvas(bitmap))
                        val folder = File(context.filesDir, "brand-ui-checks").apply { mkdirs() }
                        File(folder, "task-$language-$widthDp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    activity!!.finish()
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            TaskDraftStore(context).save(oldDraft, oldDraftPackage)
            UiLanguage.set(context, oldLanguage)
            VirtualScreenState.enabled(context, oldVirtual)
        }
    }
}
