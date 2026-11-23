package com.dallas.cabletv.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections

/**
 * A small web page served on the TV's own network while the sign-in screen is open.
 * The phone opens the address from the QR code, types the details there, and the TV
 * receives them. Every request must carry the six-digit code shown on the TV, and the
 * server closes as soon as details arrive or the sign-in screen is left.
 *
 * Traffic on the home network is not encrypted. Use it on your own network only.
 */
class PhoneSignInServer(private val pin: String, private val onReceive: (Map<String, String>) -> Unit) {

    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** Starts listening and returns the port in use. */
    fun start(port: Int = 8080): Int {
        val s = ServerSocket(port)
        server = s
        running = true
        Thread {
            while (running) {
                val client = try {
                    s.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { handle(client) }.start()
            }
        }.start()
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrElse(0) { "" }
                val target = parts.getOrElse(1) { "/" }

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }

                val path = target.substringBefore('?')
                when {
                    method == "GET" && path == "/" -> {
                        val given = parseForm(target.substringAfter('?', "")).get("pin")
                        if (given == pin) {
                            respond(sock, "200 OK", formPage(pin))
                        } else {
                            respond(sock, "403 Forbidden", page("Wrong code. Scan the code on the TV again."))
                        }
                    }
                    method == "POST" && path == "/" -> {
                        val chars = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(chars, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        val fields = parseForm(String(chars, 0, read))
                        if (fields["pin"] == pin && running) {
                            val details = fields.filterKeys { it != "pin" }
                            respond(sock, "200 OK", page("Sent. You can close this page and check the TV."))
                            stop()
                            onReceive(details)
                        } else {
                            respond(sock, "403 Forbidden", page("Wrong code. Scan the code on the TV again."))
                        }
                    }
                    else -> respond(sock, "404 Not Found", page("Not found."))
                }
            } catch (e: Exception) {
                // A broken request is dropped. The TV keeps listening for the next one.
            }
        }
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

/** The address of this TV on the local network, or null when there is none. */
fun localIpv4(): String? = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses) }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()
