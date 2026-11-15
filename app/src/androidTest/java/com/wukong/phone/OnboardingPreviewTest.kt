package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnboardingPreviewTest {
    @Test fun captureLocalizedScreensAndCheckSmallScreenActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capture_onboarding") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousLanguage = UiLanguage.code(context)
        val store = TaskDraftStore(context); val previousGoal = store.goal(); val previousPackage = store.packageName()
        val before = SettingsStore(context).load()?.toJson()?.toString()
        var activity: Activity? = null
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun capture(name: String) {
            val root = activity!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val density = activity!!.resources.displayMetrics.density
            for (widthDp in listOf(320,375,414,768)) {
                val width = (widthDp*density).toInt(); val height = (900*density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height)
                all(root).filterIsInstance<Button>().filter { it.isShown && it.width > 0 }.forEach {
                    assertTrue("Clipped action at $widthDp: ${it.text}", it.paint.measureText(it.text.toString()) <= it.width-it.compoundPaddingLeft-it.compoundPaddingRight+1)
                }
                if (widthDp == 414) {
                    val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
                    val folder = File(context.filesDir,"onboarding-previews").apply { mkdirs() }
                    File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
            }
        }
        try {
            for (language in listOf("en","zh-CN")) {
                UiLanguage.set(context,language); store.clear()
                activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { capture("home-$language"); activity!!.finish() }
                instrumentation.waitForIdleSync()
                activity = instrumentation.startActivitySync(Intent(context,SetupActivity::class.java).putExtra("model_only",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { all(activity!!.findViewById(android.R.id.content)).filterIsInstance<ModelConfigurationView>().single().providerPicker.setSelection(0) }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val form = all(activity!!.findViewById(android.R.id.content)).filterIsInstance<ModelConfigurationView>().single()
                    form.key.setText("")
                    capture("model-$language"); activity!!.finish()
                }
                instrumentation.waitForIdleSync()
            }
            assertTrue("Previewing must not save model changes", before == SettingsStore(context).load()?.toJson()?.toString())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }; instrumentation.waitForIdleSync()
            store.save(previousGoal,previousPackage); UiLanguage.set(context,previousLanguage)
        }
    }
}
