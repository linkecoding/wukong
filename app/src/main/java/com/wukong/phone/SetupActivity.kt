package com.wukong.phone

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A resumable preparation flow. Completion returns to the draft; it never starts device actions. */
class SetupActivity : LocalizedActivity() {
    private enum class Stage { MODEL, PHONE, CONTROLS, READY }
    private lateinit var body: LinearLayout
    private lateinit var ui: SetupViews
    private lateinit var actionBar: LinearLayout
    private var stage = Stage.MODEL
    private var modelOnly = false
    private var connected = false
    private var checking = false
    private var submitting = false
    private var phoneStatus: TextView? = null
    private var job: Future<*>? = null
    private var client: ChatModel? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var discovery: WirelessDiscovery? = null
    private val changed: () -> Unit = {
        if (stage == Stage.PHONE) {
            phoneStatus?.setText(if (WirelessActivation.busy) R.string.setup_connect_recovering else R.string.setup_task_saved)
            findViewById<Button>(R.id.setup_primary)?.isEnabled = !WirelessActivation.busy
        }
        if (!WirelessActivation.busy) checkConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SetupViews(this)
        modelOnly = intent.getBooleanExtra("model_only", false)
        stage = if (modelOnly || runCatching { SettingsStore(this).load()?.also { it.validate() } }.getOrNull() == null) Stage.MODEL else Stage.PHONE
        body = ui.column().apply { setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(32)); setBackgroundColor(getColor(R.color.paper)) }
        val root = ui.column().apply { setBackgroundColor(getColor(R.color.paper)) }
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        actionBar = ui.column().apply {
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
            background = WukongUi.shape(this@SetupActivity, R.color.paper, 0, R.color.line)
        }
        root.addView(actionBar)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(root)
        WirelessActivation.listen(changed)
        discovery = WirelessDiscovery(this) { pairing, port ->
            WirelessActivation.discovered(pairing, port)
            if (!pairing) runOnUiThread { if (stage != Stage.MODEL) checkConnection() }
        }.also { if (android.os.Build.VERSION.SDK_INT >= 30) it.start() }
        render()
    }
    override fun onResume() {
        super.onResume()
        if (TaskRuntime.active) { finish(); return }
        if (stage != Stage.MODEL) checkConnection()
    }
    private fun render() {
        if (isFinishing || isDestroyed) return
        body.removeAllViews(); actionBar.removeAllViews()
        ui.back(body) { finish() }
        if (!modelOnly) {
            ui.add(body, ui.text(getString(R.string.setup_steps), 12f, true), 8, 12)
            val goal = TaskDraftStore(this).goal()
            if (goal.isNotBlank()) ui.add(body, ui.text(goal, 14f).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                background = WukongUi.shape(this@SetupActivity, R.color.surface, 12, R.color.line)
            })
        }
        val title = when (stage) { Stage.MODEL -> R.string.setup_model; Stage.PHONE -> R.string.setup_device; Stage.CONTROLS -> R.string.setup_controls; Stage.READY -> R.string.setup_ready }
        ui.add(body, ui.text(getString(title), 28f), 12, 12)
        when (stage) {
            Stage.MODEL -> modelPage()
            Stage.PHONE -> {
                ui.add(body, ui.text(getString(R.string.setup_connect_description), 15f, true))
                phoneStatus = ui.text(getString(if (WirelessActivation.busy) R.string.setup_connect_recovering else R.string.setup_task_saved), 13f, true)
                ui.add(body, phoneStatus!!)
                ui.add(actionBar, ui.button(R.string.setup_connect_action, true) {
                    startActivity(Intent(this, WirelessSetupActivity::class.java).putExtra("return_when_ready", true))
                }.apply { id = R.id.setup_primary; isEnabled = !WirelessActivation.busy })
                ui.add(body, ui.button(R.string.setup_retry) { checkConnection() })
            }
            Stage.CONTROLS -> {
                ui.add(body, ui.text(getString(R.string.setup_controls_description), 15f, true))
                ui.add(actionBar, ui.button(R.string.setup_controls_action, true) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                }.apply { id = R.id.setup_primary })
            }
            Stage.READY -> {
                ui.add(body, ui.text(getString(R.string.setup_ready_description), 15f, true))
                ui.add(actionBar, ui.button(R.string.setup_return_task, true) { setResult(RESULT_OK); finish() }.apply { id = R.id.setup_primary })
            }
        }
    }
    private fun modelPage() {
        ui.add(body, ui.text(getString(R.string.setup_model_description), 15f, true))
        val view = ModelConfigurationView(this, runCatching { SettingsStore(this).load() }.getOrNull())
        ui.add(body, view)
        lateinit var next: Button
        next = ui.button(if (modelOnly) R.string.setup_test_save else R.string.setup_test_continue, true) {
            if (submitting) return@button
            if (TaskRuntime.active) { view.message.setText(R.string.setup_stop_first); return@button }
            val candidate = try { view.candidate() } catch (e: Exception) {
                view.message.text = UiMessages.text(this, e.message.orEmpty()); return@button
            }
            submitting = true; view.setBusy(true); next.isEnabled = false; view.message.setText(R.string.setup_testing)
            val trace = RunLogStore(this).start("connection_test", getString(R.string.ui_model_tool_call_test), candidate)
            val activeClient = ChatModel(candidate, trace = trace); client = activeClient
            job = worker.submit {
                val result = runCatching { activeClient.test() }
                trace?.record("end", JSONObject().put("message", if (result.isSuccess) "connection_test_passed" else "connection_test_failed"))
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    submitting = false; view.setBusy(false); next.isEnabled = true
                    result.fold(onSuccess = {
                        runCatching { SettingsStore(this).save(candidate) }.fold(onSuccess = {
                            if (modelOnly) { setResult(RESULT_OK); finish() }
                            else { stage = Stage.PHONE; render(); checkConnection() }
                        }, onFailure = { view.message.text = UiMessages.text(this, it.message.orEmpty()) })
                    }, onFailure = { view.message.text = getString(R.string.setup_test_failed) + "\n" + UiMessages.text(this, it.message.orEmpty()) })
                }
            }
        }.apply { id = R.id.setup_primary }
        ui.add(actionBar, next)
    }
    private fun checkConnection() {
        if (stage == Stage.MODEL || checking || isFinishing || isDestroyed) return
        checking = true
        worker.execute {
            val ready = runCatching { ShellClient(this).status() }.isSuccess
            if (!ready) ConnectionRecovery.restore(this)
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                connected = ready
                val next = if (!connected) Stage.PHONE else if (!Settings.canDrawOverlays(this)) Stage.CONTROLS else Stage.READY
                if (stage != next) { stage = next; render() }
            }
        }
    }
    override fun onDestroy() {
        WirelessActivation.unlisten(changed); discovery?.close(); client?.cancel(); job?.cancel(true); worker.shutdownNow()
        super.onDestroy()
    }
}
