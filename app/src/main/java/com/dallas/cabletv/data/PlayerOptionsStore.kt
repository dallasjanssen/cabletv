package com.dallas.cabletv.data

import android.content.Context

/**
 * Picture and guide choices. Nothing here is a password, so it stays in ordinary preferences
 * rather than the encrypted login store.
 */
class PlayerOptionsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PlayerOptions = PlayerOptions(
        bufferPreset = prefs.getString(K_BUFFER, BUFFER_AUTO) ?: BUFFER_AUTO,
        softwareVideoDecoder = prefs.getBoolean(K_SOFT_VIDEO, false),
        softwareAudioDecoder = prefs.getBoolean(K_SOFT_AUDIO, false),
        audioPassthrough = prefs.getBoolean(K_PASSTHROUGH, false),
        tunneledPlayback = prefs.getBoolean(K_TUNNEL, false),
        amlogicFix = prefs.getBoolean(K_AMLOGIC, false),
        autoFrameRate = prefs.getBoolean(K_AFR, false),
        afrFilmRatesOnly = prefs.getBoolean(K_AFR_FILM, true),
        switchResolution = prefs.getBoolean(K_SWITCH_RES, false),
        audioOffsetMs = prefs.getInt(K_AUDIO_OFFSET, 0),
        userAgent = prefs.getString(K_UA, "").orEmpty(),
        udpProxy = prefs.getString(K_UDP, "").orEmpty(),
        zapDelayMs = prefs.getInt(K_ZAP, 250),
        blackFrameOnZap = prefs.getBoolean(K_BLACK, false),
        maxConnections = prefs.getInt(K_CONN, 0),
        epgOffsetMinutes = prefs.getInt(K_EPG_OFFSET, 0),
        epgPastDays = prefs.getInt(K_EPG_DAYS, 0),
        epgUpdateHours = prefs.getInt(K_EPG_HOURS, 0),
        epgUpdateOnStart = prefs.getBoolean(K_EPG_START, true),
        openLastChannel = prefs.getBoolean(K_LAST, true),
        showChannelNumbers = prefs.getBoolean(K_NUMBERS, true),
        showChannelNames = prefs.getBoolean(K_NAMES, true),
        showCatchupMark = prefs.getBoolean(K_CATCHUP, true),
        twoLineNames = prefs.getBoolean(K_TWO_NAMES, false),
        twoLineTitles = prefs.getBoolean(K_TWO_TITLES, false),
        namePrefixes = prefs.getString(K_PREFIXES, "").orEmpty(),
        nameSuffixes = prefs.getString(K_SUFFIXES, "").orEmpty(),
        logoFolder = prefs.getString(K_LOGO_FOLDER, "").orEmpty(),
        logoPriority = prefs.getString(K_LOGO_PRIORITY, LOGO_PLAYLIST) ?: LOGO_PLAYLIST,
        hiddenChannelIds = prefs.getString(K_HIDDEN_IDS, "").orEmpty(),
        blockedChannelIds = prefs.getString(K_BLOCKED_IDS, "").orEmpty(),
        customGroups = prefs.getString(K_GROUPS, "").orEmpty(),
        channelOrder = prefs.getString(K_ORDER, "").orEmpty(),
        epgAssignments = prefs.getString(K_EPG_ASSIGN, "").orEmpty(),
        doubleBackToExit = prefs.getBoolean(K_DOUBLE_BACK, false),
        infoTimeoutSec = prefs.getInt(K_INFO_SEC, 5),
        infoTransparency = prefs.getInt(K_INFO_ALPHA, 0),
        sleepTimerMinutes = prefs.getInt(K_SLEEP, 0),
        pipOnHome = prefs.getBoolean(K_PIP, false),
        skipBackSec = prefs.getInt(K_SKIP_BACK, 10),
        skipForwardSec = prefs.getInt(K_SKIP_FWD, 30),
        barBackSec = prefs.getInt(K_BAR_BACK, 10),
        barForwardSec = prefs.getInt(K_BAR_FWD, 30),
        multiviewSlots = prefs.getInt(K_SLOTS, 2).coerceIn(2, 4),
        remoteMap = prefs.getString(K_REMOTE, "").orEmpty(),
        recordPadStartMin = prefs.getInt(K_PAD_START, 0),
        recordPadEndMin = prefs.getInt(K_PAD_END, 0),
        recordingsFolder = prefs.getString(K_REC_FOLDER, "").orEmpty(),
        remindLeadMin = prefs.getInt(K_REMIND_LEAD, 1),
        reminderPopupSec = prefs.getInt(K_REMIND_POP, 30),
        reminderWakesDevice = prefs.getBoolean(K_REMIND_WAKE, false),
        pinGrace = prefs.getString(K_PIN_GRACE, PIN_GRACE_NONE) ?: PIN_GRACE_NONE,
        sortByWatchTime = prefs.getBoolean(K_WATCH_SORT, false)
    )

    fun save(o: PlayerOptions) {
        prefs.edit()
            .putString(K_BUFFER, o.bufferPreset)
            .putBoolean(K_SOFT_VIDEO, o.softwareVideoDecoder)
            .putBoolean(K_SOFT_AUDIO, o.softwareAudioDecoder)
            .putBoolean(K_PASSTHROUGH, o.audioPassthrough)
            .putBoolean(K_TUNNEL, o.tunneledPlayback)
            .putBoolean(K_AMLOGIC, o.amlogicFix)
            .putBoolean(K_AFR, o.autoFrameRate)
            .putBoolean(K_AFR_FILM, o.afrFilmRatesOnly)
            .putBoolean(K_SWITCH_RES, o.switchResolution)
            .putInt(K_AUDIO_OFFSET, o.audioOffsetMs.coerceIn(-2_000, 2_000))
            .putString(K_UA, o.userAgent.trim())
            .putString(K_UDP, o.udpProxy.trim())
            .putInt(K_ZAP, o.zapDelayMs.coerceIn(0, 5_000))
            .putBoolean(K_BLACK, o.blackFrameOnZap)
            .putInt(K_CONN, o.maxConnections.coerceIn(0, 8))
            .putInt(K_EPG_OFFSET, o.epgOffsetMinutes.coerceIn(-720, 720))
            .putInt(K_EPG_DAYS, o.epgPastDays.coerceIn(0, 14))
            .putInt(K_EPG_HOURS, o.epgUpdateHours.coerceIn(0, 168))
            .putBoolean(K_EPG_START, o.epgUpdateOnStart)
            .putBoolean(K_LAST, o.openLastChannel)
            .putBoolean(K_NUMBERS, o.showChannelNumbers)
            .putBoolean(K_NAMES, o.showChannelNames)
            .putBoolean(K_CATCHUP, o.showCatchupMark)
            .putBoolean(K_TWO_NAMES, o.twoLineNames)
            .putBoolean(K_TWO_TITLES, o.twoLineTitles)
            .putString(K_PREFIXES, o.namePrefixes)
            .putString(K_SUFFIXES, o.nameSuffixes)
            .putString(K_LOGO_FOLDER, o.logoFolder)
            .putString(K_LOGO_PRIORITY, o.logoPriority)
            .putString(K_HIDDEN_IDS, o.hiddenChannelIds)
            .putString(K_BLOCKED_IDS, o.blockedChannelIds)
            .putString(K_GROUPS, o.customGroups)
            .putString(K_ORDER, o.channelOrder)
            .putString(K_EPG_ASSIGN, o.epgAssignments)
            .putBoolean(K_DOUBLE_BACK, o.doubleBackToExit)
            .putInt(K_INFO_SEC, o.infoTimeoutSec.coerceIn(1, 30))
            .putInt(K_INFO_ALPHA, o.infoTransparency.coerceIn(0, 80))
            .putInt(K_SLEEP, o.sleepTimerMinutes.coerceIn(0, 240))
            .putBoolean(K_PIP, o.pipOnHome)
            .putInt(K_SKIP_BACK, o.skipBackSec.coerceIn(5, 600))
            .putInt(K_SKIP_FWD, o.skipForwardSec.coerceIn(5, 600))
            .putInt(K_BAR_BACK, o.barBackSec.coerceIn(5, 600))
            .putInt(K_BAR_FWD, o.barForwardSec.coerceIn(5, 600))
            .putInt(K_SLOTS, o.multiviewSlots.coerceIn(2, 4))
            .putString(K_REMOTE, o.remoteMap)
            .putInt(K_PAD_START, o.recordPadStartMin.coerceIn(0, 30))
            .putInt(K_PAD_END, o.recordPadEndMin.coerceIn(0, 60))
            .putString(K_REC_FOLDER, o.recordingsFolder)
            .putInt(K_REMIND_LEAD, o.remindLeadMin.coerceIn(1, 120))
            .putInt(K_REMIND_POP, o.reminderPopupSec.coerceIn(5, 120))
            .putBoolean(K_REMIND_WAKE, o.reminderWakesDevice)
            .putString(K_PIN_GRACE, o.pinGrace)
            .putBoolean(K_WATCH_SORT, o.sortByWatchTime)
            .apply()
    }

    private companion object {
        const val PREFS = "cabletv_player_options"
        const val K_BUFFER = "buffer"
        const val K_SOFT_VIDEO = "soft_video"
        const val K_SOFT_AUDIO = "soft_audio"
        const val K_PASSTHROUGH = "passthrough"
        const val K_TUNNEL = "tunnel"
        const val K_AMLOGIC = "amlogic"
        const val K_AFR = "afr"
        const val K_AFR_FILM = "afr_film"
        const val K_SWITCH_RES = "switch_res"
        const val K_AUDIO_OFFSET = "audio_offset"
        const val K_UA = "ua"
        const val K_UDP = "udp"
        const val K_ZAP = "zap"
        const val K_BLACK = "black"
        const val K_CONN = "conn"
        const val K_EPG_OFFSET = "epg_offset"
        const val K_EPG_DAYS = "epg_days"
        const val K_EPG_HOURS = "epg_hours"
        const val K_EPG_START = "epg_start"
        const val K_LAST = "last"
        const val K_NUMBERS = "numbers"
        const val K_NAMES = "names"
        const val K_CATCHUP = "catchup"
        const val K_TWO_NAMES = "two_names"
        const val K_TWO_TITLES = "two_titles"
        const val K_PREFIXES = "prefixes"
        const val K_SUFFIXES = "suffixes"
        const val K_LOGO_FOLDER = "logo_folder"
        const val K_LOGO_PRIORITY = "logo_priority"
        const val K_HIDDEN_IDS = "hidden_ids"
        const val K_BLOCKED_IDS = "blocked_ids"
        const val K_GROUPS = "groups"
        const val K_ORDER = "order"
        const val K_EPG_ASSIGN = "epg_assign"
        const val K_DOUBLE_BACK = "double_back"
        const val K_INFO_SEC = "info_sec"
        const val K_INFO_ALPHA = "info_alpha"
        const val K_SLEEP = "sleep"
        const val K_PIP = "pip"
        const val K_SKIP_BACK = "skip_back"
        const val K_SKIP_FWD = "skip_fwd"
        const val K_BAR_BACK = "bar_back"
        const val K_BAR_FWD = "bar_fwd"
        const val K_SLOTS = "slots"
        const val K_REMOTE = "remote"
        const val K_PAD_START = "pad_start"
        const val K_PAD_END = "pad_end"
        const val K_REC_FOLDER = "rec_folder"
        const val K_REMIND_LEAD = "remind_lead"
        const val K_REMIND_POP = "remind_pop"
        const val K_REMIND_WAKE = "remind_wake"
        const val K_PIN_GRACE = "pin_grace"
        const val K_WATCH_SORT = "watch_sort"
    }
}
