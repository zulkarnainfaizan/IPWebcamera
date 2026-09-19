package com.example.mycctv

import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getIPAddress(useIPv4: Boolean): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Sort to prefer wlan0
            interfaces.sortByDescending { it.name.contains("wlan") }
            
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: ""
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (useIPv4) {
                            if (isIPv4 && sAddr.isNotEmpty()) return sAddr
                        } else {
                            if (!isIPv4 && sAddr.isNotEmpty()) {
                                val delim = sAddr.indexOf('%') // drop ip6 zone suffix
                                return if (delim < 0) sAddr.uppercase() else sAddr.substring(0, delim).uppercase()
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "No Connection"
    }
}
