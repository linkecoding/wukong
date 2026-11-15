package com.wukong.phone.script

/** Native engine owns no Java/Android API except one JSON bridge on its evaluation thread. */
internal class QuickJsEngine : AutoCloseable {
    private var handle = nativeCreate()
    fun evaluate(source: String, timeoutMs: Long, bridge: JsRuntimeService.Bridge): String =
        nativeEvaluate(handle, source.toByteArray(Charsets.UTF_8), bridge, timeoutMs).toString(Charsets.UTF_8)
    // Lifetime is serialized by JsRuntimeService.lifecycleLock; interrupt touches only an atomic flag.
    fun interrupt() { if (handle != 0L) nativeInterrupt(handle) }
    override fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0 } }
    private external fun nativeCreate(): Long
    private external fun nativeEvaluate(handle: Long, source: ByteArray, bridge: JsRuntimeService.Bridge, timeoutMs: Long): ByteArray
    private external fun nativeInterrupt(handle: Long)
    private external fun nativeDestroy(handle: Long)
    companion object { init { System.loadLibrary("wukong-quickjs") } }
}
