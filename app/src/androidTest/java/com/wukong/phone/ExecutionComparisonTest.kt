package com.wukong.phone

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import com.wukong.phone.script.JsSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real-model paired experiments. Synthetic data, real Android views, shell input and QuickJS. */
@RunWith(AndroidJUnit4::class)
class ExecutionComparisonTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val c get() = i.targetContext
    private val handler = Handler(Looper.getMainLooper())
    private val counts = linkedMapOf("蓝牙耳机" to 3, "充电器" to 5, "数据线" to 2)
    private val baseGoal = "只操作当前测试页面，不打开其他应用。依次查询蓝牙耳机、充电器、数据线，等待每次结果，读出商品件数，分别填入对应件数输入框；成功的查询不得重复，失败时只点重试当前查询重试一次。最后点校验结果，看到校验通过后总结三个关键词各找到多少件商品。"
    private val goals = linkedMapOf(
        "batch" to baseGoal,
        "retry" to baseGoal,
        "wizard" to "只操作当前资料核对测试页，不打开其他应用。在第一步填写姓名悟空测试、城市杭州，并关闭接收演示通知。进入下一步，填写活动时间周六下午、人数2，选择步行。点校验信息，确认全部信息核对通过后总结所有填写内容。"
    )
    private lateinit var activity: Activity
    private lateinit var root: LinearLayout
    private lateinit var top: TextView
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var search: Button
    private lateinit var retry: Button
    private val inputs = linkedMapOf<String, EditText>()
    private val requests = mutableListOf<String>()
    private var invalid = 0
    private var checks = 0
    private var verified = false
    private var caseId = ""
    private var name = ""; private var city = ""; private var notifications = true
    private var time = ""; private var people = ""; private var walking = false

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = i.runOnMainSync(block)
    private fun label(text: String, id: Int = android.view.View.NO_ID) = TextView(activity).apply {
        this.text = text; textSize = 16f; this.id = id
        root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
    private fun field(hint: String, id: Int, value: String = "") = EditText(activity).apply {
        this.hint = hint; this.id = id; textSize = 15f; isSingleLine = true; setText(value)
        root.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
    }
    private fun button(text: String, id: Int, click: () -> Unit) = Button(activity).apply {
        this.text = text; this.id = id; isAllCaps = false; textSize = 14f
        setOnClickListener { click() }; root.addView(this, LinearLayout.LayoutParams(-1, dp(44)))
    }
    private fun page(title: String) {
        root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(72), dp(16), dp(20)); id = android.R.id.custom; isFocusableInTouchMode = true }
        top = label(title)
        activity.setContentView(ScrollView(activity).apply { addView(root) })
        root.requestFocus()
    }
    private fun setup(id: String) = ui {
        handler.removeCallbacksAndMessages(null)
        caseId = id; requests.clear(); inputs.clear(); invalid = 0; checks = 0; verified = false
        name = ""; city = "上海"; notifications = true; time = ""; people = "1"; walking = false
        if (id == "wizard") wizardPage(false) else {
            page(if (id == "retry") "商品查询测试：含失败重试" else "商品查询测试：批量核对")
            query = field("商品关键词", R.id.benchmark_query)
            search = button("查询商品", R.id.benchmark_search) { doQuery(false) }
            retry = button("重试当前查询", R.id.benchmark_retry) { doQuery(true) }.apply { isEnabled = false }
            status = label("尚未查询", R.id.benchmark_result)
            for ((index, word) in counts.keys.withIndex()) inputs[word] = field("${word}件数", listOf(R.id.benchmark_count_a, R.id.benchmark_count_b, R.id.benchmark_count_c)[index])
            button("校验结果", R.id.benchmark_check) {
                checks++
                verified = valuesCorrect() && requests == expectedRequests()
                if (!verified) invalid++
                status.text = if (verified) "校验通过：蓝牙耳机=3，充电器=5，数据线=2" else "校验未通过，请核对查询记录与件数"
            }
        }
    }
    private fun expectedRequests() = if (caseId == "retry") listOf("蓝牙耳机", "充电器", "充电器", "数据线") else counts.keys.toList()
    private fun valuesCorrect() = counts.all { (word, count) -> inputs[word]?.text.toString() == count.toString() }
    private fun doQuery(isRetry: Boolean) {
        val word = query.text.toString().trim()
        if (word !in counts || isRetry && requests.lastOrNull() != word) { invalid++; status.text = "查询失败：请检查关键词"; return }
        requests += word
        search.isEnabled = false; retry.isEnabled = false
        status.text = "正在查询：$word"
        handler.postDelayed({
            val fails = caseId == "retry" && word == "充电器" && requests.count { it == word } == 1
            if (fails) { status.text = "查询失败：充电器，网络暂时不可用，请点重试当前查询"; retry.isEnabled = true }
            else { status.text = "查询结果：$word，找到 ${counts[word]} 件商品"; search.isEnabled = true }
        }, 550)
    }
    private fun wizardPage(second: Boolean) {
        if (!second) {
            page("资料核对测试 · 第一步")
            val n = field("姓名", R.id.benchmark_name, name)
            val ct = field("城市", R.id.benchmark_city, city)
            val notify = CheckBox(activity).apply { text = "接收演示通知"; id = R.id.benchmark_notify; isChecked = notifications; root.addView(this) }
            val error = label("")
            button("下一步", R.id.benchmark_next) {
                name = n.text.toString(); city = ct.text.toString(); notifications = notify.isChecked
                if (name != "悟空测试" || city != "杭州" || notifications) { invalid++; error.text = "第一步未通过，请核对姓名、城市和通知选项" }
                else {
                    root.isEnabled = false
                    handler.postDelayed({ wizardPage(true) }, 450)
                }
            }
        } else {
            page("资料核对测试 · 第二步")
            label("已保留：姓名$name，城市$city，接收演示通知已关闭")
            val t = field("活动时间", R.id.benchmark_time, time)
            val p = field("人数", R.id.benchmark_people, people)
            val walkingView = CheckBox(activity).apply { text = "步行"; id = R.id.benchmark_walk; isChecked = walking; root.addView(this) }
            status = label("请填写第二步", R.id.benchmark_result)
            button("校验信息", R.id.benchmark_check) {
                time = t.text.toString(); people = p.text.toString(); walking = walkingView.isChecked; checks++
                verified = name == "悟空测试" && city == "杭州" && !notifications && time == "周六下午" && people == "2" && walking
                if (!verified) invalid++
                status.text = if (verified) "全部信息核对通过：姓名悟空测试，城市杭州，通知关闭，时间周六下午，人数2，步行" else "第二步未通过，请核对活动时间、人数和步行选项"
            }
        }
    }
    private fun objective(): JSONObject {
        var data = JSONObject()
        ui {
            val passed = verified && checks >= 1 && (caseId == "wizard" || valuesCorrect() && requests == expectedRequests())
            data = JSONObject().put("passed", passed).put("verified", verified).put("invalidActions", invalid).put("checkClicks", checks)
                .put("queries", JSONArray(requests)).put("counts", JSONObject(inputs.mapValues { it.value.text.toString() }))
            if (caseId == "wizard") data.put("form", JSONObject().put("name", name).put("city", city).put("notifications", notifications).put("time", time).put("people", people).put("walking", walking))
        }
        return data
    }
    private fun focusPhysicalWindow() {
        i.waitForIdleSync()
        val bounds = Rect(); ui { top.getGlobalVisibleRect(bounds) }
        val down = SystemClock.uptimeMillis()
        for (kind in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; i.sendPointerSync(e); e.recycle()
        }
    }
    @Test fun fixtureOraclesRecognizeSuccessAndTheRequiredRetry() {
        assumeTrue(!TaskRuntime.active)
        activity = i.startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 3000
            while (true) {
                var ready = false; ui { ready = condition() }
                if (ready) return
                check(SystemClock.elapsedRealtime() < until) { "Fixture did not become ready" }; Thread.sleep(20)
            }
        }
        try {
            for (id in goals.keys) {
                setup(id)
                assertFalse(objective().getBoolean("passed"))
                if (id == "wizard") {
                    ui {
                        activity.findViewById<EditText>(R.id.benchmark_name).setText("悟空测试")
                        activity.findViewById<EditText>(R.id.benchmark_city).setText("杭州")
                        activity.findViewById<CheckBox>(R.id.benchmark_notify).isChecked = false
                        activity.findViewById<Button>(R.id.benchmark_next).performClick()
                    }
                    await { activity.findViewById<EditText>(R.id.benchmark_time) != null }
                    ui {
                        activity.findViewById<EditText>(R.id.benchmark_time).setText("周六下午")
                        activity.findViewById<EditText>(R.id.benchmark_people).setText("2")
                        activity.findViewById<CheckBox>(R.id.benchmark_walk).isChecked = true
                        activity.findViewById<Button>(R.id.benchmark_check).performClick()
                    }
                } else {
                    for ((word, count) in counts) {
                        ui { query.setText(word); search.performClick() }
                        await { !status.text.startsWith("正在查询") }
                        if (id == "retry" && word == "充电器") {
                            ui { assertTrue(retry.isEnabled); retry.performClick() }
                            await { !status.text.startsWith("正在查询") }
                        }
                        ui { inputs.getValue(word).setText(count.toString()) }
                    }
                    ui { activity.findViewById<Button>(R.id.benchmark_check).performClick() }
                }
                assertTrue(id, objective().getBoolean("passed"))
            }
        } finally { ui { handler.removeCallbacksAndMessages(null); activity.finish() } }
    }
    @Test fun capabilityPoliciesAreEnforcedInRequestSchemas() {
        val settings = ModelSettings("https://example.com/v1", "fixture", "not-a-real-key")
        fun names(mode: PlanningMode): List<String> {
            val tools = ChatModel(settings, mode = mode).buildRequest(JSONArray()).getJSONArray("tools")
            return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertEquals(10, names(PlanningMode.AUTO).size)
        assertEquals(9, names(PlanningMode.TOOLS_ONLY).size); assertFalse(names(PlanningMode.TOOLS_ONLY).contains("phone_run_js"))
        assertEquals(listOf("phone_action", "phone_run_js"), names(PlanningMode.SCRIPT_ONLY))
        val action = ChatModel(settings, mode = PlanningMode.SCRIPT_ONLY).buildRequest(JSONArray()).getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertEquals("[\"finish\"]", action.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString())
    }
    @Test fun comparePairedComplexWorkflowsWithTheConfiguredModel() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("live_benchmark") == "true")
        assumeTrue(!TaskRuntime.active)
        val settings = SettingsStore(c).load() ?: error("模型未配置")
        val repetitions = args.getString("repetitions", "2").toInt().also { require(it in 1..5) }
        val selectedCases = args.getString("cases")?.split(',') ?: goals.keys.toList()
        require(selectedCases.all { it in goals })
        val reportName = args.getString("report", "execution-comparison.json").also { require(it.matches(Regex("[a-z0-9-]+\\.json"))) }
        val previousVirtual = VirtualScreenState.enabled(c)
        val store = RunLogStore(c, "-benchmark").apply { enabled = true }
        val rows = JSONArray()
        val report = JSONObject().put("schema", 1).put("model", settings.model).put("provider", ModelPresets.provider(settings)).put("thinking", settings.effectiveThinking)
            .put("device", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE).put("sourceRevision", args.getString("revision", "working-tree"))
            .put("repetitions", repetitions).put("cases", JSONObject(goals.filterKeys { it in selectedCases })).put("runs", rows)
        fun save() {
            val tmp = File(c.filesDir, "$reportName.tmp"); tmp.writeText(report.toString(2)); check(tmp.renameTo(File(c.filesDir, reportName)))
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        ui { VirtualScreenState.enabled(c, false); VirtualScreenState.stop(c) }
        activity = i.startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        ui { activity.setTurnScreenOn(true); activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) }
        save()
        try {
            repeat(repetitions) { repetition ->
                for (id in if (repetition % 2 == 0) selectedCases else selectedCases.reversed()) {
                    val order = if (repetition % 2 == 0) listOf(PlanningMode.TOOLS_ONLY, PlanningMode.SCRIPT_ONLY) else listOf(PlanningMode.SCRIPT_ONLY, PlanningMode.TOOLS_ONLY)
                    for (mode in order) {
                        setup(id); focusPhysicalWindow()
                        val goal = goals.getValue(id)
                        val trace = store.start("benchmark", goal, settings) ?: error("无法创建基准日志")
                        val model = ChatModel(settings, trace = trace, mode = mode)
                        val token = Cancellation(); val control = ExecutionControl()
                        val thread = Thread.currentThread()
                        var denied = 0
                        var result = AgentEvent(Phase.FAILED, "未开始")
                        var elapsed = 0L
                        ShellSession(c).use { session ->
                            val driver = AndroidDriver(session, token, trace, control)
                            JsSession(c, token, control, trace).use { scripts ->
                                val timeout = timer.schedule({ token.cancel(); model.cancel(); scripts.cancelCell(); thread.interrupt() }, 150, TimeUnit.SECONDS)
                                val started = SystemClock.elapsedRealtime()
                                trace.record("benchmark_config", JSONObject().put("case", id).put("mode", mode.name).put("repetition", repetition)
                                    .put("systemPromptSha256", MessageDigest.getInstance("SHA-256").digest(model.planningSystemPrompt().toByteArray()).joinToString("") { "%02x".format(it) }))
                                try {
                                    result = Agent(model, driver, Approval { _, _ -> denied++; false }, allowedPackage = c.packageName, maxSteps = 30,
                                        control = control, completionVerifier = CompletionVerifier(model::verifyCompletion), scriptRunner = if (mode == PlanningMode.SCRIPT_ONLY) scripts else null)
                                        .run(goal, token) { trace.record("agent_event", JSONObject().put("phase", it.phase.name).put("message", it.message).put("step", it.step)) }
                                } finally { elapsed = SystemClock.elapsedRealtime() - started; timeout.cancel(false); Thread.interrupted() }
                            }
                        }
                        trace.record("end", JSONObject().put("phase", result.phase.name).put("message", result.message))
                        val objective = objective()
                        val events = store.read(trace.name, 20000)
                        fun eventsOf(type: String) = events.filter { it.optString("type") == type }.map { it.getJSONObject("data") }
                        val responses = eventsOf("model_response")
                        val usages = responses.mapNotNull { it.optJSONObject("usage") }
                        val completeUsage = usages.size == responses.size && usages.all { it.has("prompt_tokens") && it.has("completion_tokens") && it.has("total_tokens") }
                        val modelMillis = (responses + eventsOf("model_error")).sumOf { it.optLong("durationMs") }
                        val row = JSONObject().put("case", id).put("repetition", repetition).put("mode", mode.name).put("elapsedMs", elapsed)
                            .put("phase", result.phase.name).put("summary", result.message).put("objective", objective)
                            .put("success", result.phase == Phase.COMPLETED && objective.getBoolean("passed"))
                            .put("falseCompletion", result.phase == Phase.COMPLETED && !objective.getBoolean("passed")).put("deniedApprovals", denied)
                            .put("planningRounds", eventsOf("tool_call").size).put("httpRequests", eventsOf("model_request").size).put("modelTimeMs", modelMillis)
                            .put("modelErrors", eventsOf("model_error").size).put("deviceErrors", eventsOf("device_error").size + eventsOf("inspection_error").size).put("scriptErrors", eventsOf("script_error").size).put("scriptCells", eventsOf("script_started").size)
                            .put("scriptCalls", eventsOf("script_call").size).put("usageComplete", completeUsage).put("rawUsage", JSONArray(usages))
                            .put("promptTokens", if (completeUsage) usages.sumOf { it.getLong("prompt_tokens") } else JSONObject.NULL)
                            .put("completionTokens", if (completeUsage) usages.sumOf { it.getLong("completion_tokens") } else JSONObject.NULL)
                            .put("totalTokens", if (completeUsage) usages.sumOf { it.getLong("total_tokens") } else JSONObject.NULL)
                            .put("cacheHitTokens", usages.sumOf { it.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: it.optLong("prompt_cache_hit_tokens") })
                            .put("mutationsAccepted", eventsOf("device_result").count { it.optJSONObject("action")?.optString("kind") in setOf("tap", "input", "scroll", "launch", "home", "back", "tap_at", "swipe", "long_press") })
                            .put("completionReviews", eventsOf("completion_review").size).put("log", trace.name)
                        rows.put(row); save()
                        i.sendStatus(0, Bundle().apply { putString("stream", "\nBENCH ${rows.length()}/${repetitions * selectedCases.size * 2} $id ${mode.name}: success=${row.getBoolean("success")} ${elapsed}ms rounds=${row.getInt("planningRounds")} tokens=${row.opt("totalTokens")}\n") })
                    }
                }
            }
            report.put("finished", true); save()
        } finally {
            timer.shutdownNow(); Thread.interrupted()
            ui { handler.removeCallbacksAndMessages(null); activity.finish(); VirtualScreenState.enabled(c, previousVirtual) }
        }
        // Failures are benchmark outcomes, not grounds for silently rerunning or discarding a sample.
        assertEquals(repetitions * selectedCases.size * 2, rows.length())
    }
}
