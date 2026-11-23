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

const val PHONE_BACKUP_JSON_FIELD = "__backup_restore_json"

/** Why the server closed itself. A normal stop or a received form does not use this. */
enum class SignInEnd { TooManyWrongCodes, Expired }

/**
 * A small web page served on the TV's own network while the sign-in screen is open.
 * The phone opens the address from the QR code, types the details there, and the TV
 * receives them. Every request must carry the six-digit code shown on the TV, and the
 * server closes when the sign-in screen is left, ten wrong codes have been tried,
 * or ten minutes have passed.
 *
 * Traffic on the home network is not encrypted. Use it on your own network only.
 */
class PhoneSignInServer(
    private val pin: String,
    private val onReceive: (Map<String, String>) -> Unit,
    /** Current values shown on the phone form when it opens. */
    private val currentValues: () -> Map<String, String> = { emptyMap() },
    /** Supplies a JSON backup when the phone asks to download one. */
    private val backupJson: (() -> String)? = null,
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
        respondHtml(sock, "403 Forbidden", page("Wrong code. Scan the code on the TV again."))
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
                        respondHtml(sock, "431 Request Header Fields Too Large", page("Request too large."))
                        return
                    }
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                    }
                }
                if (contentLength < 0L) {
                    respondHtml(sock, "400 Bad Request", page("Bad request."))
                    return
                }

                val path = target.substringBefore('?')
                val query = parseForm(target.substringAfter('?', ""))
                when {
                    method == "GET" && path == "/" -> {
                        val given = query["pin"]
                        when {
                            given != null && sameCode(given) -> respondHtml(sock, "200 OK", formPage(pin, currentValues(), backupJson != null))
                            given != null -> noteWrong(sock)
                            else -> respondHtml(sock, "403 Forbidden", page("Scan the code on the TV."))
                        }
                    }
                    method == "GET" && path == "/backup" -> {
                        val given = query["pin"]
                        when {
                            given != null && !sameCode(given) -> noteWrong(sock)
                            given == null || !sameCode(given) -> respondHtml(sock, "403 Forbidden", page("Scan the code on the TV."))
                            backupJson == null -> respondHtml(sock, "404 Not Found", page("Backup is not available here."))
                            else -> {
                                val provider = backupJson ?: return
                                val text = runCatching { provider.invoke() }.getOrElse {
                                    respondHtml(sock, "500 Internal Server Error", page("Backup failed. Check the TV and try again."))
                                    return
                                }
                                val name = "cabletv-backup-${System.currentTimeMillis()}.json"
                                respondBytes(
                                    sock,
                                    "200 OK",
                                    "application/json; charset=utf-8",
                                    text.toByteArray(Charsets.UTF_8),
                                    headers = listOf("Content-Disposition: attachment; filename=\"$name\"")
                                )
                            }
                        }
                    }
                    method == "POST" && path == "/" -> {
                        if (contentLength > MAX_BODY) {
                            respondHtml(sock, "413 Payload Too Large", page("That is too much data."))
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
                                respondHtml(sock, "403 Forbidden", page("This code has expired. Scan the code on the TV again."))
                                return
                            }
                            val details = fields.filterKeys { it != "pin" }
                            respondHtml(sock, "200 OK", page("Sent. You can keep this page open and send more changes."))
                            onReceive(details)
                        } else {
                            noteWrong(sock)
                        }
                    }
                    method == "POST" && path == "/restore" -> {
                        if (contentLength > MAX_BODY) {
                            respondText(sock, "413 Payload Too Large", "That file is too large.")
                            return
                        }
                        val given = query["pin"]
                        if (!sameCode(given)) {
                            if (given != null) noteWrong(sock) else respondText(sock, "403 Forbidden", "Wrong code.")
                            return
                        }
                        val body = ByteArray(contentLength.toInt())
                        var read = 0
                        while (read < body.size) {
                            val n = input.read(body, read, body.size - read)
                            if (n < 0) break
                            read += n
                        }
                        val json = String(body, 0, read, Charsets.UTF_8)
                        onReceive(mapOf(PHONE_BACKUP_JSON_FIELD to json))
                        respondText(sock, "200 OK", "Backup sent to TV. Check the TV for restore status.")
                    }
                    else -> respondHtml(sock, "404 Not Found", page("Not found."))
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

    private fun respondHtml(sock: Socket, status: String, html: String) {
        respondBytes(sock, status, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun respondText(sock: Socket, status: String, text: String) {
        respondBytes(sock, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun respondBytes(sock: Socket, status: String, contentType: String, bytes: ByteArray, headers: List<String> = emptyList()) {
        val out = sock.getOutputStream()
        out.write(
            buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $contentType\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
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
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 64
    }

    private fun page(message: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Cable TV sign-in</title>
        <style>
          body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;padding:24px;background:#0d0f13;color:#f3f4f6}
          main{max-width:680px;margin:0 auto;padding:20px;border-radius:12px;border:1px solid #2b3039;background:#171a20}
          h1{margin:0 0 10px;font-size:20px;line-height:1.2}
          p{margin:0;color:#b3b9c4;font-size:16px;line-height:1.45}
        </style></head>
        <body><main><h1>Cable TV sign-in</h1><p>$message</p></main></body></html>
    """.trimIndent()

    private fun formPage(pin: String, values: Map<String, String>, backupEnabled: Boolean): String {
        fun escAttr(text: String): String =
            text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        fun value(key: String): String = escAttr(values[key].orEmpty())
        val strip = values["strip"]?.let {
            it == "1" || it.equals("true", ignoreCase = true) || it.equals("on", ignoreCase = true)
        } == true
        val backupSection = if (!backupEnabled) "" else """
          <h2>Backup and restore</h2>
          <p>Download a full backup, or upload one from this phone.</p>
          <p><a href="/backup?pin=$pin" style="color:#3dff8c;font-weight:bold">Download backup file</a></p>
          <label>Upload backup file<input id="backupFile" type="file" accept=".json,application/json"></label>
          <button type="button" onclick="uploadBackup()">Upload backup to TV</button>
          <p id="restoreStatus" style="min-height:1.3em"></p>
        """
        val script = if (!backupEnabled) "" else """
        <script>
          async function uploadBackup(){
            const status=document.getElementById('restoreStatus');
            const file=document.getElementById('backupFile').files[0];
            if(!file){status.textContent='Pick a backup file first.';return;}
            status.textContent='Uploading...';
            try{
              const text=await file.text();
              const response=await fetch('/restore?pin=$pin',{
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body:text
              });
              status.textContent=await response.text();
            }catch(e){
              status.textContent='Upload failed. Try again.';
            }
          }
        </script>
        """
        return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Cable TV sign-in</title>
        <style>
          body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;padding:16px;background:#0d0f13;color:#f3f4f6}
          main{max-width:760px;margin:0 auto;padding:18px;border-radius:12px;border:1px solid #2b3039;background:#171a20}
          h1{font-size:24px;line-height:1.2;margin:0 0 8px}
          h2{font-size:16px;color:#f6a723;margin:22px 0 0}
          p{color:#b3b9c4;font-size:15px;line-height:1.45;margin:8px 0 0}
          label{display:block;margin-top:12px;font-size:14px;color:#b3b9c4}
          input,textarea{width:100%;box-sizing:border-box;padding:12px;font-size:17px;border-radius:10px;
                border:1px solid #2b3039;background:#20242c;color:#f3f4f6;margin-top:4px}
          input:focus,textarea:focus{outline:none;border-color:#f6a723;box-shadow:0 0 0 2px rgba(246,167,35,.20)}
          input[type=checkbox]{width:auto;transform:scale(1.15);margin-right:8px;accent-color:#f6a723}
          button{margin-top:24px;width:100%;padding:14px;font-size:17px;border:0;border-radius:999px;
                 background:#f6a723;color:#0d0f13;font-weight:700}
          a{color:#f6c76a}
        </style></head>
        <body>
        <main>
        <h1>Cable TV setup</h1>
        <p>Change live TV, library, and customization settings from your phone.</p>
        <form method="post" action="/">
          <input type="hidden" name="pin" value="$pin">
          <h2>Live TV (Xtream Codes)</h2>
          <label>Server URL<input name="xs" autocapitalize="off" autocorrect="off" value="${value("xs")}"></label>
          <label>Username<input name="xu" autocapitalize="off" autocorrect="off" value="${value("xu")}"></label>
          <label>Password<input name="xp" type="password" value="${value("xp")}"></label>
          <h2>Live TV playlist (M3U, optional)</h2>
          <p>Kept as well as an Xtream login. Use it when you have a separate playlist.</p>
          <label>Playlist URL<input name="m3u" autocapitalize="off" autocorrect="off" value="${value("m3u")}"></label>
          <h2>Programme guide (XMLTV, optional)</h2>
          <p>The EPG link from your provider. Used together with any guide the login already includes.</p>
          <label>Guide URL<input name="epg" autocapitalize="off" autocorrect="off" placeholder="https://example.com/epg.xml" value="${value("epg")}"></label>
          <h2>Stalker / Ministra portal (optional)</h2>
          <label>Portal URL<input name="su" autocapitalize="off" autocorrect="off" value="${value("su")}"></label>
          <label>MAC address<input name="sm" autocapitalize="off" autocorrect="off" placeholder="00:1A:79:00:00:00" value="${value("sm")}"></label>
          <h2>On demand (Jellyfin, optional)</h2>
          <label>Server URL<input name="js" autocapitalize="off" autocorrect="off" value="${value("js")}"></label>
          <label>Username<input name="ju" autocapitalize="off" autocorrect="off"></label>
          <label>Password<input name="jp" type="password"></label>
          <h2>Customizations</h2>
          <label>Hidden channel groups (comma separated)<input name="hidden" autocapitalize="off" autocorrect="off" value="${value("hidden")}"></label>
          <label>Locked groups and categories (comma separated)<input name="locked" autocapitalize="off" autocorrect="off" value="${value("locked")}"></label>
          <input type="hidden" name="strip" value="0">
          <label><input type="checkbox" name="strip" value="1" ${if (strip) "checked" else ""}>Show channel names without country prefixes</label>
          <button type="submit">Send to TV</button>
        </form>
        $backupSection
        </main>
        $script
        </body></html>
    """.trimIndent()
    }
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
