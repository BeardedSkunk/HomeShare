package de.beardedskunk.clipsharing.web

import java.net.Inet4Address
import java.net.NetworkInterface

/** Ermittelt die lokale IPv4-Adresse im WLAN (fuer die Webserver-Anzeige). */
fun localIpv4(): String? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
