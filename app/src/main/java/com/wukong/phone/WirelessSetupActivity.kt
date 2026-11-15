package com.wukong.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*

class WirelessSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var discovered: TextView
    private lateinit var pairPort: EditText
    private lateinit var code: EditText
    private lateinit var connectPort: EditText
    private lateinit var pairButton: Button
    private lateinit var activateButton: Button
    private var discovery: WirelessDiscovery? = null
    private val changed: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(32)); setBackgroundColor(getColor(R.color.paper)) }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        fun text(value: String, size: Float = 15f) = TextView(this).apply { text = value; textSize = size; setTextColor(getColor(R.color.ink)); setPadding(0, dp(8), 0, dp(12)) }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setSingleLine(); minHeight = dp(48)
            setTextColor(getColor(R.color.accent_ink)); backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setOnClickListener { runCatching { action() }.onFailure { status.text = it.message ?: "操作失败，请重试" } }
        }
        fun field(hint: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; textSize = 15f; setSingleLine(); minHeight = dp(52)
            inputType = InputType.TYPE_CLASS_NUMBER or if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = !secret
        }
        content.addView(text("手机内激活", 28f))
        content.addView(text("无需电脑，无需另装工具。首次配对后，重启手机通常只需重新激活。"))
        status = text("").apply { setTextColor(getColor(R.color.success)) }
        content.addView(status)
        content.addView(text("1. 连接 Wi-Fi，开启开发者选项中的无线调试。\n2. 启动配对助手，在系统页面选择“使用配对码配对设备”。\n3. 保持配对窗口打开，下拉通知栏，在悟空通知中输入六位配对码。\n4. 悟空会自动连接本机并启动执行引擎。"))
        content.addView(button("启动配对助手") { startAssistant() })
        content.addView(button("打开无线调试设置") { openWirelessSettings() })
        discovered = text("")
        content.addView(discovered)
        content.addView(text("已配对？直接重新激活", 20f))
        content.addView(text("连接端口在无线调试主页显示，与配对窗口中的端口不同。留空时使用本机自动发现结果。"))
        connectPort = field("连接端口（可留空，自动发现）")
        content.addView(connectPort)
        activateButton = button("激活执行引擎") { WirelessActivation.activate(this, port(connectPort)) }
        content.addView(activateButton)
        content.addView(text("手动配对", 20f))
        content.addView(text("自动发现失败时填写配对端口。请保持系统配对窗口打开，可用分屏或通知栏助手完成。配对码不会保存。"))
        pairPort = field("配对端口（可留空，自动发现）")
        code = field("六位配对码", secret = true)
        content.addView(pairPort); content.addView(code)
        pairButton = button("配对并激活") {
            val value = code.text.toString().trim()
            code.setText("")
            WirelessActivation.pair(this, value, port(pairPort))
        }
        content.addView(pairButton)
        content.addView(button("返回悟空") { finish() })
        WirelessActivation.listen(changed)
        if (Build.VERSION.SDK_INT >= 30) discovery = WirelessDiscovery(this, WirelessActivation::discovered).also { it.start() }
        refresh()
    }
    private fun port(input: EditText): Int? {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return null
        val number = raw.toIntOrNull()
        require(number != null && number in 1..65535) { "端口须在 1–65535 之间" }
        return number
    }
    private fun refresh() {
        if (!::status.isInitialized) return
        status.text = if (Build.VERSION.SDK_INT < 30) "此系统版本不支持手机内无线调试激活，需要 Android 11 或以上" else WirelessActivation.message
        discovered.text = "本机自动发现 · 配对端口：${WirelessActivation.pairingPort ?: "等待配对窗口"} · 连接端口：${WirelessActivation.connectPort ?: "等待无线调试"}"
        pairButton.isEnabled = !WirelessActivation.busy && Build.VERSION.SDK_INT >= 30
        activateButton.isEnabled = pairButton.isEnabled
    }
    private fun startAssistant() {
        require(Build.VERSION.SDK_INT >= 30) { "免电脑激活需要 Android 11 或以上" }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41); return
        }
        startForegroundService(Intent(this, WirelessPairingService::class.java))
        openWirelessSettings()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startAssistant()
        else if (requestCode == 41) status.text = "未允许通知，可使用手动配对；通知助手需要通知权限。"
    }
    private fun openWirelessSettings() {
        val wireless = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        startActivity(if (wireless.resolveActivity(packageManager) != null) wireless else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }
    override fun onDestroy() { WirelessActivation.unlisten(changed); discovery?.close(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
