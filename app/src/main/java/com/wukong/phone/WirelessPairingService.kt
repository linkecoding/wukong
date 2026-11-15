package com.wukong.phone

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Inline notification input keeps Android's pairing dialog open in the Settings app. */
class WirelessPairingService : Service() {
    private var discovery: WirelessDiscovery? = null
    private val update: () -> Unit = { getSystemService(NotificationManager::class.java).notify(2, notification()) }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("wireless", "悟空无线配对", NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(2, notification())
        WirelessActivation.listen(update)
        discovery = WirelessDiscovery(this, WirelessActivation::discovered).also { it.start() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "pair" -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("pairing_code")?.toString()?.trim()
                if (code != null) runCatching { WirelessActivation.pair(this, code) }
                    .onFailure { WirelessActivation.inputError(it.message ?: "配对请求无效，请重试") }
            }
            "stop" -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, WirelessSetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pair = PendingIntent.getService(this, 11, Intent(this, WirelessPairingService::class.java).setAction("pair"), flags)
        val stop = PendingIntent.getService(this, 12, Intent(this, WirelessPairingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val remoteInput = RemoteInput.Builder("pairing_code").setLabel("系统显示的六位配对码").build()
        val builder = Notification.Builder(this, "wireless").setSmallIcon(R.drawable.ic_wukong).setContentTitle("悟空 · 手机内配对")
            .setContentText(WirelessActivation.message).setStyle(Notification.BigTextStyle().bigText(WirelessActivation.message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (!WirelessActivation.activated && !WirelessActivation.busy) builder.addAction(Notification.Action.Builder(null, "输入配对码", pair).setAllowGeneratedReplies(false).addRemoteInput(remoteInput).build())
        return builder.addAction(Notification.Action.Builder(null, "结束助手", stop).build()).build()
    }
    override fun onDestroy() { discovery?.close(); WirelessActivation.unlisten(update); super.onDestroy() }
}
