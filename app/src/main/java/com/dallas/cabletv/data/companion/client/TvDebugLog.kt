package com.dallas.cabletv.data.companion.client

import org.json.JSONObject

/** The TV's debug log as the phone shows it: whether logging is on, and the text recorded. */
data class TvDebugLog(val enabled: Boolean, val log: String) {
    companion object {
        fun fromJson(json: JSONObject): TvDebugLog =
            TvDebugLog(json.optBoolean("enabled", false), json.optString("log", ""))
    }
}
