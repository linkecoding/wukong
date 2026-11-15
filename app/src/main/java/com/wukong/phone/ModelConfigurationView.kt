package com.wukong.phone

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** Hallmark · model configuration · cream/moss · P4 H4 E4 S4 R5 V4.
 * Discovery never changes or saves the selected model; manual IDs remain available. */
class ModelConfigurationView @JvmOverloads constructor(context: Context, saved: ModelSettings? = null, private val catalogFactory: () -> ModelCatalog = { ModelCatalog() }) : LinearLayout(context) {
    var onChanged: (() -> Unit)? = null
    private val ui = SetupViews(context)
    val providerPicker = Spinner(context).apply { id = R.id.provider_picker; minimumHeight = ui.dp(52) }
    val key = ui.field(context.getString(R.string.model_key_hint), saved?.apiKey.orEmpty(), true).apply { id = R.id.model_key }
    val endpoint = ui.field("https://…", saved?.endpoint.orEmpty()).apply { id = R.id.model_endpoint }
    val model = AutoCompleteTextView(android.view.ContextThemeWrapper(context, R.style.Theme_Wukong_Dropdown)).apply {
        id = R.id.model_name; hint = context.getString(R.string.model_pick_hint); textSize = 15f
        setSingleLine(); minHeight = ui.dp(52); threshold = 1
        gravity = Gravity.END or Gravity.CENTER_VERTICAL; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.ink)); setHintTextColor(context.getColor(R.color.muted))
        setPadding(ui.dp(12), ui.dp(12), ui.dp(56), ui.dp(12))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setText(saved?.model.orEmpty(), false)
        setDropDownBackgroundDrawable(context.getDrawable(R.drawable.dropdown_background))
    }
    val fullEndpoint = CheckBox(context).apply { text = context.getString(R.string.ui_use_exact_request_url); isChecked = saved?.useFullEndpoint ?: false; minHeight = ui.dp(48) }
    val thinking = Switch(context).apply { id = R.id.model_thinking; text = context.getString(R.string.ui_thinking_mode); minHeight = ui.dp(48); isChecked = saved?.thinkingEnabled ?: false }
    val message = ui.text("", 13f, true).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val catalogStatus = ui.text("", 12f, true).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val providerNote = ui.text("", 12f, true)
    private val thinkingNote = ui.text("", 12f, true)
    private val capabilityNotice = ui.text(context.getString(R.string.setup_thinking_required), 12f, true)
    private val advanced = card().apply { id = R.id.model_advanced; visibility = View.GONE }
    private val platform = ui.button(R.string.model_get_key) { openPlatform() }.apply { textSize = 12f }
    private val openModels = WukongUi.button(context, "") { openCatalog(false) }.apply {
        id = R.id.model_catalog_open; contentDescription = context.getString(R.string.model_choose)
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_expand, 0, 0, 0)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent))
    }
    private val refreshModels = ui.button(R.string.model_refresh) { openCatalog(true) }.apply {
        id = R.id.model_catalog_refresh; textSize = 12f; visibility = View.GONE
    }
    private val loading = ProgressBar(context).apply { visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private lateinit var disclosure: Button
    private var chosen = saved?.let(ModelPresets::provider)?.takeIf { value -> ModelPresets.all.any { it.id == value } } ?: "deepseek"
    private var firstSelection = true
    private var busy = false
    private var generation = 0
    private var catalog: ModelCatalog? = null
    private var job: Future<*>? = null
    private var worker: ExecutorService? = null
    private var models = emptyList<String>()
    private var fetching = false

    init {
        orientation = VERTICAL
        val connection = card()
        val providerRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        providerRow.addView(ui.text(context.getString(R.string.setup_provider), 13f, true), LayoutParams(0, -2, 1f))
        providerPicker.adapter = WukongUi.choiceAdapter(context, ModelPresets.all.map { context.getString(it.title) })
        providerPicker.setSelection(ModelPresets.all.indexOfFirst { it.id == chosen }.coerceAtLeast(0))
        providerRow.addView(providerPicker, LayoutParams(ui.dp(176), ui.dp(52)))
        ui.add(connection, providerRow, bottom = 12)
        ui.add(connection, ui.text("Base URL", 12f, true), bottom = 6)
        ui.add(connection, endpoint, bottom = 8)
        val keyRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        keyRow.addView(ui.text("API Key", 12f, true), LayoutParams(0, -2, 1f))
        keyRow.addView(platform, LayoutParams(-2, ui.dp(48)))
        connection.addView(keyRow)
        ui.add(connection, key, bottom = 0)
        ui.add(connection, providerNote, top = 8, bottom = 0)
        ui.add(this, connection, bottom = 16)

        val modelCard = card()
        val modelHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        modelHeader.addView(ui.text(context.getString(R.string.ui_model_id), 16f).apply { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LayoutParams(0, ui.dp(48), 1f))
        modelHeader.addView(refreshModels, LayoutParams(-2, ui.dp(48)))
        modelCard.addView(modelHeader)
        val modelInput = FrameLayout(context)
        modelInput.addView(model, FrameLayout.LayoutParams(-1, -2))
        modelInput.addView(openModels, FrameLayout.LayoutParams(ui.dp(48), ui.dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
        modelInput.addView(loading, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(14) })
        ui.add(modelCard, modelInput, bottom = 0)
        ui.add(modelCard, catalogStatus, top = 8, bottom = 0)
        ui.add(modelCard, capabilityNotice, top = 8, bottom = 0)
        ui.add(this, modelCard, bottom = 8)
        disclosure = ui.button(R.string.setup_advanced) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            disclosure.setText(if (advanced.visibility == View.VISIBLE) R.string.setup_hide_advanced else R.string.setup_advanced)
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
        }
        ui.add(this, disclosure, bottom = 8)
        fullEndpoint.setTextColor(context.getColor(R.color.ink)); thinking.setTextColor(context.getColor(R.color.ink))
        ui.add(advanced, fullEndpoint, bottom = 4)
        ui.add(advanced, ui.text(context.getString(R.string.ui_any_base_path_is_allowed_wukong_appends_chat_completions_unless_y), 12f, true))
        ui.add(advanced, thinking, bottom = 4); ui.add(advanced, thinkingNote, bottom = 0)
        ui.add(this, advanced, bottom = 12)
        ui.add(this, message, top = 4, bottom = 0)
        for (input in listOf(endpoint, key, model)) input.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), WukongUi.shape(context, R.color.paper, 12, R.color.accent, 2))
            addState(intArrayOf(), WukongUi.shape(context, R.color.paper, 12, R.color.line))
        }
        if (saved == null) applyPreset(chosen, false) else updateCapability()
        providerPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = ModelPresets.all[position].id
                if (firstSelection && next == chosen) { firstSelection = false; return }
                firstSelection = false
                if (next != chosen) { applyPreset(next, true); onChanged?.invoke() }
            }
        }
        watch(model) { updateCapability(); onChanged?.invoke() }
        for (input in listOf(key, endpoint)) watch(input) { invalidateCatalog(); onChanged?.invoke() }
        watch(message) { message.visibility = if (message.text.isBlank()) View.GONE else View.VISIBLE }
        fullEndpoint.setOnCheckedChangeListener { _, _ -> invalidateCatalog(); onChanged?.invoke() }
        thinking.setOnCheckedChangeListener { _, _ -> onChanged?.invoke() }
    }
    private fun card() = ui.column().apply {
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16))
        background = WukongUi.shape(context, R.color.surface, 18)
    }
    private fun watch(view: TextView, change: () -> Unit) = view.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change() }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    private fun applyPreset(next: String, clearKey: Boolean) {
        invalidateCatalog(); chosen = next
        val preset = ModelPresets.all.first { it.id == next }
        if (clearKey) { key.setText(""); message.text = context.getString(R.string.setup_key_cleared) }
        endpoint.setText(preset.endpoint); model.setText(preset.model, false)
        fullEndpoint.isChecked = false; thinking.isChecked = false
        if (next == "custom") advanced.visibility = View.VISIBLE
        disclosure.setText(if (advanced.visibility == View.VISIBLE) R.string.setup_hide_advanced else R.string.setup_advanced)
        updateCapability()
    }
    private fun updateCapability() {
        val required = ModelPresets.thinkingRequired(candidate(false))
        if (required) thinking.isChecked = true
        thinking.isEnabled = !busy && !required
        capabilityNotice.visibility = if (required) View.VISIBLE else View.GONE
        thinkingNote.setText(if (required) R.string.setup_thinking_required else R.string.setup_thinking_hint)
        val preset = ModelPresets.all.first { it.id == chosen }
        platform.visibility = if (preset.platform.isBlank()) View.GONE else View.VISIBLE
        providerNote.text = if (chosen == "qwen") context.getString(R.string.model_region_note) else ""
        providerNote.visibility = if (chosen == "qwen") View.VISIBLE else View.GONE
    }
    fun candidate(validate: Boolean = true) = ModelSettings(endpoint.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim(), fullEndpoint.isChecked, thinking.isChecked, chosen)
        .also { if (validate) it.validate() }
    fun setBusy(value: Boolean) {
        busy = value
        if (busy) { cancelFetch(); model.dismissDropDown() }
        platform.isEnabled = !busy; providerPicker.isEnabled = !busy; key.isEnabled = !busy
        endpoint.isEnabled = !busy; model.isEnabled = !busy; fullEndpoint.isEnabled = !busy
        openModels.isEnabled = !busy; refreshModels.isEnabled = !busy
        thinking.isEnabled = !busy && !ModelPresets.thinkingRequired(candidate(false))
    }
    private fun openCatalog(refresh: Boolean) {
        if (busy || fetching) return
        if (models.isNotEmpty() && !refresh) { showModels(); return }
        val settings = candidate(false)
        if (settings.apiKey.isBlank()) { catalogMessage(context.getString(R.string.model_key_first), true); return }
        try { settings.copy(model = "discovery").validate() } catch (_: Exception) {
            catalogMessage(context.getString(R.string.model_address_check), true); return
        }
        if (settings.modelsEndpoint() == null) { catalogMessage(context.getString(R.string.model_unknown_route), true); return }
        cancelFetch()
        val current = generation
        val client = catalogFactory(); catalog = client
        fetching = true; loading.visibility = View.VISIBLE; openModels.visibility = View.INVISIBLE
        refreshModels.isEnabled = false
        catalogMessage(context.getString(R.string.model_loading))
        if (worker == null) worker = Executors.newSingleThreadExecutor()
        job = worker!!.submit {
            val result = runCatching { client.fetch(settings) }
            post {
                if (generation != current || !isAttachedToWindow) return@post
                fetching = false; loading.visibility = View.GONE; openModels.visibility = View.VISIBLE
                refreshModels.isEnabled = !busy; catalog = null
                result.fold(onSuccess = { values ->
                    models = values
                    if (values.isEmpty()) catalogMessage(context.getString(R.string.model_list_empty))
                    else {
                        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, values) {
                            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                                WukongUi.dropdownRow(context, super.getView(position, convertView, parent) as TextView).apply {
                                    if (this is CheckedTextView) isChecked = getItem(position) == model.text.toString()
                                }
                        }
                        model.setAdapter(adapter)
                        refreshModels.visibility = View.VISIBLE
                        catalogMessage(context.getString(R.string.model_list_ready, values.size))
                        showModels()
                    }
                }, onFailure = { error -> catalogMessage(catalogError(error), true) })
            }
        }
    }
    private fun showModels() {
        val adapter = model.adapter as? ArrayAdapter<*> ?: return
        val current = generation
        model.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(model.windowToken, 0)
        adapter.filter.filter(null) {
            if (current == generation && isAttachedToWindow && !busy) model.showDropDown()
        }
    }
    private fun catalogError(error: Throwable): String = context.getString(when ((error as? ModelCatalog.CatalogException)?.reason) {
        ModelCatalog.Failure.AUTH -> R.string.model_list_auth
        ModelCatalog.Failure.UNAVAILABLE -> R.string.model_list_unsupported
        ModelCatalog.Failure.RATE_LIMIT -> R.string.model_list_rate_limit
        ModelCatalog.Failure.UNKNOWN_ROUTE -> R.string.model_unknown_route
        ModelCatalog.Failure.INVALID_RESPONSE -> R.string.model_list_invalid
        else -> R.string.model_list_failed
    })
    private fun catalogMessage(value: String, error: Boolean = false) {
        catalogStatus.text = value; catalogStatus.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        catalogStatus.setTextColor(context.getColor(if (error) R.color.danger else R.color.muted))
    }
    private fun cancelFetch() {
        generation++; catalog?.cancel(); catalog = null; job?.cancel(true); job = null
        fetching = false; loading.visibility = View.GONE; openModels.visibility = View.VISIBLE
    }
    private fun invalidateCatalog() {
        cancelFetch(); models = emptyList(); model.dismissDropDown()
        model.setAdapter<ArrayAdapter<String>>(null)
        refreshModels.visibility = View.GONE; catalogMessage("")
    }
    override fun onDetachedFromWindow() {
        invalidateCatalog(); worker?.shutdownNow(); worker = null
        super.onDetachedFromWindow()
    }
    private fun openPlatform() {
        val link = ModelPresets.all.first { it.id == chosen }.platform
        if (link.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            .onFailure { message.text = context.getString(R.string.model_platform_failed) }
    }
}
