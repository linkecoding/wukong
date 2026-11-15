package com.wukong.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import com.wukong.core.Phase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var goalInput: EditText
    private lateinit var appPicker: Spinner
    private lateinit var root: LinearLayout
    private var tab = 0
    private var draft = ""
    private var selectedPackage: String? = null
    private var apps = emptyList<Pair<String, String>>()
    private var shellReady = false
    private var virtualToggle: Switch? = null
    private var virtualPreview: VirtualScreenPanel? = null
    private var shellStatus = "正在检查 Shell 连接…"
    private val backendExecutor = Executors.newSingleThreadExecutor()
    private val navigationButtons = mutableListOf<Button>()
    private val executor = Executors.newSingleThreadExecutor()
    private var testJob: Future<*>? = null
    private var testClient: ChatModel? = null
    private val listener: () -> Unit = { if (tab == 0 && ::status.isInitialized) updateTask() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = savedInstanceState?.getString("draft").orEmpty()
        selectedPackage = savedInstanceState?.getString("package")
        root = column().apply { setBackgroundColor(color(R.color.paper)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = row().apply { setPadding(dp(24), dp(16), dp(24), dp(12)); gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("悟空", 26f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("Android · 0.4.1", 12f, muted = true))
        root.addView(header)
        val navigation = row().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        listOf("任务", "历史", "设置", "日志").forEachIndexed { index, label ->
            val item = button(label, primary = false) { navigate(index) }.apply { stateListAnimator = null; elevation = 0f }
            navigationButtons += item
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(navigation)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = column().apply { setPadding(dp(24), dp(16), dp(24), dp(32)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        TaskRuntime.listen(listener)
        WirelessActivation.listen(listener)
        VirtualScreenState.listen(listener)
        navigate(savedInstanceState?.getInt("tab") ?: 0)
    }

    override fun onResume() { super.onResume(); VirtualScreenState.foreground(true); refreshShell(); if (tab == 0 && ::status.isInitialized) updateTask() }
    override fun onStop() { VirtualScreenState.foreground(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); refreshShell() }
    override fun onSaveInstanceState(out: Bundle) {
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        out.putString("draft", draft); out.putString("package", selectedPackage); out.putInt("tab", tab)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        TaskRuntime.unlisten(listener); WirelessActivation.unlisten(listener); VirtualScreenState.unlisten(listener); testJob?.cancel(true); testClient?.cancel(); executor.shutdownNow(); backendExecutor.shutdownNow(); super.onDestroy()
    }

    private fun navigate(index: Int) {
        testJob?.cancel(true); testClient?.cancel(); testClient = null
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        tab = index; body.removeAllViews()
        navigationButtons.forEachIndexed { itemIndex, button ->
            button.isSelected = itemIndex == index
            button.backgroundTintList = ColorStateList.valueOf(color(if (itemIndex == index) R.color.line else R.color.surface))
        }
        when (index) { 0 -> taskPage(); 1 -> historyPage(); 3 -> logsPage(); else -> settingsPage() }
    }

    private fun taskPage() {
        body.addView(text("把这件事，\n交给悟空。", 32f, bold = true))
        body.addView(text("说清楚目标，悟空可以跨应用帮你完成。", muted = true).spaced(8, 24))
        status = text("", 14f, bold = true)
        body.addView(status.spaced(0, 20))
        val preview = VirtualScreenPanel(this).apply { visibility = if (VirtualScreenState.enabled(this@MainActivity)) View.VISIBLE else View.GONE }
        virtualPreview = preview
        virtualToggle = Switch(this).apply {
            text = "在虚拟屏执行"; isChecked = VirtualScreenState.enabled(this@MainActivity); isEnabled = !TaskRuntime.active
            setTextColor(color(R.color.ink)); minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled ->
                VirtualScreenState.enabled(this@MainActivity, enabled)
                preview.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) runCatching { VirtualScreenState.start(this@MainActivity) }.onFailure { errorDialog(it.message ?: "虚拟屏启动失败") }
                else VirtualScreenState.stop(this@MainActivity)
            }
        }.also { body.addView(it) }
        body.addView(preview.spaced(8, 16))
        body.addView(button("手机内激活", primary = false) { startActivity(Intent(this, WirelessSetupActivity::class.java)) }.spaced(0, 20))
        body.addView(text("从哪里开始（可选）", 14f, bold = true))
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        apps = listOf("" to "桌面 · 整机操作") + resolved.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        appPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                if (apps.isEmpty()) listOf("没有可操作的应用") else apps.map { it.second })
            minimumHeight = dp(52)
            val existing = apps.indexOfFirst { it.first == selectedPackage }
            if (existing >= 0) setSelection(existing)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedPackage = apps.getOrNull(position)?.first }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        body.addView(appPicker.spaced(8, 20))
        body.addView(text("你想做什么", 14f, bold = true))
        goalInput = field("例如：搜索无线耳机，告诉我前几条结果", draft).apply {
            minLines = 3; maxLines = 6; gravity = Gravity.TOP; isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        body.addView(goalInput.spaced(8, 12))
        body.addView(text("普通操作自动执行；支付、删除等敏感操作需你确认，可随时停止。", 12f, muted = true).spaced(0, 12))
        startButton = button("开始任务") { startTask() }
        body.addView(startButton.spaced(0, 8))
        stopButton = button("停止当前任务", primary = false) { TaskRuntime.stop() }
        body.addView(stopButton.spaced(0, 24))
        body.addView(text("执行记录", 18f, bold = true))
        detail = text("", 14f).apply { setTextIsSelectable(true) }
        body.addView(detail.spaced(12, 16))
        body.addView(button("检查 Shell 连接", primary = false) { refreshShell() })
        body.addView(button("允许悬浮控制条", primary = false) { allowOverlay() })
        updateTask()
    }

    private fun updateTask() {
        val connected = shellReady
        val phase = TaskRuntime.event.phase
        status.text = if (TaskRuntime.stopRequested && TaskRuntime.active) "正在停止…" else if (phase == Phase.IDLE) {
            shellStatus
        } else phaseName(phase.name)
        status.setTextColor(color(if (phase == Phase.FAILED) R.color.danger else if (!connected && phase == Phase.IDLE) R.color.muted else R.color.success))
        startButton.isEnabled = !TaskRuntime.active && shellReady && !WirelessActivation.busy && (!VirtualScreenState.enabled(this) || VirtualScreenState.displayId > 0)
        virtualToggle?.isEnabled = !TaskRuntime.active
        val virtualEnabled = VirtualScreenState.enabled(this)
        if (virtualToggle?.isChecked != virtualEnabled) virtualToggle?.isChecked = virtualEnabled
        virtualPreview?.visibility = if (virtualEnabled) View.VISIBLE else View.GONE
        stopButton.isEnabled = TaskRuntime.active && !TaskRuntime.stopRequested
        goalInput.isEnabled = !TaskRuntime.active
        appPicker.isEnabled = !TaskRuntime.active
        val events = TaskRuntime.events().takeLast(10)
        detail.text = if (events.isEmpty()) "任务开始后，这里会显示步骤和结果。" else events.joinToString("\n\n") {
            (if (it.step > 0) "${it.step} · " else "") + it.message
        }
    }

    private fun startTask() {
        try {
            val selected = apps.getOrNull(appPicker.selectedItemPosition)
            val settings = SettingsStore(this).load() ?: error("请先在设置中保存模型接口")
            TaskRuntime.start(this, goalInput.text.toString(), selected?.first?.ifBlank { null }, settings)
            updateTask()
        } catch (e: Exception) { errorDialog(e.message ?: "无法开始任务") }
    }

    private fun refreshShell() {
        backendExecutor.submit {
            val result = runCatching { ShellClient(this).status() }
            runOnUiThread {
                if (!isDestroyed) {
                    shellReady = result.isSuccess
                    if (shellReady && VirtualScreenState.enabled(this) && VirtualScreenState.service == null) VirtualScreenState.start(this)
                    shellStatus = if (shellReady) "执行引擎已连接" else "执行引擎未激活 · 点“手机内激活”开始"
                    if (tab == 0 && ::status.isInitialized) updateTask()
                }
            }
        }
    }
    private fun allowOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    }

    private fun settingsPage() {
        body.addView(text("连接你的模型", 28f, bold = true))
        body.addView(text("使用支持工具调用的 Chat Completions 兼容接口。", muted = true).spaced(8, 24))
        val store = SettingsStore(this)
        val saved = try { store.load() } catch (_: Exception) { null }
        body.addView(text("Base URL", 14f, bold = true))
        val endpoint = field("例如 https://api.deepseek.com", saved?.endpoint.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        body.addView(endpoint.spaced(8, 8))
        val fullEndpoint = CheckBox(this).apply {
            text = "使用完整请求地址（不追加路径）"
            isChecked = saved?.useFullEndpoint ?: false
            setTextColor(getColor(R.color.ink))
        }
        body.addView(fullEndpoint)
        body.addView(text("Base URL 不限制结尾，默认在后面追加 /chat/completions。自定义完整请求地址请勾选上方选项。", 13f, muted = true).spaced(0, 16))
        body.addView(text("模型名称", 14f, bold = true))
        val model = field("填写服务商提供的模型 ID", saved?.model.orEmpty())
        body.addView(model.spaced(8, 20))
        val thinking = Switch(this).apply {
            text = "思考模式"
            isChecked = saved?.thinkingEnabled ?: false
            setTextColor(getColor(R.color.ink))
            minHeight = dp(48)
        }
        body.addView(thinking)
        body.addView(text("适用于 DeepSeek 等支持 thinking 参数的服务。开启时由模型选择工具；关闭时要求调用已提供的工具。", 13f, muted = true).spaced(8, 16))
        body.addView(text("API Key", 14f, bold = true))
        val key = field("密钥保存在本机", saved?.apiKey.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        body.addView(key.spaced(8, 16))
        val message = text("", 13f).apply { minHeight = dp(48) }
        fun settings() = ModelSettings(endpoint.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim(), fullEndpoint.isChecked, thinking.isChecked).also { it.validate() }
        body.addView(button("保存模型配置") {
            try { store.save(settings()); message.text = "已加密保存到本机" }
            catch (e: Exception) { message.text = e.message ?: "保存失败" }
        }.spaced(0, 8))
        lateinit var test: Button
        test = button("测试连接", primary = false) {
            try {
                val candidate = settings()
                val trace = RunLogStore(this).start("connection_test", "模型工具调用测试", candidate)
                val client = ChatModel(candidate, trace = trace); testClient = client
                test.isEnabled = false; message.text = "正在测试连接…"
                testJob = executor.submit {
                    val result = runCatching { client.test() }.getOrElse { it.message ?: "连接失败" }
                    trace?.record("end", JSONObject().put("message", result))
                    runOnUiThread { if (!isDestroyed) { test.isEnabled = true; message.text = result } }
                }
            } catch (e: Exception) { message.text = e.message }
        }
        body.addView(test)
        body.addView(message.spaced(8, 20))
        val logs = RunLogStore(this)
        body.addView(Switch(this).apply {
            text = "保留运行日志"; isChecked = logs.enabled; minHeight = dp(48)
            setTextColor(getColor(R.color.ink))
            setOnCheckedChangeListener { _, enabled -> logs.enabled = enabled }
        })
        body.addView(text("记录工具参数、执行结果、耗时和服务端 token 用量，不记录 API Key 或完整界面。日志只保存在本机，保留最近 20 次，每次最多 512 KB。关闭后停止新增，已有日志仍可查看或清空。", 13f, muted = true).spaced(8, 20))
        body.addView(text("数据与权限", 18f, bold = true))
        body.addView(text("悟空内置无线调试配对和执行引擎。Android 11 及以上可在手机内激活，无需电脑或另装工具，也无需启用无障碍服务。重启后需要重新激活。悬浮窗权限仅用于显示确认和停止按钮。", 14f, muted = true).spaced(12, 12))
        body.addView(text("任务目标、应用列表和必要的界面文字会发送给上述模型服务；调用截图工具时也会发送当前画面。\n\nOCR 使用内置 PP-OCRv6 tiny 检测和识别模型，在手机本地运行，无需下载模型或安装其他 App。OCR 文字会作为任务上下文发送给上述模型服务。\n\n密钥通过 Android Keystore 加密，任务历史与日志保存在本机，可随时清除。可在主屏或虚拟屏跨应用操作，支持 OCR、截图与坐标手势；密码、验证码和自动解锁仍需你手动处理。", 14f, muted = true).spaced(12, 20))
        body.addView(button("清除模型配置", primary = false) {
            if (TaskRuntime.active) { errorDialog("请先停止当前任务"); return@button }
            store.clear(); endpoint.setText(""); model.setText(""); key.setText(""); message.text = "模型配置已清除"
        })
    }

    private fun historyPage() {
        body.addView(text("做过的事", 28f, bold = true))
        body.addView(text("最近 30 次任务，仅保存在这台手机。", muted = true).spaced(8, 24))
        val records = HistoryStore(this).read()
        if (records.isEmpty()) body.addView(text("还没有任务记录。\n从一个简单的搜索任务开始。", muted = true).spaced(16, 32))
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        records.forEach {
            body.addView(text("${it.app} · ${format.format(Date(it.timestamp))}", 12f, muted = true))
            body.addView(text(it.goal, 17f, bold = true).spaced(8, 8))
            body.addView(text("${phaseName(it.phase)}\n${it.result}", 14f).apply { setTextIsSelectable(true) }.spaced(0, 20))
            body.addView(View(this).apply { setBackgroundColor(color(R.color.line)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(20) })
        }
        if (records.isNotEmpty()) body.addView(button("清空历史记录", primary = false) {
            AlertDialog.Builder(this).setTitle("清空本机任务历史？").setMessage("删除后无法恢复。")
                .setPositiveButton("清空") { _, _ -> HistoryStore(this).clear(); navigate(1) }.setNegativeButton("取消", null).show()
        })
    }

    private fun logsPage() {
        val store = RunLogStore(this)
        body.addView(text("运行日志", 28f, bold = true))
        body.addView(text(if (store.enabled) "正在保留日志 · 可在设置中关闭" else "保留日志已关闭", 14f, muted = true).spaced(8, 16))
        body.addView(button("刷新日志", primary = false) { navigate(3) })
        val files = store.files()
        if (files.isEmpty()) body.addView(text("还没有运行日志。开启保留日志后，开始任务或测试连接即可记录。", muted = true).spaced(16, 16))
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        for (file in files) {
            val first = store.read(file.name).firstOrNull()
            val goal = first?.optJSONObject("data")?.optString("goal").orEmpty()
            body.addView(button("${format.format(Date(first?.optLong("time") ?: file.lastModified()))}  ${goal.take(48)}", primary = false) { logDetail(file.name) }
                .apply { ellipsize = android.text.TextUtils.TruncateAt.END }.spaced(8, 8))
        }
        if (files.isNotEmpty()) body.addView(button("清空运行日志", primary = false) {
            AlertDialog.Builder(this).setTitle("清空运行日志？").setMessage("删除本机已保存的运行日志。")
                .setPositiveButton("清空") { _, _ -> store.clear(); navigate(3) }.setNegativeButton("取消", null).show()
        }.spaced(16, 8))
    }

    private fun logDetail(name: String) {
        body.removeAllViews()
        val records = RunLogStore(this).read(name)
        body.addView(button("返回日志列表", primary = false) { navigate(3) })
        body.addView(button("刷新这次运行", primary = false) { logDetail(name) }.spaced(8, 8))
        val usages = records.filter { it.optString("type") == "model_response" }.mapNotNull { it.optJSONObject("data")?.optJSONObject("usage") }
        fun sum(key: String) = if (usages.any { it.has(key) && !it.isNull(key) }) usages.sumOf { it.optLong(key, 0) }.toString() else "未返回"
        body.addView(text("Token 累计 · 输入 ${sum("prompt_tokens")} / 输出 ${sum("completion_tokens")} / 合计 ${sum("total_tokens")}", 15f, bold = true).spaced(16, 8))
        val responses = records.count { it.optString("type") == "model_response" || it.optString("type") == "model_error" }
        body.addView(text("已返回用量 ${usages.size} 次 / 未返回 ${responses - usages.size} 次。缺失用量不估算；缓存、推理等细分字段按服务端原始 usage 显示。", 13f, muted = true).spaced(0, 16))
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
        for (record in records) {
            val data = record.optJSONObject("data") ?: JSONObject()
            val step = if (data.has("step")) "第 ${data.optInt("step")} 步 · " else ""
            val label = when (record.optString("type")) {
                "start" -> "开始"; "observation", "device_observation" -> "读取界面"; "observation_error" -> "读屏失败"; "model_request" -> "请求模型"; "model_response" -> "模型响应 / Token"
                "model_error" -> "模型失败"; "tool_call" -> "工具调用"; "device_action" -> "执行设备动作"; "device_result" -> "执行结果"
                "device_error" -> "设备失败"; "approval_requested" -> "等待敏感操作确认"; "approval_result" -> "确认结果"; "end" -> "结束"; else -> "任务状态"
            }
            body.addView(text("${format.format(Date(record.optLong("time")))} · $step$label", 14f, bold = true).spaced(12, 4))
            body.addView(text(data.toString(2), 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }.spaced(0, 8))
        }
    }

    private fun phaseName(value: String) = when (value) {
        "RUNNING" -> "执行中"; "AWAITING_APPROVAL" -> "等待你确认"; "COMPLETED" -> "模型已返回结果"
        "FAILED" -> "任务未完成"; "CANCELLED" -> "任务已停止"; else -> "等待任务"
    }
    private fun errorDialog(message: String) { AlertDialog.Builder(this).setTitle("还需要准备一下").setMessage(message).setPositiveButton("知道了", null).show() }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 16f, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color(if (muted) R.color.muted else R.color.ink))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(4).toFloat(), 1f)
    }
    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 15f; setSingleLine(); minimumHeight = dp(52)
        setTextColor(color(R.color.ink)); setHintTextColor(color(R.color.muted))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        fun shape(stroke: Int) = GradientDrawable().apply {
            setColor(color(R.color.surface)); cornerRadius = dp(8).toFloat(); setStroke(dp(1), color(stroke))
        }
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(R.color.accent))
            addState(intArrayOf(), shape(R.color.line))
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun button(label: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; setSingleLine(); minHeight = dp(48); minimumWidth = 0; minWidth = 0
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        setTextColor(ColorStateList(states, intArrayOf(color(R.color.muted), color(if (primary) R.color.accent_ink else R.color.ink))))
        backgroundTintList = ColorStateList(states, intArrayOf(color(R.color.line), color(if (primary) R.color.accent else R.color.surface)))
        setOnClickListener { click() }
    }
    private fun <T : View> T.spaced(top: Int = 0, bottom: Int = 0): T = apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    }
    private fun color(id: Int) = getColor(id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
