package com.dallas.cabletv.data

/**
 * Picture, guide, remote, and library choices that are not secrets.
 * Defaults keep the player behaving as it did before these choices existed.
 * Other code reads this type. It does not grow new fields casually: the store
 * and the settings screen both name every field.
 */
data class PlayerOptions(
    /** auto, small, medium, large, or very_large. auto follows the device's memory. */
    val bufferPreset: String = BUFFER_AUTO,
    val softwareVideoDecoder: Boolean = false,
    val softwareAudioDecoder: Boolean = false,
    val audioPassthrough: Boolean = false,
    val tunneledPlayback: Boolean = false,
    /** Skip video decoders whose names belong to Amlogic, which stutter on some boxes. */
    val amlogicFix: Boolean = false,
    val autoFrameRate: Boolean = false,
    /** When auto frame rate is on, only change the screen for 50 fps and 60 fps video. */
    val afrFilmRatesOnly: Boolean = true,
    val switchResolution: Boolean = false,
    /** Positive delays the sound. Negative delays the picture's audio path the other way. */
    val audioOffsetMs: Int = 0,
    /** Empty keeps the usual VLC identity sent to providers. */
    val userAgent: String = "",
    /** host:port of a udpxy-style proxy. Empty leaves udp:// addresses unchanged. */
    val udpProxy: String = "",
    /** Wait this long after the last channel step before the stream actually changes. */
    val zapDelayMs: Int = 250,
    val blackFrameOnZap: Boolean = false,
    /** 0 means no cap. A provider that allows two streams should be set to 2. */
    val maxConnections: Int = 0,
    /** Added to every guide time. Negative means the guide is ahead of the broadcast. */
    val epgOffsetMinutes: Int = 0,
    /** How many days of past listings to keep. 0 means the guide cache decides. */
    val epgPastDays: Int = 0,
    /** Hours between guide refreshes. 0 means refresh the way the app already does. */
    val epgUpdateHours: Int = 0,
    val epgUpdateOnStart: Boolean = true,
    val openLastChannel: Boolean = true,
    val showChannelNumbers: Boolean = true,
    val showChannelNames: Boolean = true,
    val showCatchupMark: Boolean = true,
    val twoLineNames: Boolean = false,
    val twoLineTitles: Boolean = false,
    /** Comma-separated prefixes to strip, such as "US:, UK:". */
    val namePrefixes: String = "",
    /** Comma-separated suffixes to strip, such as "| HD, | FHD". */
    val nameSuffixes: String = "",
    /** Empty uses the logo on the channel. Otherwise a folder of image files. */
    val logoFolder: String = "",
    /** playlist, epg, or folder. */
    val logoPriority: String = LOGO_PLAYLIST,
    /** Channel ids hidden one by one, separated by commas. */
    val hiddenChannelIds: String = "",
    /** Channel ids the PIN blocks one by one, separated by commas. */
    val blockedChannelIds: String = "",
    /**
     * Custom groups. Each group is a name, then =, then comma-separated channel ids.
     * Groups are separated by newlines.
     */
    val customGroups: String = "",
    /** Channel ids in the viewer's order, comma-separated. Empty keeps the provider order. */
    val channelOrder: String = "",
    /** channelId=epgId pairs, comma-separated, for a guide match chosen by hand. */
    val epgAssignments: String = "",
    val doubleBackToExit: Boolean = false,
    /** How long the channel banner stays up. The player used 5 seconds. */
    val infoTimeoutSec: Int = 5,
    /** 0 is solid, 100 is invisible. The banner used an opaque panel. */
    val infoTransparency: Int = 0,
    /** 0 is off. */
    val sleepTimerMinutes: Int = 0,
    val pipOnHome: Boolean = false,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    val barBackSec: Int = 10,
    val barForwardSec: Int = 30,
    /** 2, 3, or 4 pictures. */
    val multiviewSlots: Int = 2,
    /**
     * Remote overrides. One "key=action" per line. An empty value keeps today's buttons.
     * Keys look like ok, long_ok, left, long_back. Actions look like guide, previous_channel.
     */
    val remoteMap: String = "",
    val recordPadStartMin: Int = 0,
    val recordPadEndMin: Int = 0,
    /** Empty keeps recordings in the app's private folder. */
    val recordingsFolder: String = "",
    /** How many minutes before a programme the reminder appears. 1 matches the old window. */
    val remindLeadMin: Int = 1,
    val reminderPopupSec: Int = 30,
    val reminderWakesDevice: Boolean = false,
    /** none, 10m, 1h, session, or until_clear. */
    val pinGrace: String = PIN_GRACE_NONE,
    val sortByWatchTime: Boolean = false
) {
    val hiddenIds: Set<String> get() = csvSet(hiddenChannelIds)
    val blockedIds: Set<String> get() = csvSet(blockedChannelIds)

    fun epgIdFor(channelId: String, fallback: String?): String? =
        epgAssignmentMap()[channelId] ?: fallback

    fun epgAssignmentMap(): Map<String, String> = pairMap(epgAssignments)

    fun remoteActions(): Map<String, String> = pairMap(remoteMap.replace('\n', ','))

    /**
     * Choices that are fixed when the player is built. Buffer, decoders, the user agent, and the
     * audio offset cannot be changed on a player that is already open.
     */
    fun engineSignature(): String = listOf(
        bufferPreset,
        softwareVideoDecoder,
        softwareAudioDecoder,
        audioPassthrough,
        tunneledPlayback,
        amlogicFix,
        audioOffsetMs.coerceIn(-2_000, 2_000),
        userAgent.trim()
    ).joinToString("|")

    fun customGroupMap(): Map<String, List<String>> {
        if (customGroups.isBlank()) return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        for (line in customGroups.split('\n')) {
            val parts = line.split('=', limit = 2)
            if (parts.size < 2) continue
            val name = parts[0].trim()
            if (name.isEmpty()) continue
            out[name] = csvSet(parts[1]).toList()
        }
        return out
    }
}

const val BUFFER_AUTO = "auto"
const val BUFFER_SMALL = "small"
const val BUFFER_MEDIUM = "medium"
const val BUFFER_LARGE = "large"
const val BUFFER_VERY_LARGE = "very_large"

const val LOGO_PLAYLIST = "playlist"
const val LOGO_EPG = "epg"
const val LOGO_FOLDER = "folder"

const val PIN_GRACE_NONE = "none"
const val PIN_GRACE_10M = "10m"
const val PIN_GRACE_1H = "1h"
const val PIN_GRACE_SESSION = "session"
const val PIN_GRACE_UNTIL_CLEAR = "until_clear"

/** Min buffer, max buffer, start playback, resume after a stall, back buffer, byte cap. */
data class BufferPlan(
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
    val rebufferMs: Int,
    val backMs: Int,
    val byteCap: Int,
    val prioritizeTime: Boolean
)

/**
 * Turns the buffer choice into player numbers. Compact pictures stay small so four of them
 * cannot exhaust a TV box. auto keeps the memory-based rewind the player already computed.
 */
fun bufferPlan(preset: String, compact: Boolean, autoRewindMs: Int): BufferPlan {
    if (compact) {
        return BufferPlan(8_000, 15_000, 1_500, 4_000, 0, 16 * 1024 * 1024, false)
    }
    val rewind = autoRewindMs.coerceIn(45_000, 3 * 60_000)
    return when (preset) {
        BUFFER_SMALL -> BufferPlan(8_000, 20_000, 1_000, 3_000, 15_000, 24 * 1024 * 1024, true)
        BUFFER_MEDIUM -> BufferPlan(15_000, 45_000, 1_500, 5_000, 45_000, 48 * 1024 * 1024, true)
        BUFFER_LARGE -> BufferPlan(20_000, 90_000, 2_500, 6_000, 90_000, 96 * 1024 * 1024, true)
        BUFFER_VERY_LARGE -> BufferPlan(30_000, 180_000, 4_000, 8_000, 180_000, 160 * 1024 * 1024, true)
        else -> BufferPlan(15_000, rewind, 1_500, 5_000, rewind, 64 * 1024 * 1024, true)
    }
}

/** The identity sent on stream requests. A blank choice keeps the provider-friendly default. */
fun playbackUserAgent(custom: String): String =
    custom.trim().ifEmpty { PLAYER_USER_AGENT }

/**
 * Rewrites udp:// and rtp:// addresses through a udpxy-style proxy at host:port.
 * Anything that is already http, or a blank proxy, is returned unchanged.
 */
fun proxiedStreamUrl(url: String, proxy: String): String {
    val host = proxy.trim()
    if (host.isEmpty()) return url
    val rest = when {
        url.startsWith("udp://", ignoreCase = true) -> url.substring(6)
        url.startsWith("rtp://", ignoreCase = true) -> url.substring(6)
        else -> return url
    }.trimStart('/')
    if (rest.isEmpty()) return url
    val base = if (host.contains("://")) host else "http://$host"
    return base.trimEnd('/') + "/udp/" + rest
}

/** Moves a guide instant by the viewer's offset. */
fun shiftGuideTime(epochMs: Long, offsetMinutes: Int): Long =
    epochMs + offsetMinutes * 60_000L

fun csvSet(raw: String): Set<String> =
    raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun pairMap(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val out = linkedMapOf<String, String>()
    for (piece in raw.split(',', '\n')) {
        val parts = piece.split('=', limit = 2)
        if (parts.size < 2) continue
        val key = parts[0].trim()
        val value = parts[1].trim()
        if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
    }
    return out
}

/**
 * Removes a country prefix when [stripCountry] is set, then any extra prefixes and suffixes
 * the viewer typed. Matching is case-insensitive and trims the separators they typed.
 */
fun tidyChannelName(name: String, stripCountry: Boolean, prefixes: String, suffixes: String): String {
    var result = if (stripCountry) displayName(name) else name.trim()
    for (prefix in csvSet(prefixes)) {
        if (result.startsWith(prefix, ignoreCase = true)) {
            result = result.substring(prefix.length).trim()
        }
    }
    for (suffix in csvSet(suffixes)) {
        if (result.endsWith(suffix, ignoreCase = true)) {
            result = result.substring(0, result.length - suffix.length).trim()
        }
    }
    return result.ifEmpty { name }
}

/** Puts channels into the saved order. Channels that are not listed keep their relative order at the end. */
fun orderChannels(channels: List<LiveChannel>, order: String): List<LiveChannel> {
    val ids = order.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (ids.isEmpty()) return channels
    val rank = ids.withIndex().associate { it.value to it.index }
    return channels.withIndex().sortedWith(Comparator { a, b ->
        val ra = rank[a.value.id]
        val rb = rank[b.value.id]
        when {
            ra == null && rb == null -> a.index.compareTo(b.index)
            ra == null -> 1
            rb == null -> -1
            else -> ra.compareTo(rb)
        }
    }).map { it.value }
}

/** Start and stop of a recording after the viewer's early and late padding. */
fun recordingWindow(startMs: Long, endMs: Long, padStartMin: Int, padEndMin: Int): Pair<Long, Long> {
    val start = startMs - padStartMin.coerceAtLeast(0) * 60_000L
    val end = endMs + padEndMin.coerceAtLeast(0) * 60_000L
    return start to end.coerceAtLeast(start + 60_000L)
}

/** How early a reminder is allowed to appear. At least a minute, which is what the player did before. */
fun reminderLeadMs(leadMin: Int): Long = leadMin.coerceIn(1, 120) * 60_000L

/**
 * True when a correct PIN is still covering this channel. until_clear stays open only while
 * the viewer has not landed on a different blocked channel.
 */
fun pinGraceCovers(
    grace: String,
    unlockedAtMs: Long,
    nowMs: Long,
    unlockedChannelId: String?,
    channelId: String
): Boolean {
    if (unlockedAtMs <= 0L) return false
    return when (grace) {
        PIN_GRACE_10M -> nowMs - unlockedAtMs < 10 * 60_000L
        PIN_GRACE_1H -> nowMs - unlockedAtMs < 60 * 60_000L
        PIN_GRACE_SESSION -> true
        PIN_GRACE_UNTIL_CLEAR -> unlockedChannelId == null || unlockedChannelId == channelId
        else -> false
    }
}

/** A folder logo file name that might belong to this channel. Compared loosely, without punctuation. */
fun logoMatchKey(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

/** Adds [id] to the comma-separated list, or removes it when it is already there. */
fun toggleCsvId(raw: String, id: String): String {
    if (id.isBlank()) return raw
    val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    if (!ids.remove(id)) ids.add(id)
    return ids.joinToString(",")
}

/**
 * Moves one channel earlier or later and saves the whole order. A negative [delta] moves it
 * toward the start. Channels that were never ordered keep the order they have now, so the first
 * move does not jump the rest of the list.
 */
fun moveInOrder(channels: List<LiveChannel>, order: String, id: String, delta: Int): String {
    if (delta == 0 || id.isBlank()) return order
    val ids = orderChannels(channels, order).map { it.id }.toMutableList()
    val from = ids.indexOf(id)
    if (from < 0) return order
    val to = (from + delta).coerceIn(0, ids.lastIndex)
    if (from == to) return ids.joinToString(",")
    val moved = ids.removeAt(from)
    ids.add(to, moved)
    return ids.joinToString(",")
}

/** Remembers which guide id belongs to a channel. A blank id clears the hand-picked match. */
fun assignGuide(raw: String, channelId: String, epgId: String): String {
    if (channelId.isBlank()) return raw
    val map = linkedMapOf<String, String>()
    for (piece in raw.split(',', '\n')) {
        val parts = piece.split('=', limit = 2)
        if (parts.size < 2) continue
        val key = parts[0].trim()
        val value = parts[1].trim()
        if (key.isNotEmpty() && value.isNotEmpty() && key != channelId) map[key] = value
    }
    val next = epgId.trim()
    if (next.isNotEmpty()) map[channelId] = next
    return map.entries.joinToString(",") { (key, value) -> "$key=$value" }
}

/**
 * Puts a channel in a named group, or takes it out. An empty group is dropped.
 * A group name cannot contain a new line or an equals sign, because those separate the saved text.
 */
fun editCustomGroup(raw: String, group: String, channelId: String, include: Boolean): String {
    val name = group.trim()
    if (name.isEmpty() || channelId.isBlank() || name.contains('\n') || name.contains('=')) return raw
    val map = linkedMapOf<String, MutableList<String>>()
    for (line in raw.split('\n')) {
        val parts = line.split('=', limit = 2)
        if (parts.size < 2) continue
        val groupName = parts[0].trim()
        if (groupName.isEmpty()) continue
        map[groupName] = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }
    val members = map.getOrPut(name) { mutableListOf() }
    if (include) {
        if (channelId !in members) members.add(channelId)
    } else {
        members.remove(channelId)
    }
    if (members.isEmpty()) map.remove(name)
    return map.entries.joinToString("\n") { (groupName, ids) -> "$groupName=${ids.joinToString(",")}" }
}

/** Channels whose name, group, or number matches. Fewer than two letters matches nothing, so the list stays short. */
fun channelMatches(channels: List<LiveChannel>, query: String, limit: Int = 40): List<LiveChannel> {
    val q = query.trim()
    if (q.length < 2 && q.toIntOrNull() == null) return emptyList()
    val number = q.toIntOrNull()
    return channels.filter { ch ->
        (number != null && ch.number == number) ||
            ch.name.contains(q, ignoreCase = true) ||
            ch.group.contains(q, ignoreCase = true)
    }.take(limit)
}
