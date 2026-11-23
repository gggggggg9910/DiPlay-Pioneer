package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Hybrid wired transport: iAP2 control stays on USB while the AirPlay session runs over the
 * Wi-Fi network the head unit has joined — in practice the iPhone's own personal hotspot, which
 * the head unit auto-joins once saved. This exists for Android 7.x firmware whose kernel does
 * not process packets on VpnService tunnels (wired NCM/VPN transport confirmed dead there).
 *
 * Optional overrides live in /sdcard/diplay_hybrid.conf, one "key=value" per line:
 * ssid, passphrase, channel, security (security: 0 open, 1 WEP, 2 WPA/WPA2, 3 WPA3 transition,
 * 4 WPA3 only). Missing keys fall back to the currently joined Wi-Fi network.
 */
object HybridWifi {
    data class Info(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val address: InetAddress,
        val securityType: Int,
    )

    private const val OVERRIDE_FILE =
        "/sdcard/Android/data/jp.pioneer.ceam.test/files/diplay_hybrid.conf"

    /** Null when the head unit is not on Wi-Fi or has no IPv4 on wlan0. */
    fun detect(context: Context): Info? {
        val overrides = readOverrides()
        val wifi = runCatching {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return null
        val connection = runCatching { wifi.connectionInfo }.getOrNull()
        val systemSsid = connection?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val ssid = overrides["ssid"] ?: systemSsid ?: return null
        val address = wlan0Ipv4() ?: return null
        val passphrase = overrides["passphrase"] ?: ""
        val securityType = overrides["security"]?.toIntOrNull() ?: 2
        val channel = overrides["channel"]?.toIntOrNull() ?: run {
            val frequency = connection?.frequency ?: 0
            when {
                frequency in 5_000..5_900 -> (frequency - 5_000) / 5
                frequency in 2_401..2_484 -> (frequency - 2_407) / 5
                else -> 0
            }
        }
        if (securityType != 0 && passphrase.isEmpty()) return null
        return Info(ssid, passphrase, channel, address, securityType)
    }

    private fun wlan0Ipv4(): InetAddress? = runCatching {
        NetworkInterface.getByName("wlan0")?.interfaceAddresses
            ?.firstNotNullOfOrNull { candidate -> candidate.address as? Inet4Address }
    }.getOrNull()

    private fun readOverrides(): Map<String, String> = runCatching {
        File(OVERRIDE_FILE).readLines()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    line.substring(0, index).trim() to line.substring(index + 1).trim()
                }
            }
            .toMap()
    }.getOrDefault(emptyMap())
}
