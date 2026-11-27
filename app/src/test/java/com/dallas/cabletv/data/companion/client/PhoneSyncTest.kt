package com.dallas.cabletv.data.companion.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSyncTest {

    private fun file(vararg sections: Pair<String, JSONObject>): JSONObject {
        val s = JSONObject()
        sections.forEach { (k, v) -> s.put(k, v) }
        return JSONObject().put("format", 1).put("source_tv", "Living Room").put("sections", s)
    }

    @Test
    fun sectionKeysListWhatTheFileHolds() {
        val data = file("logins" to JSONObject(), "lock" to JSONObject())
        assertEquals(setOf("logins", "lock"), syncSectionKeys(data).toSet())
        assertTrue(syncSectionKeys(JSONObject()).isEmpty())
    }

    @Test
    fun fileWithSectionsKeepsOnlyChosenSectionsItHolds() {
        val data = file("logins" to JSONObject().put("xtream_user", "a"), "lock" to JSONObject().put("pin_record", "x"))
        val out = syncFileWithSections(data, listOf("lock", "favorites"))
        assertEquals(listOf("lock"), syncSectionKeys(out))
        assertEquals("x", out.getJSONObject("sections").getJSONObject("lock").getString("pin_record"))
        assertEquals("Living Room", out.getString("source_tv"))
    }

    @Test
    fun mergeReplacesExportedSectionsAndKeepsTheRest() {
        val old = file(
            "logins" to JSONObject().put("xtream_user", "old"),
            "lock" to JSONObject().put("pin_record", "keep")
        )
        val fresh = file(
            "logins" to JSONObject().put("xtream_user", "new"),
            "channels" to JSONObject().put("hidden_filters", "UK")
        )
        val merged = mergedPhoneCopy(old, fresh).getJSONObject("sections")
        assertEquals("new", merged.getJSONObject("logins").getString("xtream_user"))
        assertEquals("UK", merged.getJSONObject("channels").getString("hidden_filters"))
        assertEquals("keep", merged.getJSONObject("lock").getString("pin_record"))
        assertEquals(setOf("logins", "channels", "lock"), merged.keys().asSequence().toSet())
    }

    @Test
    fun mergeWithNoOlderCopyIsTheExport() {
        val fresh = file("favorites" to JSONObject())
        val merged = mergedPhoneCopy(null, fresh)
        assertEquals(listOf("favorites"), syncSectionKeys(merged))
        assertFalse(merged.getJSONObject("sections").has("logins"))
    }
}
