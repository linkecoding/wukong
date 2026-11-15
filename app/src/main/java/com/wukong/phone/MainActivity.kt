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
    private lateinit var root: LinearLayout
    private lateinit var activationButton: Button
    private lateinit var activityHeading: TextView
    private lateinit var pageScroll: ScrollView
    private var taskOptionsOpen = false
    private var appOptions: View? = null
    private var displayOptions: View? = null
    private var discovery: WirelessDiscovery? = null
    private val shellCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activationListener: () -> Unit = { if (!WirelessActivation.busy) refreshShell() }
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
        val header = row().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_wukong); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        header.addView(column().apply {
            addView(text("Wukong", 22f, bold = true).apply { letterSpacing = -0.03f })
            addView(text(getString(R.string.brand_subtitle), 11f, muted = true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text(getString(R.string.preview_label), 11f, muted = true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6)); background = WukongUi.shape(this@MainActivity, R.color.paper, 20, R.color.line)
        })
        root.addView(header)
        pageScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(680)), View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)) }
        pageScroll.addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(color(R.color.line)) }, LinearLayout.LayoutParams(-1, dp(1)))
        val navigation = row().apply { setPadding(dp(12), dp(4), dp(12), dp(4)); setBackgroundColor(color(R.color.surface)) }
        listOf(Triple(0, R.string.ui_tasks, R.drawable.ic_nav_tasks), Triple(1, R.string.ui_history, R.drawable.ic_nav_history),
            Triple(3, R.string.ui_logs, R.drawable.ic_nav_logs), Triple(2, R.string.ui_settings, R.drawable.ic_nav_settings)).forEach { (index, label, icon) ->
            val item = button(getString(label), primary = false) { navigate(index) }.apply {
                tag = index; textSize = 11f; setPadding(dp(4), dp(8), dp(4), dp(4)); compoundDrawablePadding = dp(4)
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, getDrawable(icon), null, null)
                minHeight = dp(64); gravity = Gravity.CENTER; backgroundTintList = null
            }
            navigationButtons += item
            navigation.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(navigation)
        setContentView(root)
        TaskRuntime.listen(listener)
        WirelessActivation.listen(activationListener)
        if (android.os.Build.VERSION.SDK_INT >= 30) discovery = WirelessDiscovery(this) { pairing, port ->
            WirelessActivation.discovered(pairing, port)
            if (!pairing) runOnUiThread { refreshShell() }
        }.also { it.start() }
        VirtualScreenState.listen(listener)
        navigate(savedInstanceState?.getInt("tab") ?: 0)
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

    private fun navigate(index: Int) {
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        tab = index; body.removeAllViews()
        pageScroll.scrollTo(0, 0)
        navigationButtons.forEach { button ->
            val selected = button.tag == index
            button.isSelected = selected
            button.setTextColor(color(if (selected) R.color.accent else R.color.muted))
            button.compoundDrawableTintList = ColorStateList.valueOf(color(if (selected) R.color.accent else R.color.muted))
            button.background = WukongUi.feedback(this, if (selected) R.color.soft_accent else R.color.surface, 12, canDisable = false)
            button.background.state = button.drawableState
        }
        when (index) { 0 -> taskPage(); 1 -> historyPage(); 3 -> logsPage(); else -> settingsPage() }
    }

    private fun taskPage() {
        body.addView(text(getString(R.string.ui_what_shall_we_do), 32f, bold = true).apply { letterSpacing = -0.035f })
        body.addView(text(getString(R.string.ui_give_wukong_a_goal_let_your_phone_do_the_work), 15f, muted = true).spaced(8, 20))
        status = text("", 12f, bold = true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = WukongUi.shape(this@MainActivity, R.color.soft_success, 12)
        }
        body.addView(status.spaced(0, 8))
        activationButton = button(getString(R.string.setup_continue), primary = false) { openSetup() }
        body.addView(activationButton.spaced(0, 12))
        if (draft.isBlank() && !TaskRuntime.active) {
            body.addView(text(getString(R.string.samples_heading), 18f, bold = true).spaced(12, 8))
            body.addView(text(getString(R.string.samples_detail), 13f, muted = true).spaced(0, 12))
            TaskTemplates.all.forEach { template ->
                body.addView(button(getString(template.title), primary = false) { chooseTemplate(template) }.spaced(0, 8))
            }
        }
        val composer = column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = WukongUi.shape(this@MainActivity, R.color.surface, 16, R.color.line)
        }
        composer.addView(text(getString(R.string.ui_your_task), 14f, bold = true).spaced(0, 8))
        goalInput = field(getString(R.string.setup_goal_hint), draft).apply {
            id = R.id.task_goal; isSaveEnabled = false
            minLines = 3; maxLines = 6; gravity = Gravity.TOP; isSingleLine = false
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
        val starting = column().apply { visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE }
        appOptions = starting
        starting.addView(text(getString(R.string.ui_starting_app_optional), 12f, muted = true))
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        apps = listOf("" to getString(R.string.ui_home_screen_all_apps)) + resolved.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        appPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, apps.map { it.second })
            minimumHeight = dp(48)
            val existing = apps.indexOfFirst { it.first == selectedPackage }
            if (existing >= 0) setSelection(existing)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedPackage = apps.getOrNull(position)?.first; if (!TaskRuntime.active && !isFinishing && !isDestroyed) TaskDraftStore(this@MainActivity).save(draft, selectedPackage) }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        starting.addView(appPicker.spaced(4, 12)); composer.addView(starting)
        startButton = button(getString(R.string.ui_run_task)) { startTask() }.apply { id = R.id.task_run }
        composer.addView(startButton)
        stopButton = button(getString(R.string.ui_stop_task), primary = false) { TaskRuntime.stop() }
        composer.addView(stopButton.spaced(8, 0))
        body.addView(composer.spaced(8, 12))
        body.addView(text(getString(R.string.ui_routine_actions_run_automatically_sensitive_actions_need_your_app), 12f, muted = true).spaced(0, 24))
        if (draft.isNotBlank() && !TaskRuntime.active) body.addView(button(getString(R.string.sample_change), primary = false) {
            draft = ""; TaskDraftStore(this).clear(); goalInput.setText(""); navigate(0)
        }.spaced(0, 12))
        body.addView(button(getString(if (taskOptionsOpen) R.string.setup_hide_options else R.string.setup_show_options), primary = false) {
            taskOptionsOpen = !taskOptionsOpen
            appOptions?.visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE
            displayOptions?.visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.task_options)?.setText(if (taskOptionsOpen) R.string.setup_hide_options else R.string.setup_show_options)
        }.apply { id = R.id.task_options }.spaced(0, 12))
        val preview = VirtualScreenPanel(this).apply { visibility = if (VirtualScreenState.enabled(this@MainActivity)) View.VISIBLE else View.GONE }
        virtualPreview = preview
        val displayControls = column().apply {
            background = WukongUi.shape(this@MainActivity, R.color.surface, 16, R.color.line)
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        virtualToggle = Switch(this).apply {
            text = getString(R.string.ui_use_virtual_display); isChecked = VirtualScreenState.enabled(this@MainActivity); isEnabled = !TaskRuntime.active
            setTextColor(color(R.color.ink)); textSize = 14f; minHeight = dp(56); switchPadding = dp(8)
            setOnCheckedChangeListener { _, enabled ->
                VirtualScreenState.enabled(this@MainActivity, enabled)
                preview.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) runCatching { VirtualScreenState.start(this@MainActivity) }.onFailure { errorDialog(it.message ?: getString(R.string.ui_could_not_start_the_virtual_display)) }
                else VirtualScreenState.stop(this@MainActivity)
            }
        }.also { displayControls.addView(it) }
        displayControls.addView(text(getString(R.string.display_description), 12f, muted = true))
        displayOptions = displayControls
        displayControls.visibility = if (taskOptionsOpen) View.VISIBLE else View.GONE
        body.addView(displayControls.spaced(0, 12))
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
        status.background = WukongUi.shape(this, when {
            phase == Phase.FAILED || phase == Phase.PAUSED -> R.color.soft_accent
            !connected && phase == Phase.IDLE -> R.color.line
            else -> R.color.soft_success
        }, 12)
        startButton.isEnabled = !TaskRuntime.active && !WirelessActivation.busy
        val modelReady = runCatching { SettingsStore(this).load()?.also { it.validate() } }.getOrNull() != null
        startButton.setText(if (shellReady && modelReady && Settings.canDrawOverlays(this)) R.string.ui_run_task else R.string.setup_continue)
        virtualToggle?.isEnabled = !TaskRuntime.active
        val virtualEnabled = VirtualScreenState.enabled(this)
        if (virtualToggle?.isChecked != virtualEnabled) virtualToggle?.isChecked = virtualEnabled
        virtualPreview?.visibility = if (virtualEnabled) View.VISIBLE else View.GONE
        activationButton.visibility = View.GONE
        stopButton.visibility = if (TaskRuntime.active) View.VISIBLE else View.GONE
        stopButton.isEnabled = TaskRuntime.active && !TaskRuntime.stopRequested
        goalInput.isEnabled = !TaskRuntime.active
        appPicker.isEnabled = !TaskRuntime.active
        val events = TaskRuntime.events().takeLast(10)
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

    private fun settingsPage() {
        body.addView(text(getString(R.string.ui_make_it_yours), 28f, bold = true))
        body.addView(text(getString(R.string.ui_connect_a_model_choose_your_preferences_and_stay_in_control), muted = true).spaced(8, 24))
        val store = SettingsStore(this)
        val saved = runCatching { store.load() }.getOrNull()
        body.addView(text(if (saved == null) getString(R.string.setup_model_description) else getString(R.string.setup_model_summary,
            getString(ModelPresets.all.firstOrNull { it.id == ModelPresets.provider(saved) }?.title ?: R.string.setup_custom), saved.model), 14f, muted = true).spaced(0, 12))
        body.addView(button(getString(R.string.setup_open_model)) {
            if (TaskRuntime.active) { errorDialog(getString(R.string.setup_stop_first)); return@button }
            startActivity(Intent(this, SetupActivity::class.java).putExtra("model_only", true))
        }.spaced(0, 24))
        body.addView(text(getString(R.string.device_controls), 18f, bold = true).spaced(8, 12))
        body.addView(button(getString(R.string.ui_activate_on_device), primary = false) { startActivity(Intent(this, WirelessSetupActivity::class.java)) }.spaced(0, 8))
        body.addView(button(getString(R.string.ui_allow_overlay_controls), primary = false) { allowOverlay() }.spaced(0, 24))
        body.addView(text(getString(R.string.language_title), 18f, bold = true).spaced(8, 8))
        body.addView(text(getString(R.string.language_description), 13f, muted = true))
        body.addView(Spinner(this).apply {
            val choices = listOf("system", "en", "zh-CN")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.language_system), "English", "简体中文"))
            minimumHeight = dp(52)
            setSelection(choices.indexOf(UiLanguage.code(this@MainActivity)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val language = choices[position]
                    if (UiLanguage.code(this@MainActivity) == language) return
                    if (TaskRuntime.active || WirelessActivation.busy || VirtualScreenState.service != null) {
                        errorDialog(getString(R.string.language_busy))
                        setSelection(choices.indexOf(UiLanguage.code(this@MainActivity)))
                        return
                    }
                    UiLanguage.set(this@MainActivity, language)
                    recreate()
                }
            }
        }.spaced(8, 24))
        val logs = RunLogStore(this)
        body.addView(Switch(this).apply {
            text = getString(R.string.ui_keep_run_logs); isChecked = logs.enabled; minHeight = dp(48)
            setTextColor(getColor(R.color.ink))
            setOnCheckedChangeListener { _, enabled -> logs.enabled = enabled }
        })
        body.addView(text(getString(R.string.ui_save_tool_calls_results_timing_and_provider_reported_tokens_local), 13f, muted = true).spaced(8, 20))
        body.addView(text(getString(R.string.ui_data_permissions), 18f, bold = true))
        body.addView(text(getString(R.string.ui_wireless_pairing_and_the_execution_engine_are_built_in_activate_o), 14f, muted = true).spaced(12, 12))
        body.addView(text(getString(R.string.ui_your_goal_app_list_and_relevant_ui_text_are_sent_to_your_model_pr), 14f, muted = true).spaced(12, 20))
        body.addView(button(getString(R.string.ui_clear_model_configuration), primary = false) {
            if (TaskRuntime.active) { errorDialog(getString(R.string.ui_stop_the_current_task_first)); return@button }
            AlertDialog.Builder(this).setTitle(R.string.ui_clear_model_configuration).setMessage(R.string.ui_clear_model_confirm)
                .setPositiveButton(R.string.ui_clear) { _, _ -> store.clear(); navigate(2) }
                .setNegativeButton(R.string.ui_cancel, null).show()
        })
    }

    private fun historyPage() {
        body.addView(text(getString(R.string.ui_a_little_less_to_do), 28f, bold = true))
        body.addView(text(getString(R.string.ui_your_last_30_tasks_stored_only_on_this_phone), muted = true).spaced(8, 24))
        val records = HistoryStore(this).read()
        if (records.isEmpty()) body.addView(text(getString(R.string.ui_a_fresh_start_nrun_your_first_task_and_find_its_results_here), muted = true).spaced(16, 32))
        val format = SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0])
        records.forEach {
            body.addView(text("${UiMessages.text(this, it.app)} · ${format.format(Date(it.timestamp))}", 12f, muted = true))
            body.addView(text(it.goal, 17f, bold = true).spaced(8, 8))
            body.addView(text("${phaseName(it.phase)}\n${it.result}", 14f).apply { setTextIsSelectable(true) }.spaced(0, 20))
            body.addView(View(this).apply { setBackgroundColor(color(R.color.line)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(20) })
        }
        if (records.isNotEmpty()) body.addView(button(getString(R.string.ui_clear_history), primary = false) {
            AlertDialog.Builder(this).setTitle(getString(R.string.ui_clear_task_history)).setMessage(getString(R.string.ui_this_cannot_be_undone))
                .setPositiveButton(getString(R.string.ui_clear)) { _, _ -> HistoryStore(this).clear(); navigate(1) }.setNegativeButton(getString(R.string.ui_cancel), null).show()
        })
    }

    private fun logsPage() {
        val store = RunLogStore(this)
        body.addView(text(getString(R.string.ui_behind_every_action), 28f, bold = true))
        body.addView(text(if (store.enabled) getString(R.string.ui_logging_is_on_change_this_in_settings) else getString(R.string.ui_logging_is_off), 14f, muted = true).spaced(8, 16))
        body.addView(button(getString(R.string.ui_refresh_logs), primary = false) { navigate(3) })
        val files = store.files()
        if (files.isEmpty()) body.addView(text(getString(R.string.ui_no_logs_yet_enable_run_logs_in_settings_then_run_a_task_or_test_a), muted = true).spaced(16, 16))
        val format = SimpleDateFormat("MM-dd HH:mm:ss", resources.configuration.locales[0])
        for (file in files) {
            val first = store.read(file.name).firstOrNull()
            val goal = first?.optJSONObject("data")?.optString("goal").orEmpty()
            body.addView(button("${format.format(Date(first?.optLong("time") ?: file.lastModified()))}  ${goal.take(48)}", primary = false) { logDetail(file.name) }
                .apply { ellipsize = android.text.TextUtils.TruncateAt.END }.spaced(8, 8))
        }
        if (files.isNotEmpty()) body.addView(button(getString(R.string.ui_clear_logs), primary = false) {
            AlertDialog.Builder(this).setTitle(getString(R.string.ui_clear_run_logs)).setMessage(getString(R.string.ui_this_deletes_the_run_logs_saved_on_this_device))
                .setPositiveButton(getString(R.string.ui_clear)) { _, _ -> store.clear(); navigate(3) }.setNegativeButton(getString(R.string.ui_cancel), null).show()
        }.spaced(16, 8))
    }

    private fun logDetail(name: String) {
        body.removeAllViews()
        val records = RunLogStore(this).read(name)
        body.addView(button(getString(R.string.ui_back_to_logs), primary = false) { navigate(3) })
        body.addView(button(getString(R.string.ui_refresh_run), primary = false) { logDetail(name) }.spaced(8, 8))
        val usages = records.filter { it.optString("type") == "model_response" }.mapNotNull { it.optJSONObject("data")?.optJSONObject("usage") }
        fun sum(key: String) = if (usages.any { it.has(key) && !it.isNull(key) }) usages.sumOf { it.optLong(key, 0) }.toString() else getString(R.string.ui_not_reported)
        body.addView(text(getString(R.string.token_summary, sum("prompt_tokens"), sum("completion_tokens"), sum("total_tokens")), 15f, bold = true).spaced(16, 8))
        val responses = records.count { it.optString("type") == "model_response" || it.optString("type") == "model_error" }
        body.addView(text(getString(R.string.usage_summary, usages.size, responses - usages.size), 13f, muted = true).spaced(0, 16))
        val format = SimpleDateFormat("HH:mm:ss.SSS", resources.configuration.locales[0])
        for (record in records) {
            val data = record.optJSONObject("data") ?: JSONObject()
            val step = if (data.has("step")) getString(R.string.step_label, data.optInt("step")) else ""
            val label = when (record.optString("type")) {
                "start" -> getString(R.string.ui_started); "observation", "device_observation" -> getString(R.string.ui_observe_screen); "observation_error" -> getString(R.string.ui_observation_failed); "model_request" -> getString(R.string.ui_model_request); "model_response" -> getString(R.string.ui_model_response_tokens)
                "model_error" -> getString(R.string.ui_model_error); "tool_call" -> getString(R.string.ui_tool_call); "device_action" -> getString(R.string.ui_device_action); "device_result" -> getString(R.string.ui_result)
                "device_error" -> getString(R.string.ui_device_error); "approval_requested" -> getString(R.string.ui_approval_requested); "approval_result" -> getString(R.string.ui_approval_result); "end" -> getString(R.string.ui_finished); else -> getString(R.string.ui_task_status)
            }
            body.addView(text("${format.format(Date(record.optLong("time")))} · $step$label", 14f, bold = true).spaced(12, 4))
            body.addView(text(data.toString(2), 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }.spaced(0, 8))
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
