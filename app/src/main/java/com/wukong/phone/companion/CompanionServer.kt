package com.wukong.phone.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.wukong.phone.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.*

/** Tiny bounded HTTP transport, loopback + independent bearer. Never exposes shell RPC. */
internal class CompanionServer(private val context: Context, private val token: String, private val port: Int = PORT) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private var listener: ServerSocket? = null
    @Volatile private var closed = false
    fun start() {
        listener = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (!closed) {
                val socket = try { listener!!.accept() } catch (_: Exception) { break }
                try { workers.execute { socket.use { handle(it) } } } catch (_: RejectedExecutionException) { socket.close() }
            }
        }, "wukong-companion").start()
    }
    private fun <T> ui(block: () -> T): T {
        val task = FutureTask { check(!closed) { "Companion disconnected" }; block() }
        main.post(task)
        // Never time out a queued mutation and subsequently execute it unseen.
        return try { task.get() } catch (e: ExecutionException) { throw (e.cause as? Exception ?: e) }
    }
    private fun handle(socket: Socket) {
        var request = JSONObject()
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            var headerSize = 0
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    require(byte >= 0 && ++headerSize <= 16384) { "Invalid headers" }
                    if (byte == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                    bytes.write(byte)
                }
            }
            val first = line().split(' ')
            require(first.size == 3 && first[2] == "HTTP/1.1") { "Invalid request" }
            val (method, path) = first
            val headers = mutableMapOf<String, String>()
            while (true) {
                val value = line(); if (value.isEmpty()) break
                val parts = value.split(':', limit = 2)
                require(parts.size == 2 && !headers.containsKey(parts[0].lowercase())) { "Invalid headers" }
                headers[parts[0].lowercase()] = parts[1].trim()
            }
            if (headers.containsKey("origin") || !MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray())) {
                respond(socket, 401, json("Unauthorized")); return
            }
            require(!headers.containsKey("transfer-encoding")) { "Chunked requests are unsupported" }
            val size = headers["content-length"]?.toInt() ?: 0
            require(size in 0..65536) { "Request too large" }
            if (method == "POST") require(headers["content-type"]?.substringBefore(';') == "application/json" && size > 0) { "Expected JSON" }
            val data = ByteArray(size)
            var offset = 0
            while (offset < size) { val count = input.read(data, offset, size-offset); require(count > 0); offset += count }
            if (size > 0) request = JSONObject(String(data, Charsets.UTF_8))
            when {
                method == "GET" && path == "/v1/status" -> respond(socket, 200, status())
                method == "GET" && path == "/v1/model" -> respond(socket, 200, modelInfo())
                method == "GET" && path == "/v1/preview" -> {
                    val bitmap = VirtualScreenState.bitmap ?: throw NoSuchElementException("No virtual display frame")
                    val bytes = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bytes)
                    respond(socket, 200, bytes.toByteArray(), "image/jpeg")
                }
                method == "POST" && path in setOf("/v1/model", "/v1/models", "/v1/model/test") -> {
                    check(!TaskRuntime.active) { "Wait until the current task ends" }
                    val settings = modelSettings(request)
                    val result = when (path) {
                        "/v1/models" -> JSONObject().put("models", JSONArray(ModelCatalog().fetch(settings)))
                        "/v1/model/test" -> JSONObject().put("message", ChatModel(settings).test())
                        else -> { ui { check(!TaskRuntime.active); SettingsStore(context).save(settings) }; modelInfo() }
                    }
                    respond(socket, 200, result)
                }
                method == "POST" && path == "/v1/tasks" -> {
                    val duplicate = ui { RemoteTasks.existing(context, request) }
                    if (duplicate != null) { respond(socket, 200, duplicate); return }
                    RemoteTasks.validate(request)
                    ui { check(!TaskRuntime.active) { "Another task is running" }; (SettingsStore(context).load() ?: error("Configure a model first")).validate() }
                    ShellClient(context).status()
                    require(context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                        !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "Unlock and wake the phone before starting a task" }
                    val display = request.optString("display", "virtual")
                    require(display in setOf("virtual", "physical")) { "display must be virtual or physical" }
                    // Start virtual screen on the main thread; readiness is awaited off-main.
                    val target = if (display == "virtual") {
                        ui {
                            check(!TaskRuntime.active) { "Another task is running" }
                            require(Settings.canDrawOverlays(context)) { "Allow Wukong overlay controls on the phone first" }
                            if (VirtualScreenState.service == null) VirtualScreenState.start(context)
                        }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (VirtualScreenState.displayId == 0 && System.nanoTime() < deadline) Thread.sleep(100)
                        check(VirtualScreenState.displayId > 0) { "Virtual display could not start; activate the phone engine first" }
                        VirtualScreenState.displayId
                    } else 0
                    val receipt = ui { RemoteTasks.start(context, request, target) }
                    respond(socket, 202, receipt)
                }
                path.matches(Regex("/v1/tasks/[a-f0-9-]{36}(/(stop|takeover|resume|logs))?")) -> {
                    val parts = path.split('/'); val id = parts[3]; val action = parts.getOrNull(4)
                    when {
                        method == "GET" && action == null -> respond(socket, 200, ui { RemoteTasks.get(context, id) })
                        method == "POST" && action in setOf("stop", "takeover", "resume") -> respond(socket, 200, ui { RemoteTasks.control(context, id, action!!) })
                        method == "GET" && action == "logs" -> {
                            val name = ui { RemoteTasks.logName(context, id) } ?: throw NoSuchElementException("No retained log for this task")
                            val file = RunLogExporter(context).create(name)
                            try { respond(socket, 200, file.readBytes(), "application/zip") } finally { file.delete() }
                        }
                        else -> respond(socket, 404, json("Unknown endpoint"))
                    }
                }
                else -> respond(socket, 404, json("Unknown endpoint"))
            }
        } catch (error: Exception) {
            // Provider error bodies can echo credentials: scrub both new and saved keys.
            var message = error.message.orEmpty().take(2000).ifBlank { "Request failed" }
            listOf(request.optString("key"), runCatching { SettingsStore(context).load()?.apiKey }.getOrNull()).filterNotNull().filter { it.isNotBlank() }
                .forEach { message = message.replace(it, "[redacted]") }
            runCatching { respond(socket, when (error) { is NoSuchElementException -> 404; is IllegalArgumentException -> 400; is IllegalStateException -> 409; else -> 503 }, json(message)) }
        }
    }
    private fun status(): JSONObject {
        val engine = runCatching { ShellClient(context).status() }
        return JSONObject().put("protocol", 1).put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("engineReady", engine.isSuccess).put("engineMessage", engine.exceptionOrNull()?.message ?: "Ready")
            .put("overlayReady", Settings.canDrawOverlays(context)).put("busy", TaskRuntime.active)
            .put("interactive", context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("locked", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("virtualDisplayId", VirtualScreenState.displayId).put("model", modelInfo())
    }
    private fun modelInfo(): JSONObject {
        val settings = SettingsStore(context).load()
        return JSONObject().put("configured", settings != null).put("endpoint", settings?.endpoint ?: "").put("model", settings?.model ?: "")
            .put("providerId", settings?.providerId ?: "custom").put("useFullEndpoint", settings?.useFullEndpoint ?: false)
            .put("thinkingEnabled", settings?.thinkingEnabled ?: false).put("effectiveThinking", settings?.effectiveThinking ?: false)
            .put("presets", JSONArray(ModelPresets.all.map { JSONObject().put("id", it.id).put("endpoint", it.endpoint).put("model", it.model).put("platform", it.platform) }))
    }
    private fun modelSettings(request: JSONObject): ModelSettings {
        val saved = SettingsStore(context).load()
        val endpoint = request.getString("endpoint").trim()
        val provider = request.optString("providerId", "custom")
        val key = request.optString("key").ifBlank {
            require(saved != null && saved.endpoint == endpoint && saved.providerId == provider) { "Enter an API key for this endpoint" }
            saved.apiKey
        }
        return ModelSettings(endpoint, request.optString("model", "discovery").trim(), key, request.optBoolean("useFullEndpoint"), request.optBoolean("thinkingEnabled"), provider).also { it.validate() }
    }
    private fun json(message: String) = JSONObject().put("error", message)
    private fun respond(socket: Socket, status: Int, body: JSONObject) = respond(socket, status, body.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
    private fun respond(socket: Socket, status: Int, bytes: ByteArray, type: String) {
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }
    override fun close() { closed = true; listener?.close(); workers.shutdownNow() }
    companion object { const val PORT = 39743 }
}
