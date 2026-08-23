package com.lateral.privileged

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * mDNS discovery of Android's wireless-debugging ADB endpoints on the local network.
 *
 * Android advertises two ADB services over mDNS while wireless debugging is in use:
 *  - `_adb-tls-pairing._tcp` — *only* while the user has the "Pair device with a pairing
 *    code" dialog open; gone the moment the dialog closes. Holds the pairing port.
 *  - `_adb-tls-connect._tcp` — advertised while Wireless debugging is enabled; the port
 *    changes each time it is toggled, which is exactly why mDNS discovery exists.
 *
 * After a Wireless-Debugging toggle, Android sometimes leaves a stale `_adb-tls-connect._tcp`
 * record advertised next to the active one — they share a service name but resolve to
 * different ports, and only one is actually listening. Returning only the first hit races
 * the stale one into being picked, so [discoverConnect] returns *all* endpoints resolved
 * within a short settle window and the caller tries each in turn.
 */
object AdbDiscovery {

    private const val TAG = "Lateral/Privileged"
    // NsdManager wants service types without a trailing dot — "_x._tcp", never "_x._tcp.".
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"

    /**
     * After the first endpoint resolves, keep collecting for this long to catch sibling
     * advertisements (Android sometimes publishes a stale entry alongside the active one).
     */
    private const val COLLECT_SETTLE_MS = 500L

    data class Endpoint(val host: String, val port: Int)

    /** Discover the pairing service; returns null if nothing showed up before [timeoutMs]. */
    fun discoverPairing(context: Context, timeoutMs: Long): Endpoint? =
        discoverAll(context, PAIRING_SERVICE, timeoutMs).firstOrNull()

    /**
     * Discover the connect service. Returns every endpoint resolved within [timeoutMs] plus
     * a short settle window — empty if wireless debugging is off. Callers should try them
     * in order: Android can advertise a stale entry next to the active one.
     */
    fun discoverConnect(context: Context, timeoutMs: Long): List<Endpoint> =
        discoverAll(context, CONNECT_SERVICE, timeoutMs)

    private fun discoverAll(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
    ): List<Endpoint> {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            Log.e(TAG, "no NsdManager")
            return emptyList()
        }
        val results = LinkedBlockingQueue<Endpoint>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.i(TAG, "mDNS discovery started: $type")
            }

            override fun onDiscoveryStopped(type: String) {}

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery start failed ($errorCode): $type")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}

            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // resolveService is single-flight per call but separate calls queue up; the
                // settle window after the first resolve gives Android time to deliver the
                // sibling resolutions.
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS resolve failed ($errorCode): ${info.serviceName}")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        Log.i(TAG, "mDNS resolved $serviceType -> $host:${info.port}")
                        results.offer(Endpoint(host, info.port))
                    }
                })
            }
        }

        // Without a held multicast lock, Wi-Fi drivers filter multicast traffic — including
        // the mDNS replies the discovery depends on. The lock is released in `finally`.
        val multicastLock = context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock(TAG)
            ?.apply { setReferenceCounted(false); acquire() }

        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            val first = results.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return emptyList()
            val collected = mutableListOf(first)
            // After the first hit, drain anything else that arrives within the settle window
            // — that's where the stale sibling shows up — keeping uniqueness on (host, port).
            val deadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(COLLECT_SETTLE_MS)
            while (true) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) break
                val next = results.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
                if (next !in collected) collected.add(next)
            }
            collected
        } catch (e: Exception) {
            Log.e(TAG, "mDNS discovery error: $serviceType", e)
            emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            runCatching { multicastLock?.release() }
        }
    }
}
