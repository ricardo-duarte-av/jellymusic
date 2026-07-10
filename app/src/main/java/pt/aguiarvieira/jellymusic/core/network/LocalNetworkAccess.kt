package pt.aguiarvieira.jellymusic.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helpers for Android 17's [Local Network Protection](https://developer.android.com/privacy-and-security/local-network-permission).
 *
 * On apps targeting API 37+, reaching a local/private network address (LAN discovery or HTTP calls
 * to private IPs) requires the [PERMISSION] runtime permission. Public/remote servers are
 * unaffected, so the app only requests it when the user actually acts locally.
 */
object LocalNetworkAccess {

    // String literal rather than Manifest.permission.ACCESS_LOCAL_NETWORK so the code also compiles
    // and behaves on SDKs where the constant may be absent; the platform ignores it below API 37.
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** API 37 (Android 17); numeric to avoid depending on a possibly-unstable codename constant. */
    private const val ANDROID_17 = 37

    /** Whether the local-network runtime permission is enforced on this device. */
    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= ANDROID_17

    fun isGranted(context: Context): Boolean =
        !isRequired() ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Best-effort check for whether an entered server address points at the local network, so we can
     * request the permission before connecting. Handles localhost, `*.local` mDNS names, and the
     * IPv4/IPv6 private ranges.
     */
    fun isLocalAddress(input: String): Boolean {
        val host = extractHost(input).lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".local")) return true

        // IPv6 loopback / unique-local
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) {
            return true
        }

        val octets = host.split(".")
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return when {
                a == 10 -> true                     // 10.0.0.0/8
                a == 127 -> true                    // loopback
                a == 169 && b == 254 -> true        // link-local
                a == 172 && b in 16..31 -> true     // 172.16.0.0/12
                a == 192 && b == 168 -> true        // 192.168.0.0/16
                else -> false
            }
        }
        return false
    }

    private fun extractHost(input: String): String {
        var s = input.trim()
        val schemeIndex = s.indexOf("://")
        if (schemeIndex >= 0) s = s.substring(schemeIndex + 3)
        s = s.substringBefore('/')
        // Strip IPv6 brackets, then a trailing :port (but not the colons inside IPv6).
        if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']')
        return if (s.count { it == ':' } == 1) s.substringBefore(':') else s
    }
}
