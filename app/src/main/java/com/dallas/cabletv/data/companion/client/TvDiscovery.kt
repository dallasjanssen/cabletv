package com.dallas.cabletv.data.companion.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.dallas.cabletv.data.companion.CompanionProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Looks for TVs on the network with NsdManager while My TVs is on screen. Each service it sees is
 * resolved for its address and port, one at a time because older Android versions can resolve only
 * one service at once. Found TVs are on [found]. Call [start] when the screen appears and [stop]
 * when it goes away.
 */
class TvDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val list = DiscoveryList()
    private val _found = MutableStateFlow<List<DiscoveredTv>>(emptyList())

    /** TVs on the network now. */
    val found: StateFlow<List<DiscoveredTv>> = _found

    private val queue = OneAtATimeQueue<NsdServiceInfo> { resolve(it) }
    private var listener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        val created = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@TvDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                queue.enqueue(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.value = list.lost(info.serviceName)
            }
        }
        listener = created
        try {
            nsd.discoverServices(CompanionProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, created)
        } catch (e: RuntimeException) {
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        val active = listener ?: return
        listener = null
        queue.clear()
        try {
            nsd.stopServiceDiscovery(active)
        } catch (e: RuntimeException) {
            // Already stopped.
        }
        _found.value = list.clear()
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    queue.finished()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val tv = DiscoveredTv.fromResolved(
                        serviceInfo.serviceName.orEmpty(),
                        serviceInfo.host?.hostAddress,
                        serviceInfo.port,
                        serviceInfo.attributes.orEmpty()
                    )
                    // A resolve that finishes after stop() must not bring a TV back.
                    if (tv != null && synchronized(this@TvDiscovery) { listener != null }) _found.value = list.found(tv)
                    queue.finished()
                }
            })
        } catch (e: RuntimeException) {
            queue.finished()
        }
    }
}
