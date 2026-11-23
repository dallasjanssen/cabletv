package com.dallas.cabletv.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Xtream panel rules that differ between servers.
 *
 * The panel reads a catch-up start time in its own timezone, advertises which
 * stream formats it will actually serve, and often refuses a client it does not
 * recognise. These functions stay free of Android so the rules can be tested on
 * their own.
 */

/** Client string panels allow through. The same value is sent on API calls and on the stream. */
const val PLAYER_USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18"

private val apiSuffix = Regex("""/(?:get|player_api|panel_api)\.php$""", RegexOption.IGNORE_CASE)
private val fixedOffsetZone = Regex("""^UTC(?:([+-])(\d{2}):(\d{2}))?$""")
private val serverLocalTime = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$""")

private const val MAX_OFFSET_MINUTES = 14 * 60
private const val OFFSET_STEP_MINUTES = 15

enum class PortalStatus { ACTIVE, INACTIVE, EXPIRED, UNAVAILABLE }

enum class CatchupVariant { REST_TS, REST_M3U8, LEGACY_TS, LEGACY_M3U8 }

/** Portal base URL. A pasted player_api.php, panel_api.php, or get.php link is reduced to that base. */
fun normalizeXtreamServerUrl(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "Server URL is required" }
    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        throw IllegalArgumentException("Server URL is not valid")
    }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Server URL must start with http:// or https://" }
    require(uri.rawUserInfo.isNullOrEmpty()) { "Put the username and password in their own fields" }
    var host = uri.host
    var port = if (uri.port == -1) "" else ":${uri.port}"
    if (host == null) {
        // java.net.URI has no host for names with underscores. Read the authority by hand.
        val authority = uri.rawAuthority.orEmpty()
        require('@' !in authority) { "Put the username and password in their own fields" }
        val name = authority.substringBeforeLast(':', authority)
        val portText = if (authority.contains(':')) authority.substringAfterLast(':') else ""
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "Server URL is not valid"
        }
        require(portText.isEmpty() || portText.all { it.isDigit() }) { "Server URL is not valid" }
        host = name
        port = if (portText.isEmpty()) "" else ":$portText"
    }
    val path = uri.path.orEmpty().trimEnd('/').replace(apiSuffix, "").trimEnd('/')
    return "$scheme://$host$port$path"
}

/** Username and password from a pasted get.php or player_api.php link, when both are present. */
fun extractXtreamCredentials(value: String): Pair<String, String>? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    val query = uri.rawQuery ?: return null
    val params = mutableMapOf<String, String>()
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = decodeQuery(part.substring(0, eq)) ?: continue
        val decoded = decodeQuery(part.substring(eq + 1)) ?: continue
        params[key] = decoded
    }
    val user = params["username"]?.trim().orEmpty()
    val pass = params["password"]?.trim().orEmpty()
    if (user.isEmpty() || pass.isEmpty()) return null
    return user to pass
}

/**
 * Server, username, and password ready to save.
 * A pasted playlist link fills a blank username or password. Typed values win.
 */
fun xtreamLoginFields(server: String, user: String, pass: String): Triple<String, String, String> {
    val trimmed = server.trim()
    if (trimmed.isEmpty()) return Triple("", user.trim(), pass.trim())
    val fromUrl = extractXtreamCredentials(trimmed)
    val username = user.trim().ifEmpty { fromUrl?.first.orEmpty() }
    val password = pass.trim().ifEmpty { fromUrl?.second.orEmpty() }
    return Triple(normalizeXtreamServerUrl(trimmed), username, password)
}

fun resolvePortalStatus(
    auth: Any?,
    statusText: String?,
    expDate: Any?,
    nowMs: Long = System.currentTimeMillis()
): PortalStatus {
    val authFlag = normalizeAuth(auth)
    if (authFlag == false) return PortalStatus.INACTIVE
    val status = statusText?.trim()?.lowercase().orEmpty()
    if (status == "expired") return PortalStatus.EXPIRED
    val active = status == "active" || (authFlag == true && status.isEmpty())
    if (!active) return if (status.isNotEmpty()) PortalStatus.INACTIVE else PortalStatus.UNAVAILABLE
    val exp = parseExpiration(expDate)
    if (exp != null && exp * 1000 < nowMs) return PortalStatus.EXPIRED
    return PortalStatus.ACTIVE
}

/**
 * Seconds used to sort recently added titles.
 * Movies prefer `added`, series prefer `last_modified`. A value more than a day
 * in the future is a placeholder and is ignored. Millisecond values are accepted.
 */
fun recentlyAddedEpochSeconds(
    added: Long?,
    modified: Long?,
    series: Boolean,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000
): Long {
    fun valid(raw: Long?): Long {
        if (raw == null || raw <= 0L) return 0L
        val seconds = if (raw > 10_000_000_000L) raw / 1000 else raw
        if (seconds > nowEpochSeconds + 24 * 3600) return 0L
        return seconds
    }
    val addedSeconds = valid(added)
    val modifiedSeconds = valid(modified)
    return if (series) {
        if (modifiedSeconds != 0L) modifiedSeconds else addedSeconds
    } else {
        if (addedSeconds != 0L) addedSeconds else modifiedSeconds
    }
}

/** Playback address for a movie or episode. An absolute http(s) direct_source is used as-is. */
fun contentStreamUrl(
    base: String,
    user: String,
    pass: String,
    kind: String,
    id: String,
    extension: String?,
    directSource: String?
): String {
    val direct = directSource?.trim().orEmpty()
    if (direct.startsWith("http://") || direct.startsWith("https://")) return direct
    val ext = extension?.trim()?.removePrefix(".")?.ifEmpty { null } ?: "mp4"
    return "$base/$kind/${enc(user)}/${enc(pass)}/${enc(id)}.$ext"
}

/**
 * Live address, plus a MPEG-TS address when HLS is chosen and the panel also
 * serves TS (or did not say). The player tries the second address once if the
 * first is refused before playback starts.
 */
fun liveStreamUrls(
    base: String,
    user: String,
    pass: String,
    streamId: String,
    formats: List<String>
): Pair<String, String?> {
    val ext = liveExtension(formats)
    val root = "$base/live/${enc(user)}/${enc(pass)}/${enc(streamId)}"
    val fallback = if (ext == "m3u8" && mpegTsAllowed(formats)) "$root.ts" else null
    return "$root.$ext" to fallback
}

fun liveExtension(formats: List<String>): String {
    val names = formatNames(formats)
    if (names.isEmpty()) return "m3u8"
    if (names.any { it == "m3u8" || it == "hls" }) return "m3u8"
    if (names.any { it == "ts" || it == "mpegts" || it == "mpeg-ts" }) return "ts"
    return when (val first = names.first()) {
        "mpegts", "mpeg-ts" -> "ts"
        else -> first.substringBefore(';').trim().ifEmpty { "m3u8" }
    }
}

/** True when a TS fallback is safe. An empty advertisement still allows it, so a TS-only panel keeps playing. */
fun mpegTsAllowed(formats: List<String>): Boolean {
    val names = formatNames(formats)
    if (names.isEmpty()) return true
    return names.any { it == "ts" || it == "mpegts" || it == "mpeg-ts" }
}

/** MPEG-TS catch-up addresses first, then HLS. A panel that only allows one format is not probed for the other. */
fun catchupProbeOrder(formats: List<String>): List<CatchupVariant> {
    val names = formatNames(formats)
    val hls = names.any { it == "m3u8" || it == "hls" }
    val ts = names.any { it == "ts" || it == "mpegts" || it == "mpeg-ts" }
    val known = names.isNotEmpty()
    val allowTs = !known || ts || !hls
    val allowHls = !known || hls || !ts
    return buildList {
        if (allowTs) {
            add(CatchupVariant.REST_TS)
            add(CatchupVariant.LEGACY_TS)
        }
        if (allowHls) {
            add(CatchupVariant.REST_M3U8)
            add(CatchupVariant.LEGACY_M3U8)
        }
    }
}

/** The first variant whose response is 200 or 206, which is what a short range request returns for a real stream. */
fun chooseCatchupVariant(formats: List<String>, statusOf: (CatchupVariant) -> Int): CatchupVariant? =
    catchupProbeOrder(formats).firstOrNull { variant ->
        val code = statusOf(variant)
        code == 200 || code == 206
    }

fun catchupUrl(
    base: String,
    user: String,
    pass: String,
    streamId: String,
    startMs: Long,
    stopMs: Long,
    serverTimezone: String?,
    variant: CatchupVariant
): String {
    val minutes = ((stopMs - startMs) / 60_000L).coerceAtLeast(1L)
    val stamp = formatCatchupStart(startMs, serverTimezone)
    val u = enc(user)
    val p = enc(pass)
    val id = enc(streamId)
    return when (variant) {
        CatchupVariant.REST_TS -> "$base/timeshift/$u/$p/$minutes/$stamp/$id.ts"
        CatchupVariant.REST_M3U8 -> "$base/timeshift/$u/$p/$minutes/$stamp/$id.m3u8"
        CatchupVariant.LEGACY_TS -> legacyCatchup(base, u, p, id, stamp, minutes, "ts")
        CatchupVariant.LEGACY_M3U8 -> legacyCatchup(base, u, p, id, stamp, minutes, "m3u8")
    }
}

/** `Y-m-d:H-M` in the panel's clock. With no usable zone, the device clock is the remaining guess. */
fun formatCatchupStart(startMs: Long, serverTimezone: String?): String {
    val zoned = Instant.ofEpochMilli(startMs).atZone(zoneFor(serverTimezone))
    return String.format(
        java.util.Locale.US,
        "%04d-%02d-%02d:%02d-%02d",
        zoned.year,
        zoned.monthValue,
        zoned.dayOfMonth,
        zoned.hour,
        zoned.minute
    )
}

/**
 * IANA name when the runtime knows it, otherwise a fixed `UTC±HH:MM` snapshot
 * from `time_now` minus `timestamp_now`. Nothing trustworthy returns null.
 */
fun resolveServerTimezone(name: String?, timeNow: String?, timestampNow: Any?): String? {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isNotEmpty() && isSupportedTimeZoneName(trimmed)) return trimmed
    val offset = deriveOffsetMinutes(timeNow, timestampNow) ?: return null
    return formatFixedOffset(offset)
}

fun isSupportedTimeZoneName(name: String): Boolean =
    try {
        ZoneId.of(name)
        true
    } catch (e: Exception) {
        false
    }

/** UTC offset in minutes, snapped to 15 so a second of clock skew does not move the zone. */
fun deriveOffsetMinutes(timeNow: String?, timestampNow: Any?): Int? {
    val wallMs = parseNaiveUtcMs(timeNow) ?: return null
    val epoch = epochSeconds(timestampNow) ?: return null
    val rawMinutes = (wallMs / 1000.0 - epoch) / 60.0
    val snapped = (rawMinutes / OFFSET_STEP_MINUTES).roundToInt() * OFFSET_STEP_MINUTES
    if (kotlin.math.abs(snapped) > MAX_OFFSET_MINUTES) return null
    return snapped
}

fun parseNaiveUtcMs(value: String?): Long? {
    val match = serverLocalTime.matchEntire(value?.trim().orEmpty()) ?: return null
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toIntOrNull() ?: 0
    return try {
        LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

/** Whether catch-up is offered, and how many days of it the panel keeps. */
fun catchupOffered(archiveFlag: Any?, duration: Any?): Pair<Boolean, Int> {
    val days = when (duration) {
        is Number -> duration.toInt()
        is String -> duration.trim().toIntOrNull() ?: 0
        else -> 0
    }.coerceAtLeast(0)
    val flagged = when (archiveFlag) {
        is Boolean -> archiveFlag
        is Number -> archiveFlag.toInt() == 1
        is String -> archiveFlag.trim() == "1" || archiveFlag.trim().equals("true", ignoreCase = true)
        else -> false
    }
    return (flagged || days > 0) to days
}

/** Programmes that have finished inside the archive window, newest first. Clips under a minute are skipped. */
fun finishedWithin(programs: List<Program>, nowMs: Long, withinMs: Long): List<Program> {
    val cutoff = nowMs - withinMs
    return programs
        .filter { it.end <= nowMs && it.end > cutoff && it.end - it.start >= 60_000L }
        .sortedByDescending { it.start }
}

private fun formatNames(formats: List<String>): List<String> =
    formats.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

private fun formatFixedOffset(offsetMinutes: Int): String {
    if (offsetMinutes == 0) return "UTC"
    val sign = if (offsetMinutes < 0) "-" else "+"
    val absolute = kotlin.math.abs(offsetMinutes)
    return "UTC$sign${pad2(absolute / 60)}:${pad2(absolute % 60)}"
}

private fun zoneFor(serverTimezone: String?): ZoneId {
    if (!serverTimezone.isNullOrBlank()) {
        parseFixedOffset(serverTimezone)?.let { return ZoneOffset.ofTotalSeconds(it * 60) }
        try {
            return ZoneId.of(serverTimezone)
        } catch (e: Exception) {
            // Fall through to the device zone.
        }
    }
    return ZoneId.systemDefault()
}

private fun parseFixedOffset(value: String): Int? {
    val match = fixedOffsetZone.matchEntire(value.trim()) ?: return null
    val sign = match.groupValues[1]
    if (sign.isEmpty()) return 0
    val minutes = match.groupValues[2].toInt() * 60 + match.groupValues[3].toInt()
    return if (sign == "-") -minutes else minutes
}

private fun epochSeconds(value: Any?): Long? = when (value) {
    is Number -> value.toLong().takeIf { it > 0L }
    is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
    else -> null
}

private fun normalizeAuth(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is Number -> when (value.toInt()) {
        1 -> true
        0 -> false
        else -> null
    }
    is String -> when (value.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }
    else -> null
}

/** Unix milliseconds for a panel exp_date, or null when the panel did not send one. */
fun expirationEpochMillis(expDate: Any?): Long? = parseExpiration(expDate)?.times(1000)

private fun parseExpiration(value: Any?): Long? {
    val parsed = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().takeIf { it.isNotEmpty() }?.toLongOrNull() ?: return null
        else -> return null
    }
    return parsed.takeIf { it > 0L }
}

private fun legacyCatchup(
    base: String,
    user: String,
    pass: String,
    streamId: String,
    stamp: String,
    minutes: Long,
    extension: String
): String =
    "$base/streaming/timeshift.php?username=$user&password=$pass&stream=$streamId&start=$stamp&duration=$minutes&extension=$extension"

private fun enc(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun decodeQuery(value: String): String? =
    try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        null
    }

private fun pad2(n: Int): String = n.toString().padStart(2, '0')
