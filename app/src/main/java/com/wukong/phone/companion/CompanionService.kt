package com.wukong.phone.companion

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.wukong.phone.MainActivity
import com.wukong.phone.R
import java.security.SecureRandom

/** Starts only through an ADB-shell-protected Activity. Not a LAN server. */
class CompanionActivity : Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        startForegroundService(Intent(this, CompanionService::class.java))
        finish()
    }
}

internal object CompanionCredentials {
    @Synchronized fun get(context: Context): String {
        val prefs = context.getSharedPreferences("companion-auth", 0)
        return prefs.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { check(prefs.edit().putString("token", it).commit()) }
    }
    fun revoke(context: Context) { context.getSharedPreferences("companion-auth", 0).edit().clear().commit() }
}

class CompanionService : Service() {
    private var server: CompanionServer? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("companion", "Agent connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 71, Intent(this, CompanionService::class.java).setAction("disconnect"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(7, Notification.Builder(this, "companion").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.companion_notification_title)).setContentText(getString(R.string.companion_notification_body))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.companion_disconnect), stop).build()).build())
        server = CompanionServer(this, CompanionCredentials.get(this)).also { it.start() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") {
            server?.close(); server = null
            RemoteTasks.stopOwned()
            CompanionCredentials.revoke(this)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { server?.close(); super.onDestroy() }
}
