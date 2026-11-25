package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol.Sections
import com.dallas.cabletv.data.companion.CompanionProtocol.Types

/** One sync choice: the section key and the words the person sees. */
data class SyncChoice(val key: String, val label: String) {
    companion object {
        val ALL = listOf(
            SyncChoice(Sections.LOGINS, "Logins"),
            SyncChoice(Sections.CHANNELS, "Channel customizations"),
            SyncChoice(Sections.FAVORITES, "Favorites"),
            SyncChoice(Sections.LOCK, "Parental lock"),
            SyncChoice(Sections.LINEUP, "Channel lineup")
        )
    }
}

/**
 * Copies chosen parts from one TV to another: exports from the source, then imports into the
 * target and returns the target's messages. Each side asks for its own PIN when it has one.
 * [askPin] gets the TV's id, and true when the last PIN was wrong.
 */
class SyncRunner(
    private val memory: PinMemory,
    private val askPin: suspend (tvId: String, wasWrong: Boolean) -> String?
) {
    /**
     * Throws [CompanionException] when a TV fails and [PinCancelledException] when the person
     * cancels a PIN dialog. The two TVs must differ and at least one section must be chosen.
     */
    suspend fun copy(
        from: PairedTv,
        fromTv: TvRequester,
        to: PairedTv,
        toTv: TvRequester,
        sections: List<String>
    ): List<String> {
        require(from.id != to.id) { "Choose two different TVs." }
        require(sections.isNotEmpty()) { "Choose what to copy." }
        val exported = withPin(from.id, memory, { askPin(from.id, it) }) { pin ->
            fromTv.request(CompanionRequests.exportSync(pin, sections))
        }
        val data = exported.optJSONObject("data")
            ?: throw CompanionException(CompanionException.Kind.PROTOCOL, "${from.name} sent no data to copy.")
        val saved = withPin(to.id, memory, { askPin(to.id, it) }) { pin ->
            toTv.request(CompanionRequests.importSync(pin, data))
        }
        if (saved.optString("type") != Types.SAVED) {
            throw CompanionException(CompanionException.Kind.PROTOCOL, "${to.name} sent something this app did not expect.")
        }
        return saved.stringList("messages")
    }
}
