package com.dallas.cabletv.data.companion.client

/*
 * Compares the setup a sync would copy with what the target TV already has, and merges the
 * person's choices. Plain logic with no JSON library, so it can be tested on its own. The JSON
 * conversion is in SyncNodeJson.kt.
 */

/** One node of a sync file: a value, an object, or a list. */
sealed interface SyncNode

data class SyncLeaf(val value: Any?) : SyncNode

data class SyncObj(val fields: Map<String, SyncNode>) : SyncNode

data class SyncList(val items: List<SyncNode>) : SyncNode

/**
 * A setting that differs between the copy and the target TV. [path] is where it sits in the sync
 * file. Profiles appear as "@Name" segments, so a profile's setting keeps its place in the list.
 */
data class SyncConflict(
    val path: List<String>,
    val label: String,
    val sensitive: Boolean,
    val copyText: String,
    val tvText: String
) {
    val id: String get() = path.joinToString("/")
}

/** Things that differ on every TV and carry no choice: revision stamps. They are not compared. */
private val ignoredKeys = setOf("revision", "builtAtMs")

/** Lists that are compared as one value, not field by field. Each one is large or changes as a whole. */
private val wholeKeys = setOf("lineup", "assignments", "overrides", "genreBlocks")

private val sensitiveWords = listOf("pass", "token", "key", "pin_record")

private val labels = mapOf(
    "logins.xtream_server" to "Live TV server",
    "logins.xtream_user" to "Live TV username",
    "logins.xtream_pass" to "Live TV password",
    "logins.m3u_url" to "Playlist address",
    "logins.epg_url" to "Guide address",
    "logins.stalker_url" to "Stalker portal",
    "logins.stalker_mac" to "Stalker MAC address",
    "logins.jellyfin_server" to "Jellyfin server",
    "logins.jellyfin_token" to "Jellyfin sign-in",
    "logins.jellyfin_user_id" to "Jellyfin user",
    "logins.subscription_expiry" to "Subscription expiry",
    "channels.hidden_filters" to "Hidden channels",
    "channels.profiles.hidden_filters" to "Hidden channels",
    "channels.strip_prefixes" to "Show names without country prefixes",
    "favorites.profiles.favorites" to "Favorites",
    "lock.pin_record" to "Parental PIN",
    "lock.locked_filters" to "Locked channels",
    "lineup.lineup" to "Channel lineup",
    "lineup.assignments" to "Lineup placements",
    "lineup.overrides" to "Lineup picks by hand",
    "lineup.genreBlocks" to "Lineup genre blocks",
    "lineup.openRouterKey" to "OpenRouter key",
    "lineup.openRouterModel" to "OpenRouter model"
)

/** The profile name in a path, such as "Kids" for channels > profiles > @Kids > hidden_filters. */
private fun profileOf(path: List<String>): String? = path.firstOrNull { it.startsWith("@") }?.drop(1)

private fun nameOf(node: SyncNode): String? = (node as? SyncObj)?.fields?.get("name")?.let { (it as? SyncLeaf)?.value?.toString() }

private fun keyed(list: SyncList): Boolean {
    if (list.items.isEmpty()) return false
    val names = list.items.map { nameOf(it) ?: return false }
    return names.toSet().size == names.size
}

/** Every leaf and list in [root], by path. A profile's fields sit under "@Name". */
internal fun flattenSync(root: SyncNode): Map<List<String>, SyncNode> {
    val out = LinkedHashMap<List<String>, SyncNode>()
    fun walk(node: SyncNode, path: List<String>) {
        when (node) {
            is SyncLeaf, is SyncList -> out[path] = node
            is SyncObj -> for ((key, value) in node.fields) {
                if (path.isNotEmpty() && key in ignoredKeys) continue
                val child = path + key
                when {
                    path.isNotEmpty() && key in wholeKeys -> out[child] = value
                    value is SyncObj -> walk(value, child)
                    value is SyncList && key == "profiles" && keyed(value) -> for (item in value.items) {
                        val name = nameOf(item) ?: continue
                        val without = (item as SyncObj).fields - "name"
                        walk(SyncObj(without), child + "@$name")
                    }
                    else -> walk(value, child)
                }
            }
        }
    }
    walk(root, emptyList())
    return out
}

internal fun reprSync(node: SyncNode): String = when (node) {
    is SyncLeaf -> node.value?.toString() ?: ""
    is SyncList -> node.items.joinToString(",", "[", "]") { reprSync(it) }
    is SyncObj -> node.fields.entries.joinToString(",", "{", "}") { "${it.key}:${reprSync(it.value)}" }
}

private fun isSensitive(path: List<String>): Boolean {
    val last = path.lastOrNull()?.lowercase() ?: return false
    return sensitiveWords.any { last.contains(it) }
}

/** A name for a setting, such as "Hidden channels (Kids)". */
internal fun labelForPath(path: List<String>): String {
    val base = path.filter { !it.startsWith("@") }.joinToString(".")
    val label = labels[base] ?: base
    val who = profileOf(path)
    return if (who != null) "$label ($who)" else label
}

private fun textFor(node: SyncNode, sensitive: Boolean): String = when {
    sensitive -> "(hidden)"
    node is SyncList -> "${node.items.size} items"
    node is SyncLeaf && (node.value == null || node.value.toString().isBlank()) -> "(empty)"
    node is SyncLeaf -> node.value.toString().take(80)
    else -> "(details)"
}

/**
 * Settings present on both sides with different values. Settings only in the copy, or only on the
 * TV, are not conflicts: sync adds the first and never removes the second.
 */
fun findConflicts(copy: SyncNode, tv: SyncNode): List<SyncConflict> {
    val onTv = flattenSync(tv)
    return flattenSync(copy).mapNotNull { (path, copyNode) ->
        val tvNode = onTv[path] ?: return@mapNotNull null
        if (reprSync(copyNode) == reprSync(tvNode)) return@mapNotNull null
        val sensitive = isSensitive(path)
        SyncConflict(
            path = path,
            label = labelForPath(path),
            sensitive = sensitive,
            copyText = textFor(copyNode, sensitive),
            tvText = textFor(tvNode, sensitive)
        )
    }
}

/** The value at [path] in [root], or null when there is none. */
fun getSyncAt(root: SyncNode, path: List<String>): SyncNode? {
    var cur: SyncNode = root
    for (seg in path) {
        cur = when {
            seg.startsWith("@") && cur is SyncList -> cur.items.firstOrNull { nameOf(it) == seg.drop(1) } ?: return null
            cur is SyncObj && !seg.startsWith("@") -> cur.fields[seg] ?: return null
            else -> return null
        }
    }
    return cur
}

/** [root] with the value at [path] replaced by [value]. Anything not on [path] is left as it is. */
fun setSyncAt(root: SyncNode, path: List<String>, value: SyncNode): SyncNode {
    if (path.isEmpty()) return value
    val seg = path.first()
    val rest = path.drop(1)
    return when {
        seg.startsWith("@") && root is SyncList -> {
            val name = seg.drop(1)
            SyncList(root.items.map { if (nameOf(it) == name) setSyncAt(it, rest, value) else it })
        }
        root is SyncObj && !seg.startsWith("@") -> {
            if (rest.isEmpty()) {
                SyncObj(root.fields + (seg to value))
            } else {
                val child = root.fields[seg] ?: return root
                SyncObj(root.fields + (seg to setSyncAt(child, rest, value)))
            }
        }
        else -> root
    }
}

/**
 * [copy] with each path in [keepTv] taken from [tv] instead. Every other setting keeps the copy's value.
 */
fun mergeSync(copy: SyncNode, tv: SyncNode, keepTv: Set<List<String>>): SyncNode =
    keepTv.fold(copy) { acc, path ->
        val tvValue = getSyncAt(tv, path) ?: return@fold acc
        setSyncAt(acc, path, tvValue)
    }
