package com.wukong.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** The ADB shell can provision a connection. Other apps cannot read or rotate it. */
class BootstrapProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        context!!.enforceCallingPermission("android.permission.DUMP", "Only adb shell can activate Wukong")
        if (Binder.getCallingUid() != 2000) throw SecurityException("Only adb shell can activate Wukong")
        if (method == "companion") return Bundle().apply {
            putString("token", com.wukong.phone.companion.CompanionCredentials.get(context!!))
            putInt("port", com.wukong.phone.companion.CompanionServer.PORT)
        }
        require(method == "prepare") { "Unknown bootstrap operation" }
        val token = ShellCredentials.rotate(context!!)
        return Bundle().apply { putString("token", token); putInt("port", ShellClient.PORT) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
