package com.dallas.cabletv.data.companion.client

import org.json.JSONArray
import org.json.JSONObject

/** A sync file's JSON as a [SyncNode], so the review can compare and merge it. */
fun jsonToSyncNode(value: Any?): SyncNode = when (value) {
    is JSONObject -> SyncObj(value.keys().asSequence().associateWith { jsonToSyncNode(value.opt(it)) })
    is JSONArray -> SyncList((0 until value.length()).map { jsonToSyncNode(value.opt(it)) })
    JSONObject.NULL, null -> SyncLeaf(null)
    else -> SyncLeaf(value)
}

/** The JSON form of a [SyncNode], ready to send to a TV. */
fun SyncNode.toSyncJson(): Any = when (this) {
    is SyncLeaf -> value ?: JSONObject.NULL
    is SyncObj -> JSONObject().also { o -> fields.forEach { (k, v) -> o.put(k, v.toSyncJson()) } }
    is SyncList -> JSONArray().also { a -> items.forEach { a.put(it.toSyncJson()) } }
}
