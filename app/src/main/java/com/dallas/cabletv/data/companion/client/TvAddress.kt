package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol

/** An address typed in for "Add by address". */
data class TvAddress(val host: String, val port: Int) {
    /** The outcome of reading what the person typed: an address, or a short sentence saying what to fix. */
    sealed interface Result {
        data class Ok(val address: TvAddress) : Result
        data class Problem(val message: String) : Result
    }

    companion object {
        /**
         * Reads a host and a port as typed. The port may be left empty, which means the usual
         * [CompanionProtocol.DEFAULT_PORT]. A port may also be written after the host, as
         * 192.168.1.20:8766, when the port box is empty.
         */
        fun parse(hostText: String, portText: String): Result {
            var host = hostText.trim()
            var port = portText.trim()
            if (host.isEmpty()) return Result.Problem("Type the TV's IP address, like 192.168.1.20.")
            if (host.any { it.isWhitespace() }) return Result.Problem("An address has no spaces in it.")
            // host:port, but not an IPv6 address, which has several colons.
            if (port.isEmpty() && host.count { it == ':' } == 1) {
                port = host.substringAfter(':')
                host = host.substringBefore(':')
            }
            if (host.isEmpty()) return Result.Problem("Type the TV's IP address, like 192.168.1.20.")
            val number = if (port.isEmpty()) {
                CompanionProtocol.DEFAULT_PORT
            } else {
                port.toIntOrNull() ?: return Result.Problem("The port is a number from 1 to 65535.")
            }
            if (number !in 1..65535) return Result.Problem("The port is a number from 1 to 65535.")
            return Result.Ok(TvAddress(host, number))
        }
    }
}
