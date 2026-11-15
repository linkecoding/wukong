package com.wukong.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface

/** Resolve only services advertised by addresses belonging to this device. Connections use loopback. */
class WirelessDiscovery(context: Context, private val found: (Boolean, Int) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    @Volatile private var closed = false
    fun start() {
        discover("_adb-tls-pairing._tcp.", true)
        discover("_adb-tls-connect._tcp.", false)
    }
    private fun discover(type: String, pairing: Boolean) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (closed) return
                @Suppress("DEPRECATION")
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val host = info.host ?: return
                        val local = runCatching {
                            host.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().toList().any { iface -> iface.inetAddresses.toList().any { it.address.contentEquals(host.address) } }
                        }.getOrDefault(false)
                        if (!closed && local && info.port in 1..65535) found(pairing, info.port)
                    }
                })
            }
        }
        listeners += listener
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
    }
    override fun close() { closed = true; listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }; listeners.clear() }
}
