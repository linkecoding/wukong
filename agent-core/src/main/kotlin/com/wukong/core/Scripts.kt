package com.wukong.core

/** Host objects stay in trusted code. JavaScript receives only serialized observations. */
interface ScriptHost {
    fun observe(): Screen
    fun execute(action: Action, expected: Screen): String
}
fun interface ScriptRunner {
    val timeoutMillis: Long get() = 60000
    fun run(code: String, host: ScriptHost): String
}
internal class ScriptApprovalRejected : IllegalStateException("已拒绝操作，脚本停止")
