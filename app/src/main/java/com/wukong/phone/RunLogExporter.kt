package com.wukong.phone

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A bounded snapshot of one run. Never includes credentials, screenshots or other runs. */
class RunLogExporter(private val context: Context) {
    private val directory get() = File(context.cacheDir, "run-log-exports").apply { mkdirs() }
    fun cached(name: String?): File? = name?.takeIf { it.matches(Regex("wukong-run-[a-f0-9-]+\\.zip")) }
        ?.let { File(directory, it) }?.takeIf { it.isFile }
    fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.logexports", file)
    fun create(name: String, store: RunLogStore = RunLogStore(context)): File {
        // The private source is bounded to 512 KiB; export beyond the UI preview limit.
        val events = store.read(name, maxEvents = 20000)
        require(events.isNotEmpty()) { context.getString(R.string.export_missing) }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }?.forEach { it.delete() }
        val secret = runCatching { SettingsStore(context).load()?.apiKey }.getOrNull().orEmpty()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val manifest = JSONObject().put("schemaVersion", 1).put("appVersion", info.versionName)
            .put("versionCode", info.longVersionCode).put("androidSdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("exportedAt", System.currentTimeMillis()).put("eventCount", events.size)
        val file = File(directory, "wukong-run-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                entry("manifest.json", manifest.toString(2))
                entry("events.jsonl", events.joinToString("\n", postfix = "\n") { scrub(it, secret).toString() })
            }
        } catch (e: Exception) { file.delete(); throw e }
        return file
    }
    private fun scrub(value: Any?, secret: String): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy -> value.keys().forEach { key ->
            val normalized = key.lowercase().replace(Regex("[^a-z]"), "")
            copy.put(key, if (normalized in setOf("apikey", "authorization", "accesstoken", "refreshtoken", "password", "secret", "cookie")) "[redacted]" else scrub(value.get(key), secret))
        } }
        is JSONArray -> JSONArray().also { copy -> for (i in 0 until value.length()) copy.put(scrub(value.get(i), secret)) }
        is String -> if (secret.isEmpty()) value else value.replace(secret, "[redacted]")
        else -> value
    }
}
