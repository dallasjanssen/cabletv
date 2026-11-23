package com.dallas.cabletv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Why the server closed itself. A normal stop or a received form does not use this. */
enum class SignInEnd { TooManyWrongCodes, Expired }

/**
 * A small web page served on the TV's own network while the sign-in screen is open.
 * The phone opens the address from the QR code, types the details there, and the TV
 * receives them. Every request must carry the six-digit code shown on the TV, and the
 * server closes as soon as details arrive, the sign-in screen is left, ten wrong codes
 * have been tried, or ten minutes have passed.
 *
 * Traffic on the home network is not encrypted. Use it on your own network only.
 */
class PhoneSignInServer(
    private val pin: String,
    private val onReceive: (Map<String, String>) -> Unit,
    /** Called once, on a server thread, when the server closes itself. */
    private val onEnd: (SignInEnd) -> Unit = {},
    private val maxWrongCodes: Int = 10,
    private val lifetimeMs: Long = 10 * 60_000L
) {

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false
    private val wrong = AtomicInteger(0)

    /** Starts listening and returns the port in use. Falls back to any free port when [port] is taken. */
    fun start(port: Int = 8080): Int {
        val s = try {
            ServerSocket(port)
        } catch (e: IOException) {
            ServerSocket(0)
        }
        s.soTimeout = 1_000
        server = s
        pool = Executors.newFixedThreadPool(4)
        running = true
        val deadline = System.currentTimeMillis() + lifetimeMs
        Thread {
            while (running) {
                if (System.currentTimeMillis() >= deadline) {
                    if (running) {
                        stop()
                        onEnd(SignInEnd.Expired)
                    }
                    break
                }
                val client = try {
                    s.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                try {
                    pool?.execute { handle(client) }
                } catch (e: Exception) {
                    runCatching { client.close() }
                }
            }
        }.also { it.isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { pool?.shutdown() }
    }

    private fun sameCode(given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8))

    /** Counts a wrong code. Closes the server on the tenth. */
    private fun noteWrong(sock: Socket) {
        respond(sock, "403 Forbidden", page("Wrong code. Scan the code on the TV again."))
        if (wrong.incrementAndGet() >= maxWrongCodes && running) {
            stop()
            onEnd(SignInEnd.TooManyWrongCodes)
        }
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 10_000
                val input = sock.getInputStream().buffered()
                val requestLine = readLine(input, MAX_LINE) ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrElse(0) { "" }
                val target = parts.getOrElse(1) { "/" }

                var contentLength = 0L
                var headers = 0
                while (true) {
                    val line = readLine(input, MAX_LINE) ?: break
                    if (line.isEmpty()) break
                    if (++headers > MAX_HEADERS) {
                        respond(sock, "431 Request Header Fields Too Large", page("Request too large."))
                        return
                    }
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                    }
                }
                if (contentLength < 0L) {
                    respond(sock, "400 Bad Request", page("Bad request."))
                    return
                }

                val path = target.substringBefore('?')
                when {
                    method == "GET" && path == "/" -> {
                        val given = parseForm(target.substringAfter('?', "")).get("pin")
                        when {
                            given != null && sameCode(given) -> respond(sock, "200 OK", formPage(pin))
                            given != null -> noteWrong(sock)
                            else -> respond(sock, "403 Forbidden", page("Scan the code on the TV."))
                        }
                    }
                    method == "POST" && path == "/" -> {
                        if (contentLength > MAX_BODY) {
                            respond(sock, "413 Payload Too Large", page("That is too much data."))
                            return
                        }
                        val body = ByteArray(contentLength.toInt())
                        var read = 0
                        while (read < body.size) {
                            val n = input.read(body, read, body.size - read)
                            if (n < 0) break
                            read += n
                        }
                        val fields = parseForm(String(body, 0, read, Charsets.UTF_8))
                        if (sameCode(fields["pin"])) {
                            if (!running) {
                                respond(sock, "403 Forbidden", page("This code has expired. Scan the code on the TV again."))
                                return
                            }
                            val details = fields.filterKeys { it != "pin" }
                            respond(sock, "200 OK", page("Sent. You can close this page and check the TV."))
                            stop()
                            onReceive(details)
                        } else {
                            noteWrong(sock)
                        }
                    }
                    else -> respond(sock, "404 Not Found", page("Not found."))
                }
            } catch (e: Exception) {
                // A broken request is dropped. The TV keeps listening for the next one.
            }
        }
    }

    /** Reads one line as ISO-8859-1. Returns null at the end of the stream, throws when the line is too long. */
    private fun readLine(input: InputStream, max: Int): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > max) throw IOException("Line too long")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun respond(sock: Socket, status: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(text: String): Map<String, String> =
        text.split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                key to value
            }

    private companion object {
        const val MAX_BODY = 16 * 1024
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 64
    }

    private fun page(message: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>CableTV sign-in</title></head>
        <body style="font-family:sans-serif;padding:24px;background:#05070f;color:#e8f4ff">
        <p style="font-size:20px">$message</p></body></html>
    """.trimIndent()

    private fun formPage(pin: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>CableTV sign-in</title>
        <style>
          body{font-family:sans-serif;margin:0;padding:16px;background:#05070f;color:#e8f4ff}
          h1{font-size:22px;color:#3dff8c;margin:8px 0}
          h2{font-size:16px;color:#ffb347;margin:24px 0 0}
          p{color:#b8c8e6;font-size:15px}
          label{display:block;margin-top:12px;font-size:15px;color:#b8c8e6}
          input{width:100%;box-sizing:border-box;padding:12px;font-size:18px;border-radius:6px;
                border:1px solid #1a2a4a;background:#0c1a36;color:#e8f4ff;margin-top:4px}
          button{margin-top:24px;width:100%;padding:14px;font-size:18px;border:0;border-radius:6px;
                 background:#3dff8c;color:#05070f;font-weight:bold}
        </style></head>
        <body>
        <h1>CableTV sign-in</h1>
        <p>Fill in the details you need. Anything left empty keeps what the TV already has.</p>
        <form method="post" action="/">
          <input type="hidden" name="pin" value="$pin">
          <h2>Live TV (Xtream Codes)</h2>
          <label>Server URL<input name="xs" autocapitalize="off" autocorrect="off"></label>
          <label>Username<input name="xu" autocapitalize="off" autocorrect="off"></label>
          <label>Password<input name="xp" type="password"></label>
          <h2>Live TV playlist (M3U, optional)</h2>
          <p>Kept as well as an Xtream login. Use it when you have a separate playlist.</p>
          <label>Playlist URL<input name="m3u" autocapitalize="off" autocorrect="off"></label>
          <h2>Programme guide (XMLTV, optional)</h2>
          <p>The EPG link from your provider. Used together with any guide the login already includes.</p>
          <label>Guide URL<input name="epg" autocapitalize="off" autocorrect="off" placeholder="https://example.com/epg.xml"></label>
          <h2>Stalker / Ministra portal (optional)</h2>
          <label>Portal URL<input name="su" autocapitalize="off" autocorrect="off"></label>
          <label>MAC address<input name="sm" autocapitalize="off" autocorrect="off" placeholder="00:1A:79:00:00:00"></label>
          <h2>On demand (Jellyfin, optional)</h2>
          <label>Server URL<input name="js" autocapitalize="off" autocorrect="off"></label>
          <label>Username<input name="ju" autocapitalize="off" autocorrect="off"></label>
          <label>Password<input name="jp" type="password"></label>
          <button type="submit">Send to TV</button>
        </form>
        </body></html>
    """.trimIndent()
}

/**
 * The address of this TV on the local network, or null when there is none. The active Wi-Fi or
 * Ethernet link is preferred, so a VPN or mobile data address is not put in the QR code.
 */
fun localIpv4(context: Context): String? {
    runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (net != null && caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        ) {
            val found = cm.getLinkProperties(net)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            if (found != null) return found.hostAddress
        }
    }
    return runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && SKIPPED_INTERFACES.none { p -> it.name.startsWith(p, ignoreCase = true) } }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

private val SKIPPED_INTERFACES = listOf("tun", "ppp", "p2p", "rmnet")
