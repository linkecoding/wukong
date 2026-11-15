package com.wukong.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ModelSettings(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val useFullEndpoint: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val providerId: String = "auto",
) {
    val effectiveThinking get() = thinkingEnabled || ModelPresets.thinkingRequired(this)

    fun requestEndpoint(): String = if (useFullEndpoint) endpoint else endpoint.trimEnd('/') + "/chat/completions"

    fun toJson(): JSONObject = JSONObject().put("endpoint", endpoint).put("model", model).put("key", apiKey)
        .put("useFullEndpoint", useFullEndpoint).put("thinkingEnabled", thinkingEnabled).put("providerId", providerId)

    companion object {
        fun fromJson(json: JSONObject): ModelSettings {
            val endpoint = json.getString("endpoint")
            val full = if (json.has("useFullEndpoint")) json.getBoolean("useFullEndpoint") else legacyUsesFullEndpoint(endpoint)
            return ModelSettings(endpoint, json.getString("model"), json.getString("key"), full, json.optBoolean("thinkingEnabled", false), json.optString("providerId", "auto"))
        }
        // Older releases stored a complete request URL. Recognize the common base
        // addresses without rewriting existing custom request routes.
        fun legacyUsesFullEndpoint(endpoint: String): Boolean {
            val path = URI(endpoint).path.orEmpty().trimEnd('/')
            return path.isNotEmpty() && path != "/v1"
        }
    }

    fun validate() {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "请输入 HTTPS 接口地址，不含账号、查询参数或片段"
        }
        require(model.isNotBlank() && model.length <= 200) { "请填写模型名称" }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && !apiKey.contains('\n') && !apiKey.contains('\r')) { "请填写有效的 API Key" }
    }
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("wukong.model", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("wukong.model", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun load(): ModelSettings? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        return ModelSettings.fromJson(json)
    }
    fun save(settings: ModelSettings) {
        settings.validate()
        val json = settings.toJson().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) { "保存失败" }
    }
    fun clear() { preferences.edit().clear().apply() }
}
