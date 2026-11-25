package com.dallas.cabletv.ui.companion

import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.data.companion.client.SyncConflict
import com.dallas.cabletv.data.companion.client.SyncObj
import com.dallas.cabletv.data.companion.client.findConflicts
import com.dallas.cabletv.data.companion.client.jsonToSyncNode
import com.dallas.cabletv.data.companion.client.mergeSync
import com.dallas.cabletv.data.companion.client.toSyncJson
import org.json.JSONObject

/**
 * A sync waiting for the person's choices. [data] is the sync file to copy, and [current] is what the
 * target TV has for the same sections. Settings that differ are [conflicts].
 */
class SyncPlan private constructor(
    val title: String,
    val target: PairedTv,
    private val data: JSONObject,
    private val copy: SyncObj,
    private val tv: SyncObj,
    val conflicts: List<SyncConflict>
) {
    /** The sync file to send. Every setting comes from the copy, except the ones whose ids are in [keepTvIds]. */
    fun merged(keepTvIds: Set<String>): JSONObject {
        val keep = conflicts.filter { it.id in keepTvIds }.map { it.path }.toSet()
        val sections = mergeSync(copy, tv, keep).toSyncJson()
        return JSONObject()
            .put("format", data.optInt("format", 1))
            .put("source_tv", data.optString("source_tv"))
            .put("sections", sections)
    }

    companion object {
        fun build(title: String, target: PairedTv, data: JSONObject, current: JSONObject): SyncPlan {
            val copy = jsonToSyncNode(data.optJSONObject("sections") ?: JSONObject()) as SyncObj
            val tv = jsonToSyncNode(current.optJSONObject("sections") ?: JSONObject()) as SyncObj
            return SyncPlan(title, target, data, copy, tv, findConflicts(copy, tv))
        }
    }
}
