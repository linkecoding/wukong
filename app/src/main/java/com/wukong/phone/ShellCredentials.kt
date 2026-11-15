package com.wukong.phone

import android.content.Context
import java.security.SecureRandom

object ShellCredentials {
    fun rotate(context: Context): String {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        check(context.getSharedPreferences("shell", Context.MODE_PRIVATE).edit().putString("token", token).commit()) { "无法保存设备连接" }
        return token
    }
}
