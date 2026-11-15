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
import android.widget.*
import com.wukong.core.Phase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

abstract class WorkspaceActivity : LocalizedActivity() {
    protected abstract val destination: Int
    private lateinit var composerDock: FrameLayout
    private var executionSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var conversationView: ConversationView? = null
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var drawerView: com.google.android.material.navigation.NavigationView
    private var threadId = ""
    private lateinit var conversations: ConversationStore
    private lateinit var body: LinearLayout
    private lateinit var goalInput: EditText
    private lateinit var appHeader: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var pageScroll: ScrollView
    private var taskOptionsOpen = false
    private var discovery: WirelessDiscovery? = null
    private val shellCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activationListener: () -> Unit = { if (!WirelessActivation.busy) refreshShell() }
    private var detailBack: (() -> Unit)? = null
    private var tab = 0
    private var pendingExport: String? = null
    private var exporting = false
    private var draft = ""
    private var selectedPackage: String? = null
    private var apps = emptyList<Pair<String, String>>()
    private var shellReady = false
    private var shellStatus = ""
    private val backendExecutor = Executors.newSingleThreadExecutor()
    private val listener: () -> Unit = { if (tab == 0) conversationView?.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversations = ConversationStore(this)
        threadId = savedInstanceState?.getString("conversation") ?: TaskRuntime.conversationId ?: conversations.selected().orEmpty()
        if (conversations.get(threadId) == null) threadId = conversations.create()
        pendingExport = savedInstanceState?.getString("pending_export")
        shellStatus = getString(R.string.ui_checking_the_execution_engine)
        draft = savedInstanceState?.getString("draft") ?: TaskDraftStore(this).goal()
        taskOptionsOpen = savedInstanceState?.getBoolean("task_options", false) ?: false
        selectedPackage = savedInstanceState?.getString("package") ?: TaskDraftStore(this).packageName()
        root = column().apply { setBackgroundColor(color(R.color.paper)); isFocusableInTouchMode=true }
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
        composerDock = object:FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(View.MeasureSpec.getSize(widthMeasureSpec),dp(680)),View.MeasureSpec.EXACTLY),heightMeasureSpec)
            }
        }
        root.addView(composerDock, LinearLayout.LayoutParams(-1,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
        PageInsets.bind(root, pageScroll, composerDock, liftComposer = true)
        if(destination==0) {
            drawerLayout=androidx.drawerlayout.widget.DrawerLayout(this).apply { id=R.id.chat_drawer_layout }
            drawerLayout.addView(root,androidx.drawerlayout.widget.DrawerLayout.LayoutParams(-1,-1))
            drawerView=com.google.android.material.navigation.NavigationView(android.view.ContextThemeWrapper(this,R.style.Theme_Wukong_Components)).apply {
                id=R.id.chat_drawer; fitsSystemWindows=true; setTopInsetScrimEnabled(false); setBottomInsetScrimEnabled(false); setBackgroundColor(color(R.color.paper)); itemIconTintList=ColorStateList.valueOf(color(R.color.accent)); itemTextColor=ColorStateList.valueOf(color(R.color.ink))
                itemBackground=StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked),WukongUi.shape(this@WorkspaceActivity,R.color.soft_success,12))
                    addState(intArrayOf(),WukongUi.feedback(this@WorkspaceActivity,R.color.paper,12,canDisable=false))
                }
            }
            drawerLayout.addView(drawerView,androidx.drawerlayout.widget.DrawerLayout.LayoutParams(minOf(dp(320),(resources.displayMetrics.widthPixels*0.88f).toInt()),-1,Gravity.START))
            setContentView(drawerLayout)
        } else setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { goBack() }
        TaskRuntime.listen(listener)
        WirelessActivation.listen(activationListener)
        if (android.os.Build.VERSION.SDK_INT >= 30) discovery = WirelessDiscovery(this) { pairing, port ->
            WirelessActivation.discovered(pairing, port)
            if (!pairing) runOnUiThread { refreshShell() }
        }.also { it.start() }
        VirtualScreenState.listen(listener)
        when(destination) {
            4 -> renderTaskDetail(taskRecordFromIntent())
            5 -> logsPage()
            6 -> renderLogDetail(intent.getStringExtra("log") ?: error("Missing run"))
            else -> navigate(destination)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goBack()
    }

    override fun onResume() { super.onResume();
        val expectedLanguage = Locale.forLanguageTag(UiLanguage.effectiveCode(this)).language
        if (resources.configuration.locales[0].language != expectedLanguage) { recreate(); return }
        refreshShell(); if (tab == 0) updateTask()
        else if (destination == 2 && detailBack == null) { body.removeAllViews(); settingsPage() } }
    override fun onStop() {
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); refreshShell() }
    override fun onSaveInstanceState(out: Bundle) {
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        out.putString("conversation",threadId)
        out.putBoolean("task_options", taskOptionsOpen); out.putString("draft", draft); out.putString("package", selectedPackage); out.putInt("tab", tab)
        out.putString("pending_export", pendingExport)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        executionSheet?.dismiss(); TaskRuntime.unlisten(listener); WirelessActivation.unlisten(activationListener); discovery?.close(); VirtualScreenState.unlisten(listener); backendExecutor.shutdownNow(); super.onDestroy()
    }

    private fun navigate(requestedIndex: Int) {
        val index = if (requestedIndex == 3) 2 else requestedIndex
        if(index!=destination) {
            if(index==0) { startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); if(destination!=0)finish() }
            else startActivity(Intent(this,if(index==1)HistoryActivity::class.java else SettingsActivity::class.java))
            return
        }
        if (tab == 0 && ::goalInput.isInitialized) draft = goalInput.text.toString()
        detailBack = null
        showBrandHeader()
        tab = index; body.removeAllViews()
        pageScroll.scrollTo(0, 0)
        conversationView = null
        composerDock.removeAllViews()
        composerDock.visibility = if(index == 0) View.VISIBLE else View.GONE
        when (index) { 0 -> taskPage(); 1 -> historyPage(); 3 -> logsPage(); else -> settingsPage() }
    }

    private fun taskPage() {
        val nearBottom = {
            val content = pageScroll.getChildAt(0)
            content == null || content.height - pageScroll.scrollY - pageScroll.height < dp(180)
        }
        val view = ConversationView(this,threadId,::startTask,::showExecutionOptions,{ turn ->
            val record = TaskRecord(turn.goal,"整机任务",turn.phase,turn.result,turn.startedAt,turn.logName,true)
            taskDetail(record)
        },::chooseTemplate,onContent = {
            if(nearBottom()) pageScroll.post { pageScroll.fullScroll(View.FOCUS_DOWN) }
        },onActive = {
            TaskRuntime.conversationId?.let { selectConversation(it) }
        })
        conversationView=view; goalInput=view.input
        goalInput.setText(draft)
        goalInput.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(s:Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                draft=s.toString(); if(!TaskRuntime.active) {
                    TaskDraftStore(this@WorkspaceActivity).save(draft,selectedPackage); conversations.saveDraft(threadId,draft)
                }
                view.refresh()
            }
        })
        body.addView(view,LinearLayout.LayoutParams(-1,-2))
        composerDock.addView(view.composer,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL))
        updateTask()
        pageScroll.post { if(conversations.get(threadId)?.turns?.isNotEmpty()==true)pageScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun updateTask() { conversationView?.refresh() }
    private fun selectConversation(id: String) {
        if(tab==0 && ::goalInput.isInitialized)conversations.saveDraft(threadId,goalInput.text.toString())
        threadId=id; conversations.select(id); draft=conversations.draft(id)
        if(::goalInput.isInitialized)goalInput.setText(draft)
        navigate(0)
    }
    private fun newConversation() {
        if(tab==0 && ::goalInput.isInitialized)conversations.saveDraft(threadId,goalInput.text.toString())
        threadId=conversations.create(); draft=""
        if(::goalInput.isInitialized)goalInput.setText("")
        TaskDraftStore(this).clear(); navigate(0)
    }
    private fun goBack() {
        if(::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) { drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START); return }
        val back=detailBack
        if(back!=null)back() else finish()
    }
    private fun showDrawer() {
        if(!::drawerLayout.isInitialized)return
        drawerView.menu.clear()
        while(drawerView.headerCount>0)drawerView.removeHeaderView(drawerView.getHeaderView(0))
        drawerView.addHeaderView(column().apply {
            setPadding(dp(24),dp(40),dp(24),dp(24))
            addView(text("Wukong",24f,true)); addView(text(getString(R.string.chat_personal_agent),13f,muted=true))
        })
        val recent=conversations.read().filter { it.turns.isNotEmpty() }
        drawerView.menu.add(0,R.id.chat_new,0,R.string.chat_new).setIcon(R.drawable.ic_compose)
        recent.forEachIndexed { n,thread ->
            drawerView.menu.add(1,1000+n,100+n,thread.title).apply {
                isCheckable=true; isChecked=thread.id==threadId
                if(TaskRuntime.active && thread.id==TaskRuntime.conversationId)setIcon(R.drawable.ic_nav_tasks)
            }
        }
        drawerView.menu.setGroupCheckable(1,true,true)
        drawerView.menu.add(2,R.id.chat_history,10000,R.string.ui_history).setIcon(R.drawable.ic_nav_history)
        drawerView.menu.add(2,R.id.chat_settings,10001,R.string.ui_settings).setIcon(R.drawable.ic_nav_settings)
        drawerView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            when(item.itemId) {
                R.id.chat_new -> newConversation()
                R.id.chat_history -> navigate(1)
                R.id.chat_settings -> navigate(2)
                else -> recent.getOrNull(item.itemId-1000)?.let { selectConversation(it.id) }
            }; true
        }
        drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
    }
    private fun iconButton(icon:Int,label:Int,click:()->Unit)=ImageButton(this).apply {
        contentDescription=getString(label); setImageResource(icon); imageTintList=ColorStateList.valueOf(color(R.color.ink))
        background=WukongUi.feedback(this@WorkspaceActivity,R.color.paper,16,canDisable=false); setPadding(dp(12),dp(12),dp(12),dp(12)); setOnClickListener { click() }
    }
    private fun showExecutionOptions() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0)
        root.requestFocus()
        executionSheet?.dismiss()
        executionSheet=ExecutionOptionsSheet.show(this,selectedPackage) { selected ->
            selectedPackage=selected; TaskDraftStore(this).save(draft,selectedPackage); updateTask()
        }
    }

    private fun chooseTemplate(template: TaskTemplates.Template) {
        val parameter = field(getString(template.hint), "").apply { id = R.id.sample_parameter; isSaveEnabled = false; inputType = InputType.TYPE_CLASS_TEXT }
        val inputContainer = column().apply {
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(parameter.apply { setSingleLine(false); minLines = 2; maxLines = 4; gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE })
        }
        val dialog = WukongUi.dialog(this).setTitle(template.title).setView(inputContainer)
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
            TaskRuntime.start(this, goalInput.text.toString(), selectedPackage?.ifBlank { null }, settings, threadId)
            TaskDraftStore(this).clear(); conversations.saveDraft(threadId,""); draft=""; goalInput.setText("")
            (getSystemService(android.view.inputmethod.InputMethodManager::class.java)).hideSoftInputFromWindow(goalInput.windowToken,0)
            goalInput.clearFocus(); updateTask()
            pageScroll.post { pageScroll.fullScroll(View.FOCUS_DOWN) }
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
                    if (tab == 0) updateTask()
                }
            }
        }
    }
    private fun allowOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    }

    private fun showBrandHeader() {
        appHeader.removeAllViews(); appHeader.setPadding(dp(12),dp(8),dp(12),dp(8))
        appHeader.addView(iconButton(R.drawable.ic_menu,R.string.chat_menu) { showDrawer() }.apply { id=R.id.chat_menu },LinearLayout.LayoutParams(dp(48),dp(48)))
        appHeader.addView(column().apply {
            addView(text("Wukong",19f,true)); addView(text(getString(R.string.chat_personal_agent),11f,muted=true))
        },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(8) })
        appHeader.addView(iconButton(R.drawable.ic_compose,R.string.chat_new) { newConversation() }.apply { id=R.id.chat_new },LinearLayout.LayoutParams(dp(48),dp(48)))
    }
    private fun panel(padding: Int = 16) = column().apply {
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = WukongUi.shape(this@WorkspaceActivity, R.color.surface, 18)
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
            background = WukongUi.feedback(this@WorkspaceActivity, android.R.color.transparent, canDisable = false)
            addView(ImageView(this@WorkspaceActivity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.accent)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            addView(column().apply {
                addView(text(title, 14f, bold = true))
                if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, muted = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@WorkspaceActivity).apply { setImageResource(R.drawable.ic_chevron); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
            setOnClickListener { action() }
        }
    }
    private fun toolbar(title: String, back: (() -> Unit)? = null, menu: ((View) -> Unit)? = null) {
        val bar = row()
        if (back == null) bar.addView(iconButton(if(destination==0)R.drawable.ic_menu else R.drawable.ic_back,if(destination==0)R.string.chat_menu else R.string.setup_back) { if(destination==0)showDrawer() else finish() }.apply { if(destination==0)id=R.id.chat_menu },LinearLayout.LayoutParams(dp(48),dp(48)))
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
        WukongUi.dialog(this).setTitle(if (name == null) R.string.ui_clear_run_logs else R.string.ui_delete_this_log)
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
            adapter = WukongUi.choiceAdapter(this@WorkspaceActivity, listOf(getString(R.string.language_system), "English", "简体中文"))
            minimumHeight = dp(52); contentDescription = getString(R.string.language_title)
            setSelection(choices.indexOf(UiLanguage.code(this@WorkspaceActivity)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val language = choices[position]
                    if (UiLanguage.code(this@WorkspaceActivity) == language) return
                    if (TaskRuntime.active || WirelessActivation.busy || VirtualScreenState.service != null) {
                        errorDialog(getString(R.string.language_busy)); setSelection(choices.indexOf(UiLanguage.code(this@WorkspaceActivity))); return
                    }
                    UiLanguage.set(this@WorkspaceActivity, language); recreate()
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
            WukongUi.dialog(this).setTitle(R.string.ui_data_permissions)
                .setMessage(getString(R.string.ui_wireless_pairing_and_the_execution_engine_are_built_in_activate_o) + "\n\n" + getString(R.string.ui_your_goal_app_list_and_relevant_ui_text_are_sent_to_your_model_pr))
                .setPositiveButton(R.string.ui_got_it, null).show()
        })
        divider(data)
        data.addView(settingRow(getString(R.string.ui_connection_diagnostics), icon = R.drawable.ic_nav_logs) { startActivity(Intent(this,DiagnosticsActivity::class.java)) })
        section(R.string.ui_group_data, data)

    }
    private fun statusBadge(phase: String) = text(if (phase == "COMPLETED") getString(R.string.ui_completed) else phaseName(phase), 11f, bold = true).apply {
        val failed = phase == "FAILED"
        val completed = phase == "COMPLETED"
        setTextColor(color(if (failed) R.color.danger else if (completed) R.color.success else R.color.ink))
        background = WukongUi.shape(this@WorkspaceActivity, if (completed) R.color.soft_success else R.color.soft_accent, 8)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun historyPage() {
        toolbar(getString(R.string.ui_a_little_less_to_do), menu = { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(R.string.ui_clear_logs).setOnMenuItemClickListener { clearLogs { navigate(1) }; true }
                menu.add(R.string.ui_clear_history).setOnMenuItemClickListener {
                    WukongUi.dialog(this@WorkspaceActivity).setTitle(R.string.ui_clear_task_history).setMessage(R.string.ui_this_cannot_be_undone)
                        .setPositiveButton(R.string.ui_clear) { _, _ -> HistoryStore(this@WorkspaceActivity).clear(); navigate(1) }.setNegativeButton(R.string.ui_cancel, null).show(); true
                }
            }.show()
        })
        val records = HistoryStore(this).read()
        if (records.isEmpty()) { body.addView(text(getString(R.string.ui_a_fresh_start_nrun_your_first_task_and_find_its_results_here), 14f, muted = true).spaced(24, 24)); return }
        val format = SimpleDateFormat("MM-dd HH:mm", resources.configuration.locales[0])
        records.forEach { record ->
            val card = panel().apply {
                tag = "history-entry"; isFocusable = true
                background = WukongUi.feedback(this@WorkspaceActivity, R.color.surface, 18, canDisable = false)
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
        startActivity(Intent(this,TaskDetailActivity::class.java).putExtra("record",JSONObject().put("goal",record.goal).put("app",record.app).put("logAssociationKnown",record.logAssociationKnown).put("phase",record.phase)
            .put("result",record.result).put("timestamp",record.timestamp).put("logName",record.logName ?: JSONObject.NULL).toString()))
    }
    private fun taskRecordFromIntent(): TaskRecord {
        val value=JSONObject(intent.getStringExtra("record") ?: error("Missing task"))
        return TaskRecord(value.getString("goal"),value.optString("app","整机任务"),value.getString("phase"),value.getString("result"),value.getLong("timestamp"),if(value.isNull("logName"))null else value.getString("logName"),value.optBoolean("logAssociationKnown",false))
    }
    private fun renderTaskDetail(record: TaskRecord) {
        val back = { finish() }
        conversationView=null; composerDock.visibility=View.GONE
        body.removeAllViews(); pageScroll.scrollTo(0, 0); detailBack = back
        val logName = RunLogStore(this).forTask(record)
        toolbar(getString(R.string.ui_task_detail), back = back, menu = if (logName == null) null else { anchor ->
            runLogMenu(anchor, logName) { renderTaskDetail(record) }
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
        body.removeAllViews(); pageScroll.scrollTo(0, 0); detailBack = { finish() }
        toolbar(getString(R.string.ui_connection_diagnostics), back = { finish() }, menu = { anchor ->
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
    private fun logDetail(name: String) { startActivity(Intent(this,RunDetailActivity::class.java).putExtra("log",name)) }
    private fun renderLogDetail(name: String) {
        body.removeAllViews(); pageScroll.scrollTo(0, 0); detailBack = { finish() }
        toolbar(getString(R.string.ui_connection_diagnostics), back = { finish() }, menu = { anchor ->
            runLogMenu(anchor, name) { finish() }
        })
        renderRunLog(name)
    }
    private fun runLogMenu(anchor: View, name: String, afterDelete: () -> Unit) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.export_run).setOnMenuItemClickListener {
                WukongUi.dialog(this@WorkspaceActivity).setTitle(R.string.export_title).setMessage(R.string.export_description)
                    .setPositiveButton(R.string.export_save) { _, _ -> exportLog(name, false) }
                    .setNeutralButton(R.string.export_share) { _, _ -> exportLog(name, true) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
            menu.add(R.string.ui_delete_this_log).setOnMenuItemClickListener { clearLogs(name, afterDelete); true }
        }.show()
    }
    private fun exportLog(name: String, share: Boolean) {
        if (exporting) return
        exporting = true
        backendExecutor.execute {
            val exporter = RunLogExporter(this)
            val result = runCatching { exporter.create(name) }
            runOnUiThread {
                exporting = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold(onSuccess = { file ->
                    runCatching {
                        if (share) {
                            val uri = exporter.uri(file)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = android.content.ClipData.newRawUri("Run log", uri) }
                            startActivity(Intent.createChooser(send, getString(R.string.export_title)))
                        } else {
                            pendingExport = file.name
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, file.name), 710)
                        }
                    }.onFailure { pendingExport = null; exportFailure(it) }
                }, onFailure = { exportFailure(it) })
            }
        }
    }
    @Deprecated("Uses the system document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 710) return
        val file = RunLogExporter(this).cached(pendingExport)
        pendingExport = null
        if (resultCode != RESULT_OK) return
        val destination = data?.data
        if (file == null || destination == null) { exportFailure(IllegalStateException(getString(R.string.export_missing))); return }
        backendExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(destination, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error(getString(R.string.export_failed))
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) result.fold(
                    onSuccess = { Toast.makeText(this, R.string.export_saved, Toast.LENGTH_SHORT).show() },
                    onFailure = { exportFailure(it) })
            }
        }
    }
    private fun exportFailure(error: Throwable) {
        WukongUi.dialog(this).setTitle(R.string.export_failed)
            .setMessage(UiMessages.text(this, error.message.orEmpty())).setPositiveButton(android.R.string.ok, null).show()
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
                    "script_started", "script_call" -> R.string.ui_script_execution
                    "script_completed", "script_call_result" -> R.string.ui_result; "script_error" -> R.string.ui_device_error
                    "inspection_result" -> R.string.ui_inspection_result; "inspection_error" -> R.string.ui_observation_failed
                    "completion_verification", "completion_review_started", "completion_review" -> R.string.ui_completion_verification
                    "device_error" -> R.string.ui_device_error; "approval_requested" -> R.string.ui_approval_requested
                    "approval_result" -> R.string.ui_approval_result; "end" -> R.string.ui_finished; else -> R.string.ui_task_status
                }
                val payload = text(data.toString(2), 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); visibility = View.GONE; tag = "event-payload" }
                val chevron = ImageView(this).apply { setImageResource(R.drawable.ic_expand); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                val eventRow = row().apply {
                    tag = "run-event"; minimumHeight = dp(56); isFocusable = true
                    background = WukongUi.feedback(this@WorkspaceActivity, android.R.color.transparent, canDisable = false)
                    addView(ImageView(this@WorkspaceActivity).apply {
                        setImageResource(if (type == "tool_call" || type == "device_action") R.drawable.ic_nav_tasks else R.drawable.ic_nav_logs)
                        imageTintList = ColorStateList.valueOf(color(if (type.endsWith("error")) R.color.danger else R.color.accent))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
                    addView(column().apply {
                        addView(text(getString(label), 14f, bold = true))
                        val tool = data.optString("name").ifBlank { data.optString("method") }.ifBlank { data.optJSONObject("action")?.optString("kind").orEmpty() }
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
    private fun errorDialog(message: String) { WukongUi.dialog(this).setTitle(getString(R.string.ui_one_more_thing)).setMessage(UiMessages.text(this, message)).setPositiveButton(getString(R.string.ui_got_it), null).show() }
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
