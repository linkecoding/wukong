package com.wukong.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*

/** Presets never carry a key to a different provider. Advanced endpoint paths remain unrestricted. */
class ModelConfigurationView(context: Context, saved: ModelSettings?) : LinearLayout(context) {
    private val ui = SetupViews(context)
    val providerPicker = Spinner(context).apply { id = R.id.provider_picker; minimumHeight = ui.dp(52) }
    val key = ui.field(context.getString(R.string.ui_stored_encrypted_on_this_device), saved?.apiKey.orEmpty(), true).apply { id = R.id.model_key }
    val endpoint = ui.field("https://…", saved?.endpoint.orEmpty()).apply { id = R.id.model_endpoint }
    val model = ui.field(context.getString(R.string.ui_enter_the_model_id_from_your_provider), saved?.model.orEmpty()).apply { id = R.id.model_name }
    val fullEndpoint = CheckBox(context).apply { text = context.getString(R.string.ui_use_exact_request_url); isChecked = saved?.useFullEndpoint ?: false; minHeight = ui.dp(48) }
    val thinking = Switch(context).apply { id = R.id.model_thinking; text = context.getString(R.string.ui_thinking_mode); minHeight = ui.dp(48); isChecked = saved?.thinkingEnabled ?: false }
    val message = ui.text("", 13f, true).apply { minHeight = ui.dp(32); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val providerNote = ui.text("", 13f, true)
    private val thinkingNote = ui.text("", 13f, true)
    private val capabilityNotice = ui.text(context.getString(R.string.setup_thinking_required), 13f, true)
    private val summary = ui.text("", 13f, true)
    private val advanced = ui.column().apply { id = R.id.model_advanced; visibility = View.GONE }
    private val platform = ui.button(R.string.setup_platform) { openPlatform() }
    private var chosen = saved?.let(ModelPresets::provider) ?: "deepseek"
    private var firstSelection = true

    init {
        orientation = VERTICAL
        ui.add(this, ui.text(context.getString(R.string.setup_provider), 14f))
        providerPicker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ModelPresets.all.map { context.getString(it.title) })
        providerPicker.setSelection(ModelPresets.all.indexOfFirst { it.id == chosen }.coerceAtLeast(0))
        ui.add(this, providerPicker)
        ui.add(this, summary)
        ui.add(this, capabilityNotice)
        ui.add(this, platform)
        ui.add(this, providerNote)
        ui.add(this, ui.text("API Key", 14f))
        ui.add(this, key)
        ui.add(this, ui.text(context.getString(R.string.setup_provider_terms), 12f, true))
        lateinit var disclosure: Button
        disclosure = ui.button(R.string.setup_advanced) {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            disclosure.setText(if (advanced.visibility == View.VISIBLE) R.string.setup_hide_advanced else R.string.setup_advanced)
        }
        ui.add(this, disclosure)
        ui.add(advanced, ui.text("Base URL", 14f)); ui.add(advanced, endpoint)
        ui.add(advanced, fullEndpoint)
        ui.add(advanced, ui.text(context.getString(R.string.ui_any_base_path_is_allowed_wukong_appends_chat_completions_unless_y), 12f, true))
        ui.add(advanced, ui.text(context.getString(R.string.ui_model_id), 14f)); ui.add(advanced, model)
        ui.add(advanced, thinking); ui.add(advanced, thinkingNote)
        addView(advanced)
        addView(message)
        if (saved == null) applyPreset(chosen, false) else updateCapability()
        providerPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = ModelPresets.all[position].id
                if (firstSelection && next == chosen) { firstSelection = false; return }
                firstSelection = false
                if (next != chosen) applyPreset(next, true)
            }
        }
        model.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateCapability() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun applyPreset(next: String, clearKey: Boolean) {
        chosen = next
        val preset = ModelPresets.all.first { it.id == next }
        if (clearKey) { key.setText(""); message.text = context.getString(R.string.setup_key_cleared) }
        endpoint.setText(preset.endpoint); model.setText(preset.model)
        fullEndpoint.isChecked = false; thinking.isChecked = false
        if (next == "custom") advanced.visibility = View.VISIBLE
        updateCapability()
    }
    private fun updateCapability() {
        val candidate = candidate(validate = false)
        val required = ModelPresets.thinkingRequired(candidate)
        if (required) thinking.isChecked = true
        thinking.isEnabled = !required
        capabilityNotice.visibility = if (required) View.VISIBLE else View.GONE
        thinkingNote.setText(if (required) R.string.setup_thinking_required else R.string.setup_thinking_hint)
        val preset = ModelPresets.all.firstOrNull { it.id == chosen } ?: ModelPresets.all.last()
        summary.text = model.text.toString()
        platform.visibility = if (preset.platform.isBlank()) View.GONE else View.VISIBLE
        providerNote.text = if (chosen == "qwen") context.getString(R.string.setup_region_note) else ""
        providerNote.visibility = if (chosen == "qwen") View.VISIBLE else View.GONE
    }
    fun candidate(validate: Boolean = true) = ModelSettings(endpoint.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim(), fullEndpoint.isChecked, thinking.isChecked, chosen)
        .also { if (validate) it.validate() }
    fun setBusy(busy: Boolean) {
        platform.isEnabled = !busy; providerPicker.isEnabled = !busy; key.isEnabled = !busy; endpoint.isEnabled = !busy; model.isEnabled = !busy; fullEndpoint.isEnabled = !busy
        thinking.isEnabled = !busy && !ModelPresets.thinkingRequired(candidate(false))
    }
    private fun openPlatform() {
        val link = ModelPresets.all.first { it.id == chosen }.platform
        if (link.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            .onFailure { message.text = it.localizedMessage }
    }
}
