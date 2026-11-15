package com.wukong.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.Phase
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationFlowTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun await(check:()->Boolean) { val end=System.currentTimeMillis()+90000; while(!check()&&System.currentTimeMillis()<end)Thread.sleep(80); assertTrue("Expected conversation state did not arrive",check()) }
    private fun <T:Activity> resumed(type:Class<T>):T? {
        var found:T?=null
        ui { found=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { type.isInstance(it) }?.let(type::cast) }
        return found
    }
    private fun shot(a:Activity,name:String) = ui {
        val view=a.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        File(c.filesDir,"chat-review").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun summariesPersistAndContextIsBoundedWithoutOtherConversations() {
        val prefs=c.getSharedPreferences("conversations-conversationtest",0); prefs.edit().clear().commit()
        try {
            val store=ConversationStore(c,"-conversationtest"); val first=store.create()
            repeat(8) { n -> val id=store.begin(first,"Request $n",null); store.finish(first,id,"COMPLETED","x".repeat(3000),1234) }
            assertEquals(8,ConversationStore(c,"-conversationtest").get(first)!!.turns.size)
            val history=JSONArray(store.context(first)); assertEquals(6,history.length()); assertEquals("Request 2",history.getJSONObject(0).getString("request"))
            assertEquals(2000,history.getJSONObject(0).getString("result").length)
            store.saveDraft(first,"draft one")
            val second=store.create(); store.saveDraft(second,"draft two")
            assertEquals("draft one",store.draft(first)); assertEquals("draft two",store.draft(second))
            assertEquals("[]",store.context(second)); assertEquals(second,store.selected())
            val task=store.begin(second,"Do not replay",null)
            assertEquals("RUNNING",store.get(second)!!.turns.single().phase)
            store.finish(second,task,"CANCELLED","Stopped",10); assertEquals("CANCELLED",store.get(second)!!.turns.single().phase)
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun conversationLayoutsAndDrawerUseNativeControlsAtFourWidths() {
        assumeTrue(!TaskRuntime.active)
        val a=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs=c.getSharedPreferences("conversations-conversationtest",0); prefs.edit().clear().commit()
        try {
            val store=ConversationStore(c,"-conversationtest"); val id=store.create()
            for(width in listOf(320,375,414,768)) ui {
                val page=ConversationView(a,id,{}, {},{}, {},{},store)
                val px=WukongUi.dp(a,width)
                page.measure(View.MeasureSpec.makeMeasureSpec(px,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)); page.layout(0,0,px,page.measuredHeight)
                val bitmap=Bitmap.createBitmap(px,page.height,Bitmap.Config.ARGB_8888); page.draw(Canvas(bitmap))
                File(c.filesDir,"chat-review").apply { mkdirs() }.resolve("empty-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                val composer=page.composer
                composer.measure(View.MeasureSpec.makeMeasureSpec(px,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));composer.layout(0,0,px,composer.measuredHeight)
                val composerBitmap=Bitmap.createBitmap(px,composer.height,Bitmap.Config.ARGB_8888);composer.draw(Canvas(composerBitmap))
                File(c.filesDir,"chat-review/composer-$width.png").outputStream().use { composerBitmap.compress(Bitmap.CompressFormat.PNG,100,it) };composerBitmap.recycle()
                assertTrue(page.measuredWidth<=px); assertTrue(composer.width<=px)
            }
            ui { a.findViewById<View>(R.id.chat_new).performClick() }; i.waitForIdleSync(); shot(a,"home")
            assertNull(a.findViewById<View>(R.id.main_navigation))
            ui { a.findViewById<View>(R.id.chat_menu).performClick() }; i.waitForIdleSync()
            ui {
                val drawer=WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.chat_drawer)!=null }
                val settings=all(drawer).filterIsInstance<TextView>().first { it.text.toString()==a.getString(R.string.ui_settings) }
                (if(settings.isClickable)settings else settings.parent as View).performClick()
            }
            await { resumed(SettingsActivity::class.java)!=null }
            val settingsActivity=resumed(SettingsActivity::class.java)!!;shot(settingsActivity,"settings")
            assertNotSame(a,settingsActivity)
            ui { settingsActivity.onBackPressed() }; await { a.hasWindowFocus() }
            assertSame(a,resumed(MainActivity::class.java))
            ui {
                a.findViewById<View>(R.id.chat_menu).performClick()
                a.findViewById<com.google.android.material.navigation.NavigationView>(R.id.chat_drawer).menu.performIdentifierAction(R.id.chat_history,0)
            }
            await { resumed(HistoryActivity::class.java)!=null }
            val history=resumed(HistoryActivity::class.java)!!;shot(history,"history")
            ui { history.onBackPressed() };await { a.hasWindowFocus() }
            assertSame(a,resumed(MainActivity::class.java))
            i.waitForIdleSync(); assertNotNull(a.findViewById<View>(R.id.task_goal))
        } finally { prefs.edit().clear().commit(); ui { a.finish() } }
    }
    @Test fun executionOptionsAreANativeBottomSheetAndDrawerBackDoesNotLeaveHome() {
        assumeTrue(!TaskRuntime.active)
        val a=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ui { a.findViewById<View>(R.id.chat_menu).performClick() };i.waitForIdleSync()
            val drawer=a.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.chat_drawer_layout)
            await { drawer.isDrawerOpen(androidx.core.view.GravityCompat.START) }
            ui {
                val menu=a.findViewById<com.google.android.material.navigation.NavigationView>(R.id.chat_drawer).menu
                val ordered=(0 until menu.size()).map { menu.getItem(it) }
                val historyIndex=ordered.indexOfFirst { it.itemId==R.id.chat_history }
                assertTrue(ordered.withIndex().filter { it.value.groupId==1 }.all { it.index<historyIndex })
                assertTrue(historyIndex<ordered.indexOfFirst { it.itemId==R.id.chat_settings })
            }
            ui { a.onBackPressed() };await { !drawer.isDrawerOpen(androidx.core.view.GravityCompat.START) };assertFalse(a.isFinishing)
            ui { a.findViewById<View>(R.id.chat_options).performClick() };i.waitForIdleSync()
            await {
                var expanded=false
                ui { WindowInspector.getGlobalWindowViews().firstOrNull { it.findViewById<View>(R.id.execution_options_sheet)!=null }?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { expanded=com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).state==com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED } }
                expanded
            }
            ui {
                val root=WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.execution_options_sheet)!=null }
                assertNotNull(root.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet))
                assertNotNull(root.findViewById<View>(R.id.execution_virtual));assertNotNull(root.findViewById<View>(R.id.execution_phone))
                val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
                File(c.filesDir,"chat-review/options.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            assertFalse(TaskRuntime.active)
            val previous=VirtualScreenState.enabled(c)
            try {
                ui { WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.execution_options_sheet)!=null }.findViewById<RadioButton>(R.id.execution_phone).performClick() }
                await { !VirtualScreenState.enabled(c)&&VirtualScreenState.service==null }
                ui { WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.execution_options_sheet)!=null }.findViewById<RadioButton>(R.id.execution_virtual).performClick() }
                await { VirtualScreenState.enabled(c)&&VirtualScreenState.displayId>0 }
                assertFalse(TaskRuntime.active)
            } finally { if(!previous)ui { VirtualScreenState.stop(c) } }
        } finally { ui { a.finish() } }
    }
    @Test fun englishConversationScreensUseTheSameNativeLayout() {
        assumeTrue(!TaskRuntime.active)
        val language=UiLanguage.code(c);val virtual=VirtualScreenState.enabled(c)
        var a:Activity?=null
        try {
            ui { VirtualScreenState.stop(c);UiLanguage.set(c,"en") }
            await { VirtualScreenState.service==null }
            a=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui { a!!.findViewById<View>(R.id.chat_new).performClick() };i.waitForIdleSync();shot(a!!,"home-en")
            ui { a!!.findViewById<View>(R.id.chat_options).performClick() };i.waitForIdleSync()
            ui {
                val root=WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.execution_options_sheet)!=null }
                val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
                File(c.filesDir,"chat-review/options-en.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        } finally { ui { a?.finish();VirtualScreenState.stop(c);UiLanguage.set(c,language) };VirtualScreenState.enabled(c,virtual) }
    }
    @Test fun composerAvoidsTheRealKeyboardWithoutRestoringBottomNavigation() {
        assumeTrue(!TaskRuntime.active)
        val a=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ui { a.findViewById<View>(R.id.chat_new).performClick() }
            await { !VirtualScreenState.enabled(c) || VirtualScreenState.displayId>0&&VirtualScreenState.bitmap!=null }
            i.waitForIdleSync();shot(a,"home")
            val rect=android.graphics.Rect()
            ui { a.findViewById<EditText>(R.id.task_goal).getGlobalVisibleRect(rect) }
            val down=android.os.SystemClock.uptimeMillis()
            for(kind in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),kind,rect.centerX().toFloat(),rect.centerY().toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(event);event.recycle()
            }
            await { a.hasWindowFocus() }
            ui {
                val input=a.findViewById<EditText>(R.id.task_goal);input.setText("Keyboard test");input.requestFocus()
                a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            await { androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
            i.waitForIdleSync()
            ui {
                val send=a.findViewById<View>(R.id.task_run);val position=IntArray(2);send.getLocationOnScreen(position)
                val insets=androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)!!
                @Suppress("DEPRECATION") val metrics=android.util.DisplayMetrics().also { a.windowManager.defaultDisplay.getRealMetrics(it) }
                val keyboardTop=metrics.heightPixels-insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                assertTrue("Composer send button must remain above IME",position[1]+send.height<=keyboardTop+2)
                assertNull(a.findViewById<View>(R.id.main_navigation))
            }
            shot(a,"keyboard")
            ui { a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken,0) }
            await { androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==false }
            ui { a.findViewById<View>(R.id.chat_menu).performClick() }
            await { a.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.chat_drawer_layout).isDrawerOpen(androidx.core.view.GravityCompat.START) }
            shot(a,"drawer-final")
        } finally {
            ui { a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.finish() }
        }
    }
    @Test fun realConversationRunsShowsPreviewAndContinuesOnTheCurrentDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model")=="true")
        assumeTrue(!TaskRuntime.active)
        val previous=VirtualScreenState.enabled(c)
        val a=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ui { a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); a.findViewById<View>(R.id.chat_new).performClick(); VirtualScreenState.enabled(c,true); VirtualScreenState.start(a) }
            await { VirtualScreenState.displayId>0&&VirtualScreenState.bitmap!=null }
            val store=ConversationStore(c); val thread=store.selected()!!
            ui { a.findViewById<EditText>(R.id.task_goal).setText("打开系统设置首页后结束，不修改任何设置。告诉我你打开了什么应用。"); a.findViewById<View>(R.id.task_run).performClick(); VirtualScreenState.control(true) }
            await { VirtualScreenState.manual&&TaskRuntime.active }
            ui { a.findViewById<View>(R.id.chat_preview).performClick() }; i.waitForIdleSync(); shot(a,"running-preview")
            ui { a.moveTaskToBack(true) }; await { VirtualScreenState.service!!.floatingVisible }
            ui {
                val panel=WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.virtual_preview_close)!=null }
                val bitmap=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888);panel.draw(Canvas(bitmap))
                File(c.filesDir,"chat-review/floating.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                panel.findViewById<View>(R.id.virtual_preview_close).performClick()
            }
            assertTrue(TaskRuntime.active)
            ui { c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await { VirtualScreenState.foreground }; VirtualScreenState.control(false)
            await { !TaskRuntime.active }; assertEquals(TaskRuntime.event.message,Phase.COMPLETED,TaskRuntime.event.phase)
            await { store.get(thread)?.turns?.firstOrNull()?.result?.isNotBlank()==true }; i.waitForIdleSync(); shot(a,"result")
            val displayId=VirtualScreenState.displayId
            ui { a.findViewById<EditText>(R.id.task_goal).setText("刚才打开的是哪个应用？保持当前页面不变，读取当前界面再回答，不要导航或修改任何设置。"); a.findViewById<View>(R.id.task_run).performClick() }
            await { TaskRuntime.active||store.get(thread)!!.turns.size==2 }; await { !TaskRuntime.active }
            assertEquals(TaskRuntime.event.message,Phase.COMPLETED,TaskRuntime.event.phase)
            assertEquals(displayId,VirtualScreenState.displayId); assertEquals(2,store.get(thread)!!.turns.size)
            val last=store.get(thread)!!.turns.last(); assertEquals("COMPLETED",last.phase)
            val events=RunLogStore(c).read(last.logName!!)
            assertFalse("Follow-up must not automatically go home/relaunch",events.any { it.optString("type")=="device_result" && it.getJSONObject("data").optJSONObject("action")?.optString("kind") in setOf("home","launch","tap","input") })
            i.waitForIdleSync(); shot(a,"follow-up")
            ui { all(a.window.decorView).first { it.tag=="conversation-task" && it.isClickable }.performClick() }
            await { resumed(TaskDetailActivity::class.java)!=null }
            val details=resumed(TaskDetailActivity::class.java)!!;shot(details,"execution-detail")
            ui { details.onBackPressed() };await { a.hasWindowFocus() }
            i.waitForIdleSync(); assertEquals(2,all(a.window.decorView).count { it.tag=="conversation-request" })
            ui { a.findViewById<View>(R.id.chat_menu).performClick() }; i.waitForIdleSync()
            ui {
                val drawer=WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.chat_drawer)!=null }
                val bitmap=Bitmap.createBitmap(drawer.width,drawer.height,Bitmap.Config.ARGB_8888);drawer.draw(Canvas(bitmap))
                File(c.filesDir,"chat-review/drawer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        } finally { TaskRuntime.stop(); ui { VirtualScreenState.stop(c);a.finish() };VirtualScreenState.enabled(c,previous) }
    }
}
