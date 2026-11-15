package com.wukong.phone

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only connects to this phone's loopback interface. No remote host or arbitrary command UI. */
class LocalAdb(private val identity: AdbIdentity) : AbsAdbConnectionManager() {
    init { setApi(Build.VERSION.SDK_INT); setHostAddress("127.0.0.1"); setTimeout(8, TimeUnit.SECONDS); setThrowOnUnauthorised(true) }
    override fun getPrivateKey() = identity.privateKey
    override fun getCertificate() = identity.certificate
    override fun getDeviceName() = "Wukong"

    fun pairLocal(port: Int, code: String) {
        require(Build.VERSION.SDK_INT >= 30) { "免电脑激活需要 Android 11 或以上" }
        require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "请输入有效配对端口和六位配对码" }
        val password = code.toByteArray(Charsets.UTF_8)
        val pairing = PairingConnectionCtx("127.0.0.1", port, password, identity.privateKey, identity.certificate, "Wukong")
        val deadline = deadlines.schedule({ runCatching { pairing.close() } }, 25, TimeUnit.SECONDS)
        try { pairing.use { it.start() } }
        finally { deadline.cancel(false); password.fill(0) }
    }

    fun connectLocal(port: Int) { require(port in 1..65535); check(connect("127.0.0.1", port) || isConnected) { "无线调试连接失败" } }

    fun shell(command: String): String {
        // A raw exec channel has no PTY hangup when its shell exits after starting nohup.
        val stream = openStream("exec:$command")
        val timeout = deadlines.schedule({ runCatching { stream.close() } }, 15, TimeUnit.SECONDS)
        try {
            val out = java.io.ByteArrayOutputStream()
            val input = stream.openInputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = try { input.read(buffer) } catch (e: java.io.IOException) { if (stream.isClosed) -1 else throw e }
                if (count < 0) break
                check(out.size() + count <= 32_768) { "设备返回内容过大" }
                out.write(buffer, 0, count)
            }
            return out.toString("UTF-8")
        } finally { timeout.cancel(false); stream.close() }
    }

    fun push(input: InputStream, remote: String, permissions: Int) {
        require(remote in setOf("/data/local/tmp/wukong-shell.apk", "/data/local/tmp/wukong-shell.token"))
        openStream("sync:").use { stream ->
            val timeout = deadlines.schedule({ runCatching { stream.close() } }, 20, TimeUnit.SECONDS)
            try {
                val output = stream.openOutputStream()
                fun header(id: String, number: Int) { output.write(id.toByteArray(Charsets.US_ASCII)); output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(number).array()) }
                val name = "$remote,${0x8000 or permissions}".toByteArray(Charsets.UTF_8)
                header("SEND", name.size); output.write(name)
                val buffer = ByteArray(32_768)
                input.use { source -> while (true) { val count = source.read(buffer); if (count < 0) break; header("DATA", count); output.write(buffer, 0, count) } }
                header("DONE", (System.currentTimeMillis() / 1000).toInt()); output.flush()
                val response = java.io.DataInputStream(stream.openInputStream())
                val tag = ByteArray(4).also { response.readFully(it) }
                val length = ByteArray(4).also { response.readFully(it) }
                check(String(tag, Charsets.US_ASCII) == "OKAY" && ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).int == 0) { "上传执行引擎失败" }
                header("QUIT", 0); output.flush()
            } finally { timeout.cancel(false) }
        }
    }

    fun activate(context: Context, progress: (String) -> Unit) {
        check(!TaskRuntime.active) { "请先停止当前任务" }
        // A new runtime cannot retain the old process's display/Surface handles.
        VirtualScreenState.stop(context)
        check(shell("id -u").trim() == "2000") { "无线调试没有提供 shell 身份" }
        progress("正在准备手机执行引擎…")
        shell(STOP)
        val bytes = context.assets.open("wukong-shell.apk").use { it.readBytes() }
        push(bytes.inputStream(), "/data/local/tmp/wukong-shell.apk", 420)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(shell("sha256sum /data/local/tmp/wukong-shell.apk").trim().substringBefore(' ') == hash) { "执行引擎校验失败，请重试" }
        val token = ShellCredentials.rotate(context)
        push(token.toByteArray(Charsets.UTF_8).inputStream(), "/data/local/tmp/wukong-shell.token", 384)
        shell("chmod 600 /data/local/tmp/wukong-shell.token && chmod 644 /data/local/tmp/wukong-shell.apk")
        progress("正在启动执行引擎…")
        shell("CLASSPATH=/data/local/tmp/wukong-shell.apk nohup setsid app_process /system/bin com.wukong.shell.Main 43219 /data/local/tmp/wukong-shell.token >/data/local/tmp/wukong-shell.log 2>&1 </dev/null & echo \$! > /data/local/tmp/wukong-shell.pid; sleep 1")
        repeat(40) {
            if (runCatching { ShellClient(context).status() }.isSuccess) return
            Thread.sleep(250)
        }
        error("执行引擎未连接，请重试激活")
    }

    companion object {
        private val deadlines = Executors.newScheduledThreadPool(2)
        private val STOP = """if [ -f /data/local/tmp/wukong-shell.pid ]; then p=${'$'}(cat /data/local/tmp/wukong-shell.pid); case "${'$'}p" in ''|*[!0-9]*) exit 1;; esac; if [ -r /proc/${'$'}p/cmdline ]; then c=${'$'}(tr '\000' ' ' </proc/${'$'}p/cmdline); case "${'$'}c" in *com.wukong.shell.Main*) kill "${'$'}p";; esac; fi; rm -f /data/local/tmp/wukong-shell.pid; fi"""
    }
}
