package com.wukong.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.util.concurrent.Executors

/** Guide one Android authorization at a time; manual ports are troubleshooting only. */
class WirelessSetupActivity : LocalizedActivity() {
    private lateinit var content: LinearLayout
    private lateinit var ui: SetupViews
    private lateinit var status: TextView
    private var discovery: WirelessDiscovery? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var connected = false
    private var checking = false
    private var advancedOpen = false
    private var forcePair = false
    private var renderedStep = ""
    private var pairPort: EditText? = null
    private var code: EditText? = null
    private var connectPort: EditText? = null
    private val changed: () -> Unit = {
        if (::status.isInitialized) status.text = UiMessages.text(this, WirelessActivation.message)
        findViewById<Button>(R.id.setup_primary)?.isEnabled = !WirelessActivation.busy
        if (!WirelessActivation.busy) checkConnection()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SetupViews(this)
        content = ui.column().apply { setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(32)); setBackgroundColor(getColor(R.color.paper)) }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        WirelessActivation.listen(changed)
        if (Build.VERSION.SDK_INT >= 30) discovery = WirelessDiscovery(this) { pairing, port ->
            WirelessActivation.discovered(pairing, port)
            if (!pairing) runOnUiThread { checkConnection() }
        }.also { it.start() }
        render(); checkConnection()
    }
    override fun onResume() { super.onResume(); if (::content.isInitialized) { renderIfNeeded(); checkConnection() } }
    private fun hasWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }
    private fun step(): String = when {
        connected -> "done"
        Build.VERSION.SDK_INT < 30 -> "unsupported"
        !hasWifi() -> "wifi"
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0 -> "developer"
        getSharedPreferences("wireless", 0).getBoolean("paired", false) && !forcePair -> "reconnect"
        else -> "pair"
    }
    private fun renderIfNeeded() { if (step() != renderedStep) render() }
    private fun render() {
        renderedStep = step()
        content.removeAllViews()
        ui.back(content) { finish() }
        if (renderedStep != "done") ui.add(content, ui.text(getString(R.string.connect_heading), 24f), 12)
        if (renderedStep != "done") ui.add(content, ui.text(getString(R.string.connect_intro), 14f, true))
        status = ui.text(UiMessages.text(this, WirelessActivation.message), 13f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (renderedStep != "done") ui.add(content, status)
        val title = when (renderedStep) { "done" -> R.string.connect_done; "wifi" -> R.string.connect_wifi_title; "developer" -> R.string.connect_developer_title; "reconnect" -> R.string.connect_paired_title; else -> R.string.connect_pair_title }
        if (renderedStep == "unsupported") {
            ui.add(content, ui.text(getString(R.string.ui_on_device_wireless_activation_requires_android_11_or_later)))
            return
        }
        ui.add(content, ui.text(getString(title), 20f), 12)
        val description = when (renderedStep) { "done" -> R.string.connect_done_body; "wifi" -> R.string.connect_wifi_body; "developer" -> R.string.connect_developer_body; "reconnect" -> R.string.connect_paired_body; else -> R.string.connect_pair_body }
        ui.add(content, ui.text(getString(description), 15f, true), 0, 20)
        val action = when (renderedStep) { "done" -> R.string.setup_return_task; "wifi" -> R.string.connect_wifi_action; "developer" -> R.string.connect_developer_action; else -> R.string.connect_pair_action }
        ui.add(content, ui.button(action, true) {
            safely {
                when (renderedStep) {
                    "done" -> { setResult(RESULT_OK); finish() }
                    "wifi" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    "developer" -> startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    "reconnect" -> openWirelessSettings()
                    else -> startAssistant()
                }
            }
        }.apply { id = R.id.setup_primary; isEnabled = !WirelessActivation.busy })
        if (renderedStep != "done") ui.add(content, ui.button(R.string.connect_recheck) { checkConnection() })
        if (renderedStep == "reconnect") ui.add(content, ui.button(R.string.connect_pair_again) { forcePair = true; render() })
        ui.add(content, ui.button(if (advancedOpen) R.string.connect_hide_advanced else R.string.connect_advanced) { advancedOpen = !advancedOpen; render() }, 16)
        if (advancedOpen) advanced()
    }
    private fun advanced() {
        val details = ui.column()
        ui.add(details, ui.text(getString(R.string.discovery_summary, WirelessActivation.pairingPort ?: "—", WirelessActivation.connectPort ?: "—"), 12f, true))
        ui.add(details, ui.text(getString(R.string.ui_use_the_connection_port_on_the_main_wireless_debugging_screen_not), 13f, true))
        connectPort = ui.field(getString(R.string.ui_connection_port_auto_if_empty)).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        ui.add(details, connectPort!!)
        ui.add(details, ui.button(R.string.ui_activate_engine) { safely { WirelessActivation.activate(this, port(connectPort!!)) } })
        ui.add(details, ui.text(getString(R.string.ui_manual_pairing), 18f), 12)
        pairPort = ui.field(getString(R.string.ui_pairing_port_auto_if_empty)).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        code = ui.field(getString(R.string.ui_six_digit_pairing_code), secret = true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        ui.add(details, pairPort!!); ui.add(details, code!!)
        ui.add(details, ui.button(R.string.ui_pair_activate) {
            val value = code!!.text.toString().trim(); code!!.setText("")
            safely { WirelessActivation.pair(this, value, port(pairPort!!)) }
        })
        content.addView(details)
    }
    private fun checkConnection() {
        if (checking || isFinishing || isDestroyed) return
        checking = true
        worker.execute {
            val ready = runCatching { ShellClient(this).status() }.isSuccess
            if (!ready) ConnectionRecovery.restore(this)
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                connected = ready
                if (ready && intent.getBooleanExtra("return_when_ready", false)) { setResult(RESULT_OK); finish(); return@runOnUiThread }
                renderIfNeeded()
            }
        }
    }
    private fun safely(action: () -> Unit) { runCatching(action).onFailure { status.text = UiMessages.text(this, it.message.orEmpty()) } }
    private fun port(input: EditText): Int? {
        val raw = input.text.toString().trim()
        if (raw.isBlank()) return null
        return raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: error(getString(R.string.ui_port_must_be_between_1_and_65535))
    }
    private fun startAssistant() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41); return
        }
        startForegroundService(Intent(this, WirelessPairingService::class.java))
        openWirelessSettings()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startAssistant()
        else if (requestCode == 41) status.setText(R.string.connect_notification_needed)
    }
    private fun openWirelessSettings() {
        val wireless = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        startActivity(if (wireless.resolveActivity(packageManager) != null) wireless else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }
    override fun onDestroy() { WirelessActivation.unlisten(changed); discovery?.close(); worker.shutdownNow(); super.onDestroy() }
}
