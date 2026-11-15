package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun root(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun launch(type: Class<out Activity>, modelOnly: Boolean = false) = instrumentation.startActivitySync(Intent(context, type).putExtra("model_only", modelOnly).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test fun usefulTemplateIsCustomizedAndSurvivesRelaunchWithoutAutoRunning() {
        val store = TaskDraftStore(context)
        val previous = store.goal(); val previousPackage = store.packageName()
        var activity: Activity? = null
        try {
            store.clear()
            activity = launch(MainActivity::class.java)
            instrumentation.runOnMainSync {
                val sample = views(root(activity!!)).filterIsInstance<Button>().single { it.text.toString() == activity!!.getString(R.string.sample_file) }
                sample.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val windows = WindowInspector.getGlobalWindowViews().flatMap(::views)
                val parameter = windows.filterIsInstance<EditText>().single { it.id == R.id.sample_parameter }
                parameter.setText("invoice PDF")
                windows.filterIsInstance<Button>().single { it.text.toString() == activity!!.getString(R.string.sample_use) }.performClick()
                assertTrue(store.goal().contains("invoice PDF"))
                assertFalse(TaskRuntime.active)
                assertEquals(View.GONE, views(root(activity!!)).filterIsInstance<Spinner>().first().parent.let { it as View }.visibility)
                activity!!.finish()
            }
            activity = launch(MainActivity::class.java)
            instrumentation.runOnMainSync {
                assertTrue(activity!!.findViewById<EditText>(R.id.task_goal).text.toString().contains("invoice PDF"))
                assertFalse(TaskRuntime.active)
                activity!!.finish()
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            store.save(previous, previousPackage)
        }
    }
    @Test fun providerSwitchClearsKeyAndCancelKeepsSavedConfiguration() {
        val before = SettingsStore(context).load()?.toJson()?.toString()
        val activity = launch(SetupActivity::class.java, true)
        try {
            instrumentation.runOnMainSync {
                val form = views(root(activity)).filterIsInstance<ModelConfigurationView>().single()
                assertEquals(View.GONE, form.findViewById<View>(R.id.model_advanced).visibility)
                form.key.setText("test-key-for-first-provider")
                val next = if (form.providerPicker.selectedItemPosition == 1) 0 else 1
                form.providerPicker.setSelection(next)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val form = views(root(activity)).filterIsInstance<ModelConfigurationView>().single()
                assertEquals("", form.key.text.toString())
                val custom = ModelPresets.all.indexOfFirst { it.id == "custom" }
                form.providerPicker.setSelection(custom)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val form = views(root(activity)).filterIsInstance<ModelConfigurationView>().single()
                assertEquals(View.VISIBLE, form.findViewById<View>(R.id.model_advanced).visibility)
                form.endpoint.setText("https://example.com/special/path")
                form.model.setText("test-model"); form.key.setText("test-key"); form.fullEndpoint.isChecked = true
                assertEquals("https://example.com/special/path", form.candidate().requestEndpoint())
                activity.finish()
            }
            assertTrue("Cancel must preserve encrypted model settings", before == SettingsStore(context).load()?.toJson()?.toString())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun manualPortsAreOnlyShownUnderTroubleshooting() {
        val activity = launch(WirelessSetupActivity::class.java)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(views(root(activity)).filterIsInstance<EditText>().isEmpty())
                views(root(activity)).filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.connect_advanced) }.performClick()
                assertEquals(3, views(root(activity)).filterIsInstance<EditText>().size)
                activity.finish()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
