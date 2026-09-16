package com.prabotics.rapidrop.network

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.prabotics.rapidrop.preference.PreferencesManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean


class NsdDiscovery(
    private val context: Context,
    private val onDevicesUpdated: (List<DiscoveredDevice>) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val multicastLock = wifiManager?.createMulticastLock("RapiDrop_mDNS")?.apply {
        setReferenceCounted(false)
    }

    private var serverDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var clientDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)
    private val resolveTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resolveTimeoutRunnable: Runnable? = null
    private val resolvedServices = ConcurrentHashMap<String, DiscoveredDevice>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    companion object {
        private const val SERVICE_TYPE = WireFrame.SERVICE_TYPE
        private const val CLIENT_SERVICE_TYPE = WireFrame.CLIENT_SERVICE_TYPE
    }

    fun startDiscovery() {
        if (isDiscovering) return

        try {
            multicastLock?.acquire()
        } catch (e: SecurityException) {
            Log.w("RapiDrop", "Failed to acquire multicast lock", e)
        }

        val createListener = {
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    isDiscovering = true
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("_clipsync")) {
                        resolveQueue.add(serviceInfo)
                        processNextResolve()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val serviceKey = "${serviceInfo.serviceType}.${serviceInfo.serviceName}"
                    resolvedServices.remove(serviceKey)
                    resolvedServices.keys.removeAll { it.endsWith(".${serviceInfo.serviceName}") }
                    updateMergedDevices()
                }

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    releaseMulticast()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    releaseMulticast()
                }
            }
        }

        val sListener = createListener()
        val cListener = createListener()

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, sListener)
            serverDiscoveryListener = sListener
        } catch (e: IllegalArgumentException) {
            Log.w("RapiDrop", "Failed to discover server services", e)
        }

        try {
            nsdManager.discoverServices(CLIENT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, cListener)
            clientDiscoveryListener = cListener
        } catch (e: IllegalArgumentException) {
            Log.w("RapiDrop", "Failed to discover client services", e)
        }

        if (serverDiscoveryListener == null && clientDiscoveryListener == null) {
            releaseMulticast()
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        serverDiscoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: IllegalArgumentException) {
                Log.w("RapiDrop", "Failed to stop server discovery", e)
            }
        }
        clientDiscoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: IllegalArgumentException) {
                Log.w("RapiDrop", "Failed to stop client discovery", e)
            }
        }
        serverDiscoveryListener = null
        clientDiscoveryListener = null
        isDiscovering = false
        resolveTimeoutRunnable?.let { resolveTimeoutHandler.removeCallbacks(it) }
        resolveTimeoutRunnable = null
        resolveQueue.clear()
        isResolving.set(false)
        resolvedServices.clear()
        updateMergedDevices()
        releaseMulticast()
    }
    fun restartDiscovery() {
        resolvedServices.clear()
        updateMergedDevices()
        if (isDiscovering) {
            serverDiscoveryListener?.let {
                try { nsdManager.stopServiceDiscovery(it) } catch (e: IllegalArgumentException) {
                    Log.w("RapiDrop", "Failed to stop server discovery during restart", e)
                }
            }
            clientDiscoveryListener?.let {
                try { nsdManager.stopServiceDiscovery(it) } catch (e: IllegalArgumentException) {
                    Log.w("RapiDrop", "Failed to stop client discovery during restart", e)
                }
            }
            serverDiscoveryListener = null
            clientDiscoveryListener = null
            isDiscovering = false
            resolveQueue.clear()
            isResolving.set(false)
        }
        startDiscovery()
    }

    private fun getLocalIPv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress?.removePrefix("::ffff:")
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun registerClientService() {
        if (isRegistered) return
        val friendlyName = DeviceNameHelper.getDeviceFriendlyName(context)
        val deviceId = PreferencesManager(context).getDeviceId()
        val localIp = getLocalIPv4Address()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = friendlyName
            serviceType = CLIENT_SERVICE_TYPE
            port = WireFrame.DEFAULT_CLIENT_PORT
            setAttribute("id", deviceId)
            setAttribute("device", "android")
            if (!localIp.isNullOrBlank()) {
                setAttribute("ip", localIp)
            }
            setAttribute("port", "${WireFrame.DEFAULT_CLIENT_PORT}")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                isRegistered = true
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isRegistered = false
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
            }
        }
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
        } catch (e: IllegalArgumentException) {
            Log.w("RapiDrop", "Failed to register client service", e)
        }
    }

    fun unregisterClientService() {
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            Log.w("RapiDrop", "Failed to unregister client service", e)
        }
        registrationListener = null
        isRegistered = false
    }

    private fun releaseMulticast() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        } catch (e: RuntimeException) {
            Log.w("RapiDrop", "Failed to release multicast lock", e)
        }
    }

    private fun processNextResolve() {
        if (!isResolving.compareAndSet(false, true)) return

        val next = resolveQueue.poll()
        if (next == null) {
            isResolving.set(false)
            return
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolveTimeoutRunnable?.let { resolveTimeoutHandler.removeCallbacks(it) }
                resolveTimeoutRunnable = null
                isResolving.set(false)
                processNextResolve()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val rawHost = info.host?.hostAddress?.removePrefix("::ffff:")?.substringBefore("%")
                val txtIp = info.attributes["ip"]?.let { String(it, Charsets.UTF_8) }?.removePrefix("::ffff:")?.substringBefore("%")
                val host = if (!txtIp.isNullOrBlank()) txtIp else rawHost
                val txtPort = info.attributes["port"]?.let { String(it, Charsets.UTF_8).toIntOrNull() }
                val port = if (txtPort != null && txtPort > 0) txtPort else info.port
                val name = info.serviceName
                val remoteId = info.attributes["id"]?.let { String(it, Charsets.UTF_8) }.orEmpty()

                if (host != null) {
                    val serviceKey = "${info.serviceType}.${info.serviceName}"
                    val device = DiscoveredDevice(name = name, host = host, port = port, id = remoteId)
                    resolvedServices[serviceKey] = device
                    updateMergedDevices()
                }
                resolveTimeoutRunnable?.let { resolveTimeoutHandler.removeCallbacks(it) }
                resolveTimeoutRunnable = null
                isResolving.set(false)
                processNextResolve()
            }
        }
        resolveTimeoutRunnable?.let { resolveTimeoutHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            if (isResolving.compareAndSet(true, false)) {
                Log.w("RapiDrop", "mDNS resolve timed out, unlocking queue")
                processNextResolve()
            }
        }
        resolveTimeoutRunnable = timeoutRunnable
        resolveTimeoutHandler.postDelayed(timeoutRunnable, 6000L)

        try {
            nsdManager.resolveService(next, resolveListener)
        } catch (_: Exception) {
            resolveTimeoutRunnable?.let { resolveTimeoutHandler.removeCallbacks(it) }
            resolveTimeoutRunnable = null
            isResolving.set(false)
            processNextResolve()
        }
    }
    private fun updateMergedDevices() {
        val localDeviceId = PreferencesManager(context).getDeviceId()
        val ownName = DeviceNameHelper.getDeviceFriendlyName(context)

        val devicesById = mutableMapOf<String, DiscoveredDevice>()
        val devicesByNameWithoutId = mutableMapOf<String, DiscoveredDevice>()

        for (dev in resolvedServices.values) {
            val isSelf = if (dev.id.isNotBlank() && localDeviceId.isNotBlank()) {
                dev.id == localDeviceId
            } else {
                ownName.isNotBlank() && DeviceNameHelper.isSameDevice(dev.name, ownName)
            }
            if (isSelf) continue

            if (dev.id.isNotBlank()) {
                val existing = devicesById[dev.id]
                if (existing == null || existing.port != WireFrame.DEFAULT_PORT || dev.port == WireFrame.DEFAULT_PORT) {
                    devicesById[dev.id] = dev
                }
            } else {
                val nameKey = DeviceNameHelper.normalizeDeviceName(dev.name)
                if (!devicesByNameWithoutId.containsKey(nameKey)) {
                    devicesByNameWithoutId[nameKey] = dev
                }
            }
        }

        val merged = devicesById.values.toMutableList()
        val seenNamesWithId = merged.map { DeviceNameHelper.normalizeDeviceName(it.name) }.toSet()

        for ((nameKey, dev) in devicesByNameWithoutId) {
            if (!seenNamesWithId.contains(nameKey)) {
                merged.add(dev)
            }
        }

        onDevicesUpdated(merged)
    }
}
