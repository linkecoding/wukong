package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class InspectionFlowTest {
    @Test fun queryWaitAndVerificationUseRealNodesWhileOtherContentChanges() = fixture(false)
    @Test fun realModelCompletesAQueryAndVerifiesTheResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        fixture(true)
    }
    @Test fun jsScriptQueriesWaitsListensAndOperatesTheRealFixture() = fixture(false, true)
    private fun fixture(live: Boolean, scripted: Boolean = false) {
        val i = InstrumentationRegistry.getInstrumentation(); val c = i.targetContext
        assumeTrue(!TaskRuntime.active)
        val previousVirtual = VirtualScreenState.enabled(c)
        i.runOnMainSync { VirtualScreenState.enabled(c, false); VirtualScreenState.stop(c) }
        val a = i.startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        val handler = Handler(Looper.getMainLooper()); val clicks = AtomicInteger(); val report = JSONObject()
        lateinit var input: EditText; lateinit var result: TextView; lateinit var query: Button; lateinit var ticker: TextView
        try {
            i.runOnMainSync {
                a.setTurnScreenOn(true); a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 160, 30, 20); id = android.R.id.custom }
                root.addView(TextView(a).apply { text = "商品查询测试"; textSize = 22f })
                input = EditText(a).apply { hint = "商品关键词"; id = android.R.id.edit; isSingleLine = true }
                root.addView(input, LinearLayout.LayoutParams(-1, 140))
                query = Button(a).apply {
                    text = "查询商品"; isAllCaps = false; id = android.R.id.button1; isEnabled = false
                    setOnClickListener {
                        clicks.incrementAndGet(); val keyword = input.text.toString(); result.text = "正在查询商品"
                        handler.postDelayed({ result.text = "查询结果：$keyword，找到 3 件商品" }, 500)
                    }
                }
                root.addView(query, LinearLayout.LayoutParams(-1, 140))
                result = TextView(a).apply { text = "尚未查询"; textSize = 20f; id = android.R.id.text1 }; root.addView(result)
                ticker = TextView(a).apply { text = "推荐信息" }; root.addView(ticker)
                a.setContentView(root)
            }
            i.waitForIdleSync()
            val bounds = Rect(); i.runOnMainSync { result.getGlobalVisibleRect(bounds) }
            val down = SystemClock.uptimeMillis()
            for (kind in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; i.sendPointerSync(e); e.recycle()
            }
            val settings = SettingsStore(c).load() ?: if (live) error("模型未配置") else ModelSettings("https://example.com/v1", "fixture", "fixture-key")
            val trace = RunLogStore(c).start("validation", "只在测试页面查询蓝牙耳机", settings)
            ShellSession(c).use { session ->
                val control = ExecutionControl(); val driver = AndroidDriver(session, trace = trace, control = control)
                if (scripted) {
                    i.runOnMainSync { handler.postDelayed({ query.isEnabled = true }, 400) }
                    com.wukong.phone.script.JsSession(c, Cancellation(), control, trace).use { scripts ->
                        val code = """
                            state.keyword = '蓝牙耳机';
                            wukong.setText(wukong.id('android:id/edit'), 'temporary');
                            if (!wukong.setText(wukong.id('android:id/edit'), '').verified) throw new Error('Could not clear');
                            wukong.setText(wukong.id('android:id/edit'), state.keyword);
                            if (!wukong.setText(wukong.id('android:id/edit'), state.keyword).noOp) throw new Error('Repeated input');
                            wukong.tap(wukong.id('android:id/button1'), {timeoutMs:2000});
                            const before = wukong.observe();
                            let events = 0;
                            const unsubscribe = wukong.events.onChange(() => events++);
                            wukong.events.next({after:before,timeoutMs:2000});
                            unsubscribe();
                            const ready = wukong.waitAny({success:wukong.textContains('蓝牙耳机，找到 3 件商品'),error:wukong.textContains('查询失败')});
                            if (ready.key !== 'success') throw new Error('结果未出现');
                            state.events = events;
                            return wukong.query({textContains:'蓝牙耳机，找到 3 件商品'});
                        """.trimIndent()
                        var rounds = 0
                        val outcome = Agent(Model { _, _, history ->
                            if (++rounds == 1) Action.Script(code) else {
                                assertTrue(history.last().result, !history.last().result.contains("脚本已停止"))
                                Action.Finish("找到 3 件商品", evidence = listOf(ScreenCondition(NodeSelector(textContains = "蓝牙耳机，找到 3 件商品"))))
                            }
                        }, driver, Approval { _, _ -> false }, control = control, scriptRunner = scripts).run("查询蓝牙耳机商品数量", Cancellation()) {}
                        assertEquals(outcome.message, Phase.COMPLETED, outcome.phase); assertEquals(1, clicks.get())
                        assertEquals("1", scripts.run("return state.events;", object : ScriptHost {
                            override fun observe() = driver.observeNow()
                            override fun execute(action: Action, expected: Screen) = error("No mutation")
                        }))
                    }
                } else if (live) {
                    i.runOnMainSync { handler.postDelayed({ query.isEnabled = true }, 500) }
                    val model = ChatModel(settings, trace = trace)
                    val scripts = com.wukong.phone.script.JsSession(c, Cancellation(), control, trace)
                    val outcome = try { Agent(model, driver, Approval { _, _ -> false }, maxSteps = 15, control = control, completionVerifier = CompletionVerifier(model::verifyCompletion), scriptRunner = scripts)
                        .run("请使用 phone_run_js 编写一个短流程，只在当前商品查询测试页面操作：查找商品关键词输入框，等查询商品按钮可用后，输入蓝牙耳机并查询。确认出现蓝牙耳机的商品查询结果，告诉我找到多少件商品。不要打开其他应用。", Cancellation()) {} } finally { scripts.close() }
                    assertEquals(outcome.message, Phase.COMPLETED, outcome.phase)
                    assertTrue(outcome.message.contains("3") || outcome.message.contains("三"))
                    assertEquals("Never replay a successful query", 1, clicks.get())
                    trace?.let { log ->
                        val records = RunLogStore(c).read(log.name)
                        assertTrue("Real model must complete a JS cell", records.any { it.optString("type") == "script_completed" })
                    }
                    val finalScreen = driver.observe()
                    driver.execute(Action.Screenshot, finalScreen)
                    val evidence = driver.observe()
                    val confirmed = model.verifyCompletion("查询蓝牙耳机商品，告诉我数量", "已找到 3 件蓝牙耳机商品", evidence, emptyList())
                    assertTrue(confirmed.detail, confirmed.verified)
                    val rejected = model.verifyCompletion("购买并支付蓝牙耳机", "已购买并付款成功", evidence, emptyList())
                    assertFalse("Query results cannot prove a payment: ${rejected.detail}", rejected.verified)
                    report.put("phase", outcome.phase.name).put("result", outcome.message).put("semanticVerified", confirmed.verified).put("falsePaymentRejected", !rejected.verified).put("log", trace?.name)
                } else {
                    val initial = driver.observe()
                    val found = JSONObject(driver.execute(Action.Query(NodeSelector(editable = true, ancestorResourceId = "android:id/custom")), initial))
                    assertEquals(1, found.getInt("totalMatches"))
                    assertEquals("android:id/edit", found.getJSONArray("nodes").getJSONObject(0).getString("resourceId"))
                    val buttonCondition = ScreenCondition(NodeSelector(resourceId = "android:id/button1", enabled = true, clickable = true))
                    i.runOnMainSync { handler.postDelayed({ query.isEnabled = true }, 400) }
                    val enabled = JSONObject(driver.execute(Action.WaitUntil(listOf(buttonCondition), timeoutMillis = 2000), initial))
                    assertEquals("met", enabled.getJSONObject("verification").getString("state"))
                    val fresh = driver.observe(); driver.execute(Action.Input(fresh.nodes.single { it.resourceId == "android:id/edit" }.id, "蓝牙耳机"), fresh)
                    val filled = driver.observe(); driver.execute(Action.Tap(filled.nodes.single { it.resourceId == "android:id/button1" }.id), filled)
                    i.runOnMainSync {
                        fun tick(n: Int) { ticker.text = "推荐信息 $n"; handler.postDelayed({ tick(n + 1) }, 40) }
                        tick(0)
                    }
                    val condition = ScreenCondition(NodeSelector(textContains = "蓝牙耳机，找到 3 件商品"))
                    val waited = JSONObject(driver.execute(Action.WaitUntil(listOf(condition), timeoutMillis = 1800), filled))
                    assertEquals("met", waited.getJSONObject("verification").getString("state")); assertFalse(waited.getBoolean("timedOut"))
                    report.put("waitMs", waited.getLong("elapsedMs")).put("samples", waited.getInt("samples"))
                    val timeout = JSONObject(driver.execute(Action.WaitUntil(listOf(ScreenCondition(NodeSelector(textEquals = "不存在的结果"))), timeoutMillis = 300), filled))
                    assertTrue(timeout.getBoolean("timedOut")); assertEquals("not_met", timeout.getJSONObject("verification").getString("state"))
                    val verified = JSONObject(driver.execute(Action.Verify(listOf(condition)), filled))
                    assertEquals("met", verified.getJSONObject("verification").getString("state"))
                    assertEquals(1, clicks.get())
                    i.runOnMainSync { handler.removeCallbacksAndMessages(null) }
                    val state = driver.observe()
                    control.pause(true)
                    val denied = runCatching { driver.execute(Action.WaitUntil(listOf(condition)), state) }.exceptionOrNull()
                    assertTrue(denied?.message.orEmpty().contains("用户正在接管")); control.pause(false)
                }
            }
        } finally {
            i.runOnMainSync { handler.removeCallbacksAndMessages(null); a.finish(); VirtualScreenState.enabled(c, previousVirtual) }
            c.openFileOutput(if (live) "inspection-live-report.json" else "inspection-report.json", 0).use { it.write(report.toString().toByteArray()) }
        }
    }
    @Test fun compactOverviewKeepsFocusedNodesAndQueriesCanReadBeyondIt() {
        val screen = Screen("demo.app", "one", (1..120).map { UiNode(it, "条目$it", resourceId = "demo:id/item") })
        val initial = screen.toJson()
        assertEquals(60, initial.getJSONArray("nodes").length()); assertTrue(initial.getBoolean("overviewTruncated")); assertFalse(initial.getBoolean("sourceTruncated"))
        val query = Action.Query(NodeSelector(textEquals = "条目110"))
        assertEquals(110, screen.toJson(query).getJSONArray("nodes").getJSONObject(0).getInt("id"))
        val result = Inspector({ screen }).run(query).toJson()
        assertEquals(110, result.getJSONArray("nodes").getJSONObject(0).getInt("id"))
        assertEquals(query.selector, query.selector.toJson().toSelector())
        assertEquals(101, screen.toJson(Action.Query(offset = 100)).getJSONArray("nodes").getJSONObject(0).getInt("id"))
    }
}
