package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExecutionOptionsUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun await(label:String,condition:()->Boolean) { val end=SystemClock.elapsedRealtime()+12000;while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertTrue(label,condition()) }
    private var backSequence=0
    private fun back(sheet:com.google.android.material.bottomsheet.BottomSheetDialog) {
        if(InstrumentationRegistry.getArguments().getString("physical_back")!="true") { ui { sheet.onBackPressedDispatcher.onBackPressed() };return }
        val seq=++backSequence;File(c.filesDir,"execution-back-request").writeText(seq.toString())
        await("Physical Back acknowledged") { File(c.filesDir,"execution-back-done").let { it.exists()&&it.readText().trim()==seq.toString() } }
    }
    private fun shot(view:View,name:String) { val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));File(c.filesDir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    private fun checkWidths(root:View,language:String) {
        fun all(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        val left=root.left;val top=root.top;val width=root.width;val height=root.height
        for(w in listOf(320,375,414,768)) {
            root.measure(View.MeasureSpec.makeMeasureSpec(WukongUi.dp(c,w),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(WukongUi.dp(c,800),View.MeasureSpec.AT_MOST))
            root.layout(0,0,root.measuredWidth,root.measuredHeight)
            for(button in all(root).filterIsInstance<android.widget.RadioButton>())assertTrue("$language width $w radio label stays on one line",button.lineCount<=1)
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(left,top,left+width,top+height);root.requestLayout()
    }
    @Test fun searchableNativePickerPreservesChoiceAndBackAndFitsKeyboardInBothLanguages() {
        assumeTrue(!TaskRuntime.active)
        File(c.filesDir,"execution-back-request").delete();File(c.filesDir,"execution-back-done").delete()
        val oldLanguage=UiLanguage.code(c);val enabled=VirtualScreenState.enabled(c)
        ui { VirtualScreenState.stop(c) }
        try {
            for(language in listOf("zh-CN","en")) {
                UiLanguage.set(c,language)
                val main=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
                var sheet:com.google.android.material.bottomsheet.BottomSheetDialog?=null
                try {
                    val selected=mutableListOf<String?>()
                    ui {
                        assertEquals(if(language=="zh-CN")"悟空" else "Wukong",main.getString(R.string.app_name))
                        assertEquals(main.getString(R.string.app_name),main.findViewById<TextView>(R.id.brand_name).text.toString())
                        sheet=ExecutionOptionsSheet.show(main,null) { selected+=it }
                    }
                    i.waitForIdleSync();assertTrue(selected.isEmpty())
                    ui { checkWidths(sheet!!.findViewById(R.id.execution_options_sheet)!!,language) }
                    i.waitForIdleSync()
                    ui { shot(sheet!!.findViewById(com.google.android.material.R.id.design_bottom_sheet)!!,"execution-options-$language");sheet!!.findViewById<View>(R.id.execution_start_app)!!.performClick() }
                    i.waitForIdleSync()
                    ui { shot(sheet!!.findViewById(com.google.android.material.R.id.design_bottom_sheet)!!,"execution-apps-$language") }
                    val search=sheet!!.findViewById<EditText>(R.id.execution_app_search)!!
                    ui { search.setText("com.android.settings") }
                    i.waitForIdleSync()
                    val list=sheet!!.findViewById<ListView>(R.id.execution_app_list)!!
                    assertEquals(1,list.adapter.count)
                    ui { list.performItemClick(list.getChildAt(0),0,list.adapter.getItemId(0)) }
                    assertEquals(listOf("com.android.settings"),selected)
                    assertFalse(sheet!!.findViewById<View>(R.id.execution_app_picker)!!.isShown)
                    ui { sheet!!.findViewById<View>(R.id.execution_start_app)!!.performClick();search.setText("no-such-app-xyz") }
                    i.waitForIdleSync();assertEquals(0,list.adapter.count)
                    ui { search.setText("com.android.settings");search.requestFocus();search.performClick();main.getSystemService(InputMethodManager::class.java).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT) }
                    await("Search IME visible") { sheet!!.window!!.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true }
                    i.waitForIdleSync()
                    ui {
                        val decor=sheet!!.window!!.decorView;val ime=decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
                        val bounds=android.graphics.Rect();assertTrue(list.getGlobalVisibleRect(bounds))
                        assertTrue("Search results stay visible above the IME",bounds.height()>=WukongUi.dp(c,48)&&bounds.bottom<=main.windowManager.maximumWindowMetrics.bounds.bottom-ime)
                        shot(sheet!!.findViewById(com.google.android.material.R.id.design_bottom_sheet)!!,"execution-search-keyboard-$language")
                    }
                    if(InstrumentationRegistry.getArguments().getString("physical_back")=="true")back(sheet!!) else ui { main.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken,0) }
                    await("IME hidden") { sheet!!.window!!.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())!=true }
                    assertTrue("IME Back must preserve the app picker",sheet!!.findViewById<View>(R.id.execution_app_picker)!!.isShown)
                    back(sheet!!)
                    assertTrue(sheet!!.isShowing);assertFalse(sheet!!.findViewById<View>(R.id.execution_app_picker)!!.isShown)
                    assertEquals(listOf("com.android.settings"),selected)
                    back(sheet!!)
                    assertFalse(sheet!!.isShowing)
                } finally { ui { sheet?.dismiss();main.finish() } }
            }
        } finally { File(c.filesDir,"execution-back-request").delete();File(c.filesDir,"execution-back-done").delete();UiLanguage.set(c,oldLanguage);VirtualScreenState.enabled(c,enabled) }
    }
    @Test fun unavailableStartingAppFallsBackToAutomatic() {
        assumeTrue(!TaskRuntime.active)
        val enabled=VirtualScreenState.enabled(c)
        ui { VirtualScreenState.stop(c) }
        val main=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        var sheet:com.google.android.material.bottomsheet.BottomSheetDialog?=null
        try {
            val choices=mutableListOf<String?>()
            ui {
                sheet=ExecutionOptionsSheet.show(main,"missing.test.app") { choices+=it }
                assertEquals(listOf<String?>(null),choices)
                assertTrue(sheet!!.findViewById<View>(R.id.execution_start_app)!!.contentDescription.contains(main.getString(R.string.ui_home_screen_all_apps)))
            }
        } finally { ui { sheet?.dismiss();VirtualScreenState.stop(c);main.finish() };VirtualScreenState.enabled(c,enabled) }
    }

}
