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
import android.text.Editable
import android.text.TextWatcher
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

class MainActivity : LocalizedActivity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var goalInput: EditText
    private lateinit var appPicker: Spinner
    private lateinit var appHeader: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var activityHeading: TextView
    private lateinit var pageScroll: ScrollView
    private var taskOptionsOpen = false
    private var appOptions: View? = null
    private var displayOptions: View? = null
    private var discovery: WirelessDiscovery? = null
    private val shellCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activationListener: () -> Unit = { if (!WirelessActivation.busy) refreshShell() }
    private var detailBack: (() -> Unit)? = null
    private var tab = 0
    private var draft = ""
    private var selectedPackage: String? = null
    private var apps = emptyList<Pair<String, String>>()
    private var shellReady = false
    private var virtualToggle: Switch? = null
    private var virtualPreview: VirtualScreenPanel? = null
    private var shellStatus = ""
    private val backendExecutor = Executors.newSingleThreadExecutor()
    private val navigationButtons = mutableListOf<Button>()
    private val listener: () -> Unit = { if (tab == 0 && ::status.isInitialized) updateTask() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shellStatus = getString(R.string.ui_checking_the_execution_engine)
        draft = savedInstanceState?.getString("draft") ?: TaskDraftStore(this).goal()
        taskOptionsOpen = savedInstanceState?.getBoolean("task_options", false) ?: false
        selectedPackage = savedInstanceState?.getString("package") ?: TaskDraftStore(this).packageName()
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
        appHeader = row()
        showBrandHeader()
        root.addView(appHeader)
        pageScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(680)), View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(32)) }
        pageScroll.addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(color(R.color.line)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val navigation = row().apply { setPadding(dp(12), dp(4), dp(12), dp(4)); setBackgroundColor(color(R.color.surface)) }
        listOf(Triple(0, R.string.ui_tasks, R.drawable.ic_nav_tasks), Triple(1, R.string.ui_history, R.drawable.ic_nav_history),
            Triple(2, R.string.ui_settings, R.drawable.ic_nav_settings)).forEach { (index, label, icon) ->
            val item = button(getString(label), primary = false) { navigate(index) }.apply {
                tag = index; textSize = 11f; setPadding(dp(4), dp(8), dp(4), dp(4)); compoundDrawablePadding = dp(4)
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, getDrawable(icon)?.mutate(), null, null)
                minHeight = dp(64); gravity = Gravity.CENTER; backgroundTintList = null
            }
            navigationButtons += item
            navigation.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(navigation)
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { detailBack?.invoke() ?: finish() }
        TaskRuntime.listen(listener)
        WirelessActivation.listen(activationListener)
        if (android.os.Build.VERSION.SDK_INT >= 30) discovery = WirelessDiscovery(this) { pairing, port ->
            WirelessActivation.discovered(pairing, port)
            if (!pairing) runOnUiThread { refreshShell() }
        }.also { it.start() }
        VirtualScreenState.listen(listener)
        navigate(savedInstanceState?.getInt("tab") ?: 0)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val back = detailBack
        if (back != null) back() else super.onBackPressed()
    }

    override fun onResume() { super.onResume();
        val expectedLanguage = Locale.forLanguageTag(UiLanguage.effectiveCode(this)).language
        if (resources.configuration.locales[0].language != expectedLanguage) { recreate(); return }
        VirtualScreenState.foreground(true); refreshShell(); if (tab == 0 && ::status.isInitialized) updateTask() }
    override fun onStop() {
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        VirtualScreenState.foreground(false); super.onStop()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); refreshShell() }
    override fun onSaveInstanceState(out: Bundle) {
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        out.putBoolean("task_options", taskOptionsOpen); out.putString("draft", draft); out.putString("package", selectedPackage); out.putInt("tab", tab)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        TaskRuntime.unlisten(listener); WirelessActivation.unlisten(activationListener); discovery?.close(); VirtualScreenState.unlisten(listener); backendExecutor.shutdownNow(); super.onDestroy()
    }

    private fun navigate(requestedIndex: Int) {
        val index = if (requestedIndex == 3) 2 else requestedIndex
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        detailBack = null
        showBrandHeader()
        tab = index; body.removeAllViews()
        pageScroll.scrollTo(0, 0)
        navigationButtons.forEach { button ->
            val selected = button.tag == index
            button.isSelected = selected
            button.setTextColor(color(if (selected) R.color.accent else R.color.muted))
            button.compoundDrawableTintList = ColorStateList.valueOf(color(if (selected) R.color.accent else R.color.muted))
            button.background = if (selected) android.graphics.drawable.InsetDrawable(WukongUi.feedback(this, R.color.soft_success, 20, canDisable = false), dp(16), dp(6), dp(16), dp(6)) else WukongUi.feedback(this, R.color.surface, 12, canDisable = false)
            button.setPadding(dp(4), dp(8), dp(4), dp(4))
            button.background.state = button.drawableState
        }
        when (index) { 0 -> taskPage(); 1 -> historyPage(); 3 -> logsPage(); else -> settingsPage() }
    }

    private fun taskPage() {
        body.addView(text(getString(R.string.ui_what_shall_we_do), 26f, bold = true).apply { letterSpacing = -0.035f })
        status = text("", 12f, bold = true).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        body.addView(status.spaced(4, 12))
        val composer = column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = WukongUi.shape(this@MainActivity, R.color.surface, 16, R.color.line)
        }
        goalInput = field(getString(R.string.setup_goal_hint), draft).apply {
            id = R.id.task_goal; isSaveEnabled = false
            minLines = 4; maxLines = 7; gravity = Gravity.TOP; isSingleLine = false
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), WukongUi.shape(this@MainActivity, R.color.surface, 12, R.color.accent, 2))
                addState(intArrayOf(), WukongUi.shape(this@MainActivity, R.color.surface, 12))
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        goalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft = s.toString()
                if (!TaskRuntime.active && !isFinishing && !isDestroyed) TaskDraftStore(this@MainActivity).save(draft, selectedPackage)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        composer.addView(goalInput.spaced(0, 16))
        val starting = row().apply { visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE }
        appOptions = starting
        starting.addView(text(getString(R.string.ui_starting_app_optional), 14f), LinearLayout.LayoutParams(0, -2, 1f))
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        apps = listOf("" to getString(R.string.ui_home_screen_all_apps)) + resolved.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        appPicker = Spinner(this).apply {
            adapter = WukongUi.choiceAdapter(this@MainActivity, apps.map { it.second })
            minimumHeight = dp(48)
            val existing = apps.indexOfFirst { it.first == selectedPackage }
            if (existing >= 0) setSelection(existing)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedPackage = apps.getOrNull(position)?.first; if (!TaskRuntime.active && !isFinishing && !isDestroyed) TaskDraftStore(this@MainActivity).save(draft, selectedPackage) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        starting.addView(appPicker, LinearLayout.LayoutParams(dp(168), dp(48)))
        starting.setPadding(dp(16), dp(12), dp(16), dp(12))
        starting.background = WukongUi.shape(this, R.color.surface, 16)
        startButton = button(getString(R.string.ui_run_task)) { startTask() }.apply { id = R.id.task_run }
        composer.addView(startButton)
        stopButton = button(getString(R.string.ui_stop_task), primary = false) { TaskRuntime.stop() }
        composer.addView(stopButton.spaced(8, 0))
        body.addView(composer.spaced(8, 12))
        body.addView(text(getString(R.string.ui_brief_approval), 12f, muted = true).spaced(0, 12))
        if (draft.isBlank() && !TaskRuntime.active) {
            body.addView(text(getString(R.string.samples_heading), 12f, muted = true).spaced(12, 4))
            TaskTemplates.all.forEachIndexed { index, template ->
                val icons = listOf(R.drawable.ic_sample_file, R.drawable.ic_sample_search, R.drawable.ic_nav_tasks)
                body.addView(WukongUi.actionRow(this, getString(template.title), icons[index]) { chooseTemplate(template) })
            }
        }

        if (draft.isNotBlank() && !TaskRuntime.active) body.addView(button(getString(R.string.sample_change), primary = false) {
            draft = ""; TaskDraftStore(this).clear(); goalInput.setText(""); navigate(0)
        }.spaced(0, 12))
        body.addView(WukongUi.actionRow(this, getString(if (taskOptionsOpen) R.string.setup_hide_options else R.string.setup_show_options)) {
            taskOptionsOpen = !taskOptionsOpen
            appOptions?.visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE
            displayOptions?.visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.task_options)?.setText(if (taskOptionsOpen) R.string.setup_hide_options else R.string.setup_show_options)
        }.apply { id = R.id.task_options }.spaced(0, 12))
        val preview = VirtualScreenPanel(this).apply { visibility = if (VirtualScreenState.enabled(this@MainActivity)) View.VISIBLE else View.GONE }
        virtualPreview = preview
        val optionsCard = panel(0)
        starting.background = null
        starting.setPadding(dp(16), dp(4), dp(12), dp(4))
        optionsCard.addView(starting)
        divider(optionsCard)
        val displayControls = row().apply {
            setPadding(dp(16), 0, dp(16), 0)
        }
        displayControls.addView(text(getString(R.string.ui_use_virtual_display), 14f).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        virtualToggle = Switch(this).apply {
            contentDescription = getString(R.string.ui_use_virtual_display); isChecked = VirtualScreenState.enabled(this@MainActivity); isEnabled = !TaskRuntime.active
            setTextColor(color(R.color.ink)); textSize = 14f; minHeight = dp(48); switchPadding = 0; setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, enabled ->
                VirtualScreenState.enabled(this@MainActivity, enabled)
                preview.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) runCatching { VirtualScreenState.start(this@MainActivity) }.onFailure { errorDialog(it.message ?: getString(R.string.ui_could_not_start_the_virtual_display)) }
                else VirtualScreenState.stop(this@MainActivity)
            }
        }.also { displayControls.addView(it, LinearLayout.LayoutParams(-2, dp(48))) }
        optionsCard.addView(displayControls)
        displayOptions = optionsCard
        optionsCard.visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE
        body.addView(optionsCard.spaced(0, 12))
        body.addView(preview.spaced(0, 20))
        activityHeading = text(getString(R.string.ui_activity), 18f, bold = true)
        body.addView(activityHeading)
        detail = text("", 14f).apply { setTextIsSelectable(true) }
        body.addView(detail.spaced(12, 16))

        updateTask()
    }

    private fun updateTask() {
        val connected = shellReady
        val phase = TaskRuntime.event.phase
        status.text = if (TaskRuntime.stopRequested && TaskRuntime.active) getString(R.string.ui_stopping) else if (phase == Phase.IDLE) {
            shellStatus
        } else phaseName(phase.name)
        status.setTextColor(color(if (phase == Phase.FAILED) R.color.danger else if (!connected && phase == Phase.IDLE) R.color.muted else R.color.success))
        status.background = null
        startButton.isEnabled = !TaskRuntime.active && !WirelessActivation.busy
        val modelReady = runCatching { SettingsStore(this).load()?.also { it.validate() } }.getOrNull() != null
        startButton.setText(if (shellReady && modelReady && Settings.canDrawOverlays(this)) R.string.ui_run_task else R.string.setup_continue)
        virtualToggle?.isEnabled = !TaskRuntime.active
        val virtualEnabled = VirtualScreenState.enabled(this)
        if (virtualToggle?.isChecked != virtualEnabled) virtualToggle?.isChecked = virtualEnabled
        virtualPreview?.visibility = if (virtualEnabled) View.VISIBLE else View.GONE
        stopButton.visibility = if (TaskRuntime.active) View.VISIBLE else View.GONE
        stopButton.isEnabled = TaskRuntime.active && !TaskRuntime.stopRequested
        goalInput.isEnabled = !TaskRuntime.active
        appPicker.isEnabled = !TaskRuntime.active
        val events = TaskRuntime.events().takeLast(10)
        activityHeading.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        detail.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        detail.text = if (events.isEmpty()) getString(R.string.ui_steps_and_results_will_appear_here_when_a_task_starts) else events.joinToString("\n\n") {
            (if (it.step > 0) "${it.step} · " else "") + if (it.phase == Phase.COMPLETED) it.message else UiMessages.text(this, it.message)
        }
    }

    private fun chooseTemplate(template: TaskTemplates.Template) {
        val parameter = field(getString(template.hint), "").apply { id = R.id.sample_parameter; isSaveEnabled = false; inputType = InputType.TYPE_CLASS_TEXT }
        val inputContainer = column().apply {
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(parameter.apply { setSingleLine(false); minLines = 2; maxLines = 4; gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE })
        }
        val dialog = AlertDialog.Builder(this).setTitle(template.title).setView(inputContainer)
            .setPositiveButton(R.string.sample_use, null).setNegativeButton(R.string.ui_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = parameter.text.toString().trim()
                if (value.isBlank()) { parameter.error = getString(R.string.sample_required); return@setOnClickListener }
                draft = getString(template.prompt, value.take(500)); selectedPackage = null
                TaskDraftStore(this).save(draft, null)
                dialog.dismiss()
                // navigate normally retains the visible draft, so set it before rebuilding.
                goalInput.setText(draft)
                navigate(0)
            }
        }
        dialog.show()
    }
    private fun openSetup() {
        if (TaskRuntime.active) return
        draft = goalInput.text.toString()
        TaskDraftStore(this).save(draft, selectedPackage)
        startActivity(Intent(this, SetupActivity::class.java))
    }
    private fun startTask() {
        try {
            require(goalInput.text.toString().isNotBlank()) { getString(R.string.sample_required) }
            val settings = runCatching { SettingsStore(this).load()?.also { it.validate() } }.getOrNull()
            if (settings == null || !shellReady || !Settings.canDrawOverlays(this)) { openSetup(); return }
            val selected = apps.getOrNull(appPicker.selectedItemPosition)
            TaskRuntime.start(this, goalInput.text.toString(), selected?.first?.ifBlank { null }, settings)
            TaskDraftStore(this).clear()
            updateTask()
        } catch (e: Exception) { errorDialog(e.message ?: getString(R.string.ui_could_not_start_the_task)) }
    }

    private fun refreshShell() {
        if (isDestroyed || !shellCheckRunning.compareAndSet(false, true)) return
        backendExecutor.submit {
            val result = runCatching { ShellClient(this).status() }
            if (result.isFailure) ConnectionRecovery.restore(this)
            runOnUiThread {
                shellCheckRunning.set(false)
                if (!isDestroyed) {
                    shellReady = result.isSuccess
                    if (shellReady && VirtualScreenState.enabled(this) && VirtualScreenState.service == null) VirtualScreenState.start(this)
                    shellStatus = if (shellReady) getString(R.string.ui_engine_connected) else getString(R.string.setup_not_ready)
                    if (tab == 0 && ::status.isInitialized) updateTask()
                }
            }
        }
    }
    private fun allowOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    }

    private fun showBrandHeader() {
        appHeader.removeAllViews()
        appHeader.setPadding(dp(24), dp(12), dp(24), dp(12))
        appHeader.addView(ImageView(this).apply { setImageResource(R.drawable.ic_wukong); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        appHeader.addView(text("Wukong", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun panel(padding: Int = 16) = column().apply {
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = WukongUi.shape(this@MainActivity, R.color.surface, 18)
        clipToOutline = true
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(color(R.color.line)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    private fun section(title: Int, content: LinearLayout) {
        body.addView(text(getString(title), 12f, muted = true).spaced(20, 8))
        body.addView(content.spaced(0, 4))
    }
    private fun settingRow(title: String, subtitle: String? = null, icon: Int = R.drawable.ic_nav_settings, action: () -> Unit): View {
        return row().apply {
            minimumHeight = dp(64); isFocusable = true; contentDescription = title
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = WukongUi.feedback(this@MainActivity, android.R.color.transparent, canDisable = false)
            addView(ImageView(this@MainActivity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.accent)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            addView(column().apply {
                addView(text(title, 14f, bold = true))
                if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, muted = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_chevron); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
            setOnClickListener { action() }
        }
    }
    private fun toolbar(title: String, back: (() -> Unit)? = null, menu: ((View) -> Unit)? = null) {
        val bar = row()
        if (back != null) bar.addView(button("", false) { back() }.apply {
            contentDescription = getString(R.string.setup_back)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_back, 0, 0, 0)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(text(title, if (back == null) 26f else 19f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (menu != null) bar.addView(button("", false) {}.apply {
            id = R.id.page_menu; contentDescription = getString(R.string.ui_more_actions)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_more, 0, 0, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { menu(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        appHeader.removeAllViews()
        appHeader.setPadding(dp(if (back == null) 24 else 12), dp(8), dp(16), dp(8))
        appHeader.addView(bar, LinearLayout.LayoutParams(-1, -2))
    }
    private fun clearLogs(name: String? = null, after: () -> Unit) {
        if (TaskRuntime.active && name == null) { errorDialog(getString(R.string.setup_stop_first)); return }
        AlertDialog.Builder(this).setTitle(if (name == null) R.string.ui_clear_run_logs else R.string.ui_delete_this_log)
            .setMessage(R.string.ui_this_deletes_the_run_logs_saved_on_this_device)
            .setPositiveButton(R.string.ui_clear) { _, _ -> if (name == null) RunLogStore(this).clear() else RunLogStore(this).delete(name); after() }
            .setNegativeButton(R.string.ui_cancel, null).show()
    }
    private fun settingsPage() {
        toolbar(getString(R.string.ui_make_it_yours))
        val store = SettingsStore(this)
        val saved = runCatching { store.load() }.getOrNull()
        val modelGroup = panel(0)
        modelGroup.addView(settingRow(getString(R.string.ui_model_service), saved?.let { getString(R.string.setup_model_summary,
            getString(ModelPresets.all.firstOrNull { preset -> preset.id == ModelPresets.provider(it) }?.title ?: R.string.setup_custom), it.model) }) {
            if (TaskRuntime.active) { errorDialog(getString(R.string.setup_stop_first)); return@settingRow }
            startActivity(Intent(this, SetupActivity::class.java).putExtra("model_only", true))
        })
        section(R.string.ui_group_model, modelGroup)
        val device = panel(0)
        device.addView(settingRow(getString(R.string.ui_activate_on_device), getString(if (shellReady) R.string.ui_engine_connected else R.string.setup_not_ready), R.drawable.ic_phone) { startActivity(Intent(this, WirelessSetupActivity::class.java)) })
        divider(device)
        device.addView(settingRow(getString(R.string.ui_allow_overlay_controls), getString(if (Settings.canDrawOverlays(this)) R.string.ui_allowed else R.string.ui_not_allowed), R.drawable.ic_overlay) { allowOverlay() })
        section(R.string.device_controls, device)
        val preferences = panel(0)
        val languageRow = row().apply { setPadding(dp(16), dp(4), dp(12), dp(4)) }
        languageRow.addView(text(getString(R.string.language_title), 14f), LinearLayout.LayoutParams(0, -2, 1f))
        languageRow.addView(Spinner(this).apply {
            val choices = listOf("system", "en", "zh-CN")
            adapter = WukongUi.choiceAdapter(this@MainActivity, listOf(getString(R.string.language_system), "English", "简体中文"))
            minimumHeight = dp(52); contentDescription = getString(R.string.language_title)
            setSelection(choices.indexOf(UiLanguage.code(this@MainActivity)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val language = choices[position]
                    if (UiLanguage.code(this@MainActivity) == language) return
                    if (TaskRuntime.active || WirelessActivation.busy || VirtualScreenState.service != null) {
                        errorDialog(getString(R.string.language_busy)); setSelection(choices.indexOf(UiLanguage.code(this@MainActivity))); return
                    }
                    UiLanguage.set(this@MainActivity, language); recreate()
                }
            }
        }, LinearLayout.LayoutParams(dp(164), dp(52)))
        preferences.addView(languageRow); divider(preferences)
        val logs = RunLogStore(this)
        val logging = row().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        logging.addView(text(getString(R.string.ui_keep_run_logs), 14f).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(56), 1f))
        logging.addView(Switch(this).apply {
            contentDescription = getString(R.string.ui_keep_run_logs); isChecked = logs.enabled; minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled -> logs.enabled = enabled }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        preferences.addView(logging); section(R.string.ui_group_preferences, preferences)
        val data = panel(0)
        data.addView(settingRow(getString(R.string.ui_data_permissions), icon = R.drawable.ic_shield) {
            AlertDialog.Builder(this).setTitle(R.string.ui_data_permissions)
                .setMessage(getString(R.string.ui_wireless_pairing_and_the_execution_engine_are_built_in_activate_o) + "\n\n" + getString(R.string.ui_your_goal_app_list_and_relevant_ui_text_are_sent_to_your_model_pr))
                .setPositiveButton(R.string.ui_got_it, null).show()
        })
        divider(data)
        data.addView(settingRow(getString(R.string.ui_connection_diagnostics), icon = R.drawable.ic_nav_logs) { logsPage() })
        section(R.string.ui_group_data, data)
        body.addView(button(getString(R.string.ui_clear_model_configuration), false) {
            if (TaskRuntime.active) { errorDialog(getString(R.string.setup_stop_first)); return@button }
            AlertDialog.Builder(this).setTitle(R.string.ui_clear_model_configuration).setMessage(R.string.ui_clear_model_confirm)
                .setPositiveButton(R.string.ui_clear) { _, _ -> store.clear(); navigate(2) }.setNegativeButton(R.string.ui_cancel, null).show()
        }.apply { setTextColor(color(R.color.danger)) }.spaced(24, 0))
    }
    private fun statusBadge(phase: String) = text(if (phase == "COMPLETED") getString(R.string.ui_completed) else phaseName(phase), 11f, bold = true).apply {
        val failed = phase == "FAILED"
        val completed = phase == "COMPLETED"
        setTextColor(color(if (failed) R.color.danger else if (completed) R.color.success else R.color.ink))
        background = WukongUi.shape(this@MainActivity, if (completed) R.color.soft_success else R.color.soft_accent, 8)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun historyPage() {
        toolbar(getString(R.string.ui_a_little_less_to_do), menu = { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(R.string.ui_clear_logs).setOnMenuItemClickListener { clearLogs { navigate(1) }; true }
                menu.add(R.string.ui_clear_history).setOnMenuItemClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(R.string.ui_clear_task_history).setMessage(R.string.ui_this_cannot_be_undone)
                        .setPositiveButton(R.string.ui_clear) { _, _ -> HistoryStore(this@MainActivity).clear(); navigate(1) }.setNegativeButton(R.string.ui_cancel, null).show(); true
                }
            }.show()
        })
        val records = HistoryStore(this).read()
        if (records.isEmpty()) { body.addView(text(getString(R.string.ui_a_fresh_start_nrun_your_first_task_and_find_its_results_here), 14f, muted = true).spaced(24, 24)); return }
        val format = SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0])
        records.forEach { record ->
            val card = panel().apply {
                tag = "history-entry"; isFocusable = true
                background = WukongUi.feedback(this@MainActivity, R.color.surface, 18, canDisable = false)
                val meta = row()
                meta.addView(statusBadge(record.phase), LinearLayout.LayoutParams(-2, -2))
                meta.addView(text(format.format(Date(record.timestamp)), 12f, muted = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(meta)
                addView(text(record.goal, 16f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }.spaced(12, 8))
                addView(text(record.result, 13f, muted = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(getString(R.string.ui_view_task), 12f).apply { setTextColor(color(R.color.accent)) }.spaced(14, 0))
                setOnClickListener { taskDetail(record) }
            }
            body.addView(card.spaced(0, 12))
        }
    }
    private fun taskDetail(record: TaskRecord) {
        body.removeAllViews(); pageScroll.scrollTo(0, 0); detailBack = { navigate(1) }
        val logName = RunLogStore(this).forTask(record)
        toolbar(getString(R.string.ui_task_detail), back = { navigate(1) }, menu = if (logName == null) null else { anchor ->
            PopupMenu(this, anchor).apply { menu.add(R.string.ui_delete_this_log).setOnMenuItemClickListener { clearLogs(logName) { taskDetail(record) }; true } }.show()
        })
        val summary = panel()
        summary.addView(statusBadge(record.phase), LinearLayout.LayoutParams(-2, -2))
        summary.addView(text(record.goal, 17f, bold = true).apply { setTextIsSelectable(true) }.spaced(12, 8))
        summary.addView(text(SimpleDateFormat("yyyy-MM-dd HH:mm", resources.configuration.locales[0]).format(Date(record.timestamp)), 12f, muted = true))
        body.addView(summary.spaced(0, 16))
        val result = panel()
        result.addView(text(getString(R.string.ui_task_result), 13f, bold = true).spaced(0, 8))
        result.addView(text(record.result, 14f).apply { setTextIsSelectable(true) })
        body.addView(result.spaced(0, 20))
        if (logName == null) body.addView(text(getString(R.string.ui_log_unavailable), 13f, muted = true)) else renderRunLog(logName)
    }
    /** Non-task connection tests remain discoverable in Settings; task traces live in history. */
    private fun logsPage() {
        body.removeAllViews(); pageScroll.scrollTo(0, 0); detailBack = { navigate(2) }
        toolbar(getString(R.string.ui_connection_diagnostics), back = { navigate(2) }, menu = { anchor ->
            PopupMenu(this, anchor).apply { menu.add(R.string.ui_clear_logs).setOnMenuItemClickListener { clearLogs { logsPage() }; true } }.show()
        })
        val store = RunLogStore(this)
        val files = store.files().filter { store.read(it.name).firstOrNull()?.optJSONObject("data")?.optString("kind") != "task" }
        if (files.isEmpty()) body.addView(text(getString(R.string.ui_no_diagnostics), 14f, muted = true))
        files.forEach { file ->
            body.addView(panel(0).apply {
                addView(settingRow(SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0]).format(Date(file.lastModified())), getString(R.string.ui_model_tool_call_test), R.drawable.ic_nav_logs) { logDetail(file.name) })
            }.spaced(0, 12))
        }
    }
    private fun logDetail(name: String) {
        body.removeAllViews(); pageScroll.scrollTo(0, 0); detailBack = { logsPage() }
        toolbar(getString(R.string.ui_connection_diagnostics), back = { logsPage() }, menu = { anchor ->
            PopupMenu(this, anchor).apply { menu.add(R.string.ui_delete_this_log).setOnMenuItemClickListener { clearLogs(name) { logsPage() }; true } }.show()
        })
        renderRunLog(name)
    }
    private fun renderRunLog(name: String) {
        val records = RunLogStore(this).read(name)
        val usages = records.filter { it.optString("type") == "model_response" }.mapNotNull { it.optJSONObject("data")?.optJSONObject("usage") }
        fun sum(key: String) = if (usages.any { it.has(key) && !it.isNull(key) }) usages.sumOf { it.optLong(key, 0) }.toString() else "—"
        val usage = panel()
        usage.addView(text(getString(R.string.ui_token_usage), 13f, bold = true).spaced(0, 10))
        val counters = row()
        listOf(R.string.ui_tokens_input to "prompt_tokens", R.string.ui_tokens_output to "completion_tokens", R.string.ui_tokens_total to "total_tokens").forEach { (label, key) ->
            counters.addView(column().apply { addView(text(sum(key), 21f, bold = true).apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(14, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }); addView(text(getString(label), 11f, muted = true)) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        usage.addView(counters)
        usage.addView(text(getString(R.string.ui_usage_note), 11f, muted = true).spaced(10, 0))
        body.addView(usage.spaced(0, 20))
        body.addView(text(getString(R.string.ui_execution_steps), 18f, bold = true).spaced(0, 12))
        val format = SimpleDateFormat("HH:mm:ss", resources.configuration.locales[0])
        records.groupBy { (it.optJSONObject("data")?.optInt("step", 0) ?: 0).coerceAtLeast(0) }.forEach { (step, events) ->
            val group = panel()
            group.addView(text(if (step == 0) getString(R.string.ui_preparation) else getString(R.string.step_label, step).substringBefore("·").trim(), 12f, bold = true).apply { setTextColor(color(R.color.accent)) }.spaced(0, 8))
            events.forEachIndexed { index, record ->
                val data = record.optJSONObject("data") ?: JSONObject()
                val type = record.optString("type")
                val label = when (type) {
                    "start" -> R.string.ui_started; "observation", "device_observation" -> R.string.ui_observe_screen
                    "observation_error" -> R.string.ui_observation_failed; "model_request" -> R.string.ui_model_request
                    "model_response" -> R.string.ui_model_response_tokens; "model_error" -> R.string.ui_model_error
                    "tool_call" -> R.string.ui_tool_call; "device_action" -> R.string.ui_device_action; "device_result" -> R.string.ui_result
                    "device_error" -> R.string.ui_device_error; "approval_requested" -> R.string.ui_approval_requested
                    "approval_result" -> R.string.ui_approval_result; "end" -> R.string.ui_finished; else -> R.string.ui_task_status
                }
                val payload = text(data.toString(2), 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); visibility = View.GONE; tag = "event-payload" }
                val chevron = ImageView(this).apply { setImageResource(R.drawable.ic_expand); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                val eventRow = row().apply {
                    tag = "run-event"; minimumHeight = dp(56); isFocusable = true
                    background = WukongUi.feedback(this@MainActivity, android.R.color.transparent, canDisable = false)
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(if (type == "tool_call" || type == "device_action") R.drawable.ic_nav_tasks else R.drawable.ic_nav_logs)
                        imageTintList = ColorStateList.valueOf(color(if (type.endsWith("error")) R.color.danger else R.color.accent))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
                    addView(column().apply {
                        addView(text(getString(label), 14f, bold = true))
                        val tool = data.optString("name").ifBlank { data.optJSONObject("action")?.optString("kind").orEmpty() }
                        val timing = if (data.has("durationMs")) " · ${data.optLong("durationMs")} ms" else ""
                        addView(text(format.format(Date(record.optLong("time"))) + (if (tool.isBlank()) "" else " · $tool") + timing, 11f, muted = true))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
                    setOnClickListener { payload.visibility = if (payload.visibility == View.VISIBLE) View.GONE else View.VISIBLE; chevron.rotation = if (payload.visibility == View.VISIBLE) 180f else 0f }
                }
                if (index > 0) divider(group)
                group.addView(eventRow); group.addView(payload.spaced(0, 8))
            }
            body.addView(group.spaced(0, 12))
        }
    }

    private fun phaseName(value: String) = when (value) {
        "PAUSED" -> getString(R.string.paused_label)
        "RUNNING" -> getString(R.string.ui_working); "AWAITING_APPROVAL" -> getString(R.string.ui_waiting_for_approval); "COMPLETED" -> getString(R.string.ui_result_received)
        "FAILED" -> getString(R.string.ui_task_failed); "CANCELLED" -> getString(R.string.ui_task_stopped); else -> getString(R.string.ui_ready_for_a_task)
    }
    private fun errorDialog(message: String) { AlertDialog.Builder(this).setTitle(getString(R.string.ui_one_more_thing)).setMessage(UiMessages.text(this, message)).setPositiveButton(getString(R.string.ui_got_it), null).show() }
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
            setColor(color(R.color.surface)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), color(stroke))
        }
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(R.color.accent))
            addState(intArrayOf(), shape(R.color.control))
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun button(label: String, primary: Boolean = true, click: () -> Unit) = WukongUi.button(this, label, primary, click)
    private fun <T : View> T.spaced(top: Int = 0, bottom: Int = 0): T = apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    }
    private fun color(id: Int) = getColor(id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
