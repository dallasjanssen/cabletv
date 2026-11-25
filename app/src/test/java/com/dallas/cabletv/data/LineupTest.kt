package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupTest {

    private val lineupCsv = """
        number,alt_number,name,genre,package
        202,,CNN,News,Main
        206,,ESPN,Sports,Main
        207,,ESPNEWS,Sports,Main
        1,,"HISTORY Channel, The",Documentary,Main
        205,920,Sports Mix,Sports,Main
        922,205,Sports Mix 2,Sports,Main
        913,,Weather Mix (2-Tile),News,Main
        913,,Weather Mix (3-Tile),News,Main
        4010,,20/20,News,Main
        105,,DIRECTV 4K Live 1,Entertainment,Main
        2095,,Arirang TV,International,Korean add-on
        2134,,TV Globo,International,Main
        400,924,Deportes Mix,Sports,Main
    """.trimIndent()

    private val lineup = parseLineupCsv(lineupCsv).channels

    private fun ch(id: String, name: String, group: String = "Other") =
        LiveChannel(id = id, number = 9999, name = name, logo = null, group = group, streamUrl = "u", epgId = null)

    private fun doc(
        overrides: Map<String, Int> = emptyMap(),
        assignments: Map<String, LineupAssignment> = emptyMap()
    ) = LineupDoc(lineup = lineup, overrides = overrides, assignments = assignments)

    // Parsing

    @Test
    fun parsesRowsAndSkipsHeaderAndBadRows() {
        val parsed = parseLineupCsv(lineupCsv + "\n,,No number,News,Main\n300,,,News,Main")
        assertEquals(lineup.size, parsed.channels.size)
        assertEquals(2, parsed.skipped)
    }

    @Test
    fun quotedCommaStaysInTheName() {
        val hist = lineup.first { it.number == 1 }
        assertEquals("HISTORY Channel, The", hist.name)
        assertEquals("Documentary", hist.genre)
    }

    @Test
    fun readsAlternateNumber() {
        val mix = lineup.first { it.name == "Sports Mix" }
        assertEquals(205, mix.number)
        assertEquals(920, mix.altNumber)
        assertNull(lineup.first { it.name == "CNN" }.altNumber)
    }

    @Test
    fun csvFieldsHandlesDoubledQuotes() {
        assertEquals(listOf("a\"b", "c"), csvFields("\"a\"\"b\",c"))
    }

    // Name matching

    @Test
    fun normalizeDropsPrefixAccentsQualityAndPunctuation() {
        assertEquals("bbc one", normalizeChannelName("UK| BBC One HD"))
        assertEquals("centroamerica tv", normalizeChannelName("Centroamérica TV"))
        assertEquals("espn", normalizeChannelName("ESPN FHD"))
        assertEquals("heroes and icons", normalizeChannelName("Heroes & Icons (H&I)"))
        assertEquals("history channel", normalizeChannelName("The History Channel"))
        assertEquals("history channel", normalizeChannelName("HISTORY Channel, The"))
    }

    @Test
    fun nameMatchIgnoresQualityTags() {
        val index = LineupIndex(lineup)
        assertEquals(206, index.match("US| ESPN HD")?.number)
        assertEquals(207, index.match("ESPNEWS")?.number)
    }

    @Test
    fun unknownNameDoesNotMatch() {
        assertNull(LineupIndex(lineup).match("Some Local Access Channel"))
    }

    // Placement and numbers

    @Test
    fun nameMatchNumbersTheChannelAndKeepsItsId() {
        val out = applyLineup(listOf(ch("p1", "ESPN HD", "Sports")), doc())
        assertEquals(1, out.size)
        assertEquals("p1", out[0].id)
        assertEquals(206, out[0].number)
    }

    @Test
    fun overrideBeatsNameMatch() {
        val out = applyLineup(listOf(ch("p1", "ESPN")), doc(overrides = mapOf("p1" to 207)))
        assertEquals(207, out[0].number)
    }

    @Test
    fun overrideToMissingNumberIsIgnored() {
        val out = applyLineup(listOf(ch("p1", "ESPN")), doc(overrides = mapOf("p1" to 99999)))
        assertEquals(206, out[0].number)
    }

    @Test
    fun modelAssignmentPlacesAChannelNameDoesNotMatch() {
        val assignment = LineupAssignment("p2", "Newsy Channel", 202, "News", "model", 0.95)
        val out = applyLineup(listOf(ch("p2", "Newsy Channel")), doc(assignments = mapOf("p2" to assignment)))
        assertEquals(202, out[0].number)
    }

    @Test
    fun hdCopyWinsTheNumberOverSdCopy() {
        val channels = listOf(ch("sd", "ESPN SD"), ch("hd", "ESPN HD"))
        val out = applyLineup(channels, doc()).associateBy { it.id }
        assertEquals(206, out.getValue("hd").number)
        assertTrue(out.getValue("sd").number >= 1000)
    }

    @Test
    fun earlierChannelWinsWhenNeitherIsHdOrSd() {
        val channels = listOf(ch("first", "ESPN"), ch("second", "ESPN Plus"))
        val out = applyLineup(channels, doc()).associateBy { it.id }
        assertEquals(206, out.getValue("first").number)
        assertTrue(out.getValue("second").number >= 1000)
    }

    @Test
    fun unmatchedChannelGoesToItsGenreBlockByGroup() {
        val out = applyLineup(listOf(ch("k", "Local Access 12", "Sports Extra")), doc())
        assertEquals(1050, out[0].number) // Sports is the second genre, so its block starts at 1050
    }

    @Test
    fun genreBlocksDoNotCollideWithLineupNumbers() {
        val channels = (1..60).map { ch("x$it", "Unknown $it", "Sports") }
        val out = applyLineup(channels, doc())
        val numbers = out.map { it.number }
        assertEquals(numbers.size, numbers.toSet().size)
        assertTrue(numbers.none { it in lineup.map { l -> l.number } })
    }

    @Test
    fun blockOverflowContinuesWithoutRepeats() {
        val channels = (1..80).map { ch("s$it", "Unknown $it", "Movies") }
        val numbers = applyLineup(channels, doc()).map { it.number }
        assertEquals(80, numbers.toSet().size)
        assertTrue(numbers.all { it >= 1100 })
    }

    @Test
    fun applyingTwiceGivesTheSameNumbers() {
        val channels = listOf(ch("a", "ESPN"), ch("b", "Mystery"), ch("c", "Other Mystery", "News"))
        val first = applyLineup(channels, doc()).associate { it.id to it.number }
        val second = applyLineup(channels, doc()).associate { it.id to it.number }
        assertEquals(first, second)
    }

    @Test
    fun emptyLineupLeavesChannelsAlone() {
        val channels = listOf(ch("a", "ESPN"))
        assertEquals(channels, applyLineup(channels, LineupDoc()))
    }

    @Test
    fun guessGenreFromProviderCategory() {
        assertEquals("Sports", guessGenre("US Sports HD"))
        assertEquals("Spanish", guessGenre("Deportes en Español"))
        assertEquals("Other", guessGenre("Random"))
    }

    // Model pass

    @Test
    fun channelsNeedingModelSkipsMatchedOverriddenAndAssigned() {
        val channels = listOf(
            ch("matched", "ESPN"),
            ch("picked", "Unknown A"),
            ch("assigned", "Unknown B"),
            ch("unknown", "Unknown C")
        )
        val d = doc(
            overrides = mapOf("picked" to 202),
            assignments = mapOf("assigned" to LineupAssignment("assigned", "Unknown B", null, "Other", "model", 0.2))
        )
        assertEquals(listOf("unknown"), channelsNeedingModel(channels, d).map { it.id })
    }

    @Test
    fun parsesModelAnswerAndKeepsOnlyTrustedNumbers() {
        val text = """
            Here you go:
            [
              {"id": "a", "number": 202, "genre": "News", "confidence": 0.93},
              {"id": "b", "number": 202, "genre": "News", "confidence": 0.4},
              {"id": "c", "number": 77777, "genre": "Sports", "confidence": 0.99},
              {"id": "d", "number": null, "genre": "Made Up", "confidence": 0.1},
              {"id": "zzz", "number": 206, "genre": "Sports", "confidence": 0.99}
            ]
        """.trimIndent()
        val answers = parseModelAnswers(text, setOf("a", "b", "c", "d"), lineup.map { it.number }.toSet())
            .associateBy { it.providerId }
        assertEquals(202, answers.getValue("a").number)
        assertNull(answers.getValue("b").number)
        assertNull(answers.getValue("c").number)
        assertNull(answers.getValue("d").number)
        assertEquals("Other", answers.getValue("d").genre)
        assertFalse(answers.containsKey("zzz"))
    }

    @Test
    fun promptListsLineupAndAsksForJsonOnly() {
        val prompt = lineupPrompt(listOf(ch("a", "Unknown A", "News")), lineup)
        assertTrue(prompt.contains("202 | CNN | News"))
        assertTrue(prompt.contains("a | Unknown A | News"))
        assertTrue(prompt.contains("Only use a number from LINEUP"))
    }
}

class LineupPassTest {

    private val csv = """
        number,alt_number,name,genre,package
        202,,CNN,News,Main
        206,,ESPN,Sports,Main
    """.trimIndent()

    private fun ch(id: String, name: String) =
        LiveChannel(id = id, number = 1, name = name, logo = null, group = "News", streamUrl = "u", epgId = null)

    @Test
    fun importingANewLineupDropsOldAssignmentsAndKeepsOverrides() {
        val old = LineupDoc(
            revision = 3,
            assignments = mapOf("a" to LineupAssignment("a", "A", 202, "News", "model", 0.9)),
            overrides = mapOf("b" to 206)
        )
        val updated = withLineupCsv(old, csv, now = 42L)
        assertEquals(4, updated.revision)
        assertEquals(42L, updated.builtAtMs)
        assertEquals(2, updated.lineup.size)
        assertTrue(updated.assignments.isEmpty())
        assertEquals(mapOf("b" to 206), updated.overrides)
    }

    @Test
    fun modelPassAsksOnlyForUnplacedChannelsAndStoresAnswers() {
        val channels = listOf(ch("a", "Newsy Channel"), ch("b", "ESPN"), ch("c", "Odd Channel"))
        val doc = withLineupCsv(LineupDoc(), csv, now = 1L)
        var asked = 0
        val out = runModelPass(channels, doc, now = 2L) { prompt ->
            asked++
            assertTrue(prompt.contains("a | Newsy Channel"))
            assertFalse(prompt.contains("b | ESPN"))
            """[{"id":"a","number":202,"genre":"News","confidence":0.9},
               {"id":"c","number":null,"genre":"Shopping","confidence":0.3}]"""
        }
        assertEquals(1, asked)
        assertEquals(202, out.assignments.getValue("a").number)
        assertNull(out.assignments.getValue("c").number)
        assertEquals("Shopping", out.assignments.getValue("c").genre)
        assertEquals(2, out.revision)
    }

    @Test
    fun modelPassWithNothingPendingAsksNothing() {
        val channels = listOf(ch("b", "ESPN"))
        val doc = withLineupCsv(LineupDoc(), csv, now = 1L)
        val out = runModelPass(channels, doc, now = 2L) { error("should not be called") }
        assertEquals(doc, out)
    }

    @Test
    fun failedBatchThrowsSoNothingIsSaved() {
        val channels = listOf(ch("a", "Newsy Channel"))
        val doc = withLineupCsv(LineupDoc(), csv, now = 1L)
        var threw = false
        try {
            runModelPass(channels, doc, now = 2L) { throw IllegalStateException("network") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unpromptedIdsAreIgnoredAndAskedAgainNextTime() {
        val channels = listOf(ch("a", "Newsy Channel"))
        val doc = withLineupCsv(LineupDoc(), csv, now = 1L)
        val out = runModelPass(channels, doc, now = 2L) { """[{"id":"zzz","number":202,"genre":"News","confidence":0.9}]""" }
        assertTrue(out.assignments.isEmpty())
        assertEquals(1, channelsNeedingModel(channels, out).size)
    }
}
