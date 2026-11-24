package com.raven.app.core.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * DNS resolver that stably orders IPv4 (`Inet4Address`) results ahead of IPv6 (`Inet6Address`).
 *
 * Prevents dual-stack sites with unreachable AAAA records (e.g., `everia.club` over IPv4-only
 * mobile/tunnel links) from exhausting OkHttp's connect timeout before trying IPv4, while falling
 * back cleanly on IPv6-only networks.
 */
class Ipv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = order(delegate.lookup(hostname))

    companion object {
        /** Stable sort placing IPv4 addresses ahead of IPv6 while preserving original relative order. */
        fun order(addresses: List<InetAddress>): List<InetAddress> {
            if (addresses.size < 2) return addresses
            return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }
}
