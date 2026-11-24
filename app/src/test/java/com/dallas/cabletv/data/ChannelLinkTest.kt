package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLinkTest {

    private fun ch(number: Int, name: String, id: String = "id$number") =
        LiveChannel(id = id, number = number, name = name, logo = null, group = "", streamUrl = "u", epgId = null)

    @Test
    fun numberInThePath() {
        assertEquals(ChannelLink.Number(12), parseChannelLink("cabletv://channel/12"))
        assertEquals(ChannelLink.Number(12), parseChannelLink("CableTV://Channel/12/"))
        assertEquals(ChannelLink.Number(7), parseChannelLink("cabletv://tune/007"))
    }

    @Test
    fun nameInThePath() {
        assertEquals(ChannelLink.Name("ESPN"), parseChannelLink("cabletv://channel/ESPN"))
        assertEquals(ChannelLink.Name("Fox News"), parseChannelLink("cabletv://channel/Fox%20News"))
        assertEquals(ChannelLink.Name("Fox News"), parseChannelLink("cabletv://channel/Fox News"))
        // A plus sign in a name stays a plus sign.
        assertEquals(ChannelLink.Name("Disney+"), parseChannelLink("cabletv://channel/Disney+"))
    }

    @Test
    fun queryForms() {
        assertEquals(ChannelLink.Number(12), parseChannelLink("cabletv://channel?number=12"))
        assertEquals(ChannelLink.Name("Fox News"), parseChannelLink("cabletv://channel?name=Fox+News"))
        assertEquals(ChannelLink.Id("1234"), parseChannelLink("cabletv://channel?id=1234"))
        // A number that does not read as one falls through to the next field.
        assertEquals(ChannelLink.Name("ESPN"), parseChannelLink("cabletv://channel?number=abc&name=ESPN"))
    }

    @Test
    fun liveOpensTheLastChannel() {
        assertEquals(ChannelLink.Live, parseChannelLink("cabletv://live"))
        assertEquals(ChannelLink.Live, parseChannelLink("cabletv://live/"))
    }

    @Test
    fun otherLinksAreIgnored() {
        assertNull(parseChannelLink("https://example.com/channel/12"))
        assertNull(parseChannelLink("cabletv://channel"))
        assertNull(parseChannelLink("cabletv://channel/"))
        assertNull(parseChannelLink("cabletv://settings"))
        assertNull(parseChannelLink("not a link"))
        assertNull(parseChannelLink(""))
    }

    @Test
    fun badEscapesAreKeptAsTyped() {
        assertEquals(ChannelLink.Name("100%"), parseChannelLink("cabletv://channel/100%"))
    }

    @Test
    fun numberAndIdMatchExactly() {
        val list = listOf(ch(2, "CBS"), ch(12, "NBC", id = "abc"), ch(120, "PBS"))
        assertEquals("NBC", findLinkedChannel(list, ChannelLink.Number(12))?.name)
        assertNull(findLinkedChannel(list, ChannelLink.Number(5)))
        assertEquals("NBC", findLinkedChannel(list, ChannelLink.Id("abc"))?.name)
        assertEquals("NBC", findLinkedChannel(list, ChannelLink.Id("ABC"))?.name)
    }

    @Test
    fun exactNameWins() {
        val list = listOf(ch(1, "ESPN2"), ch(2, "ESPN HD"), ch(3, "espn"))
        assertEquals(3, findLinkedChannel(list, ChannelLink.Name("ESPN"))?.number)
    }

    @Test
    fun nameAsFirstWordsBeatsALongerWord() {
        val list = listOf(ch(1, "ESPN2"), ch(2, "ESPN HD"))
        assertEquals(2, findLinkedChannel(list, ChannelLink.Name("espn"))?.number)
    }

    @Test
    fun countryPrefixIsIgnored() {
        val list = listOf(ch(1, "UK| BBC One"), ch(2, "US| ESPN"))
        assertEquals(2, findLinkedChannel(list, ChannelLink.Name("ESPN"))?.number)
        assertEquals(1, findLinkedChannel(list, ChannelLink.Name("bbc one"))?.number)
    }

    @Test
    fun accentsAndPunctuationAreIgnored() {
        val list = listOf(ch(1, "Télé-Québec"), ch(2, "A&E HD"))
        assertEquals(1, findLinkedChannel(list, ChannelLink.Name("tele quebec"))?.number)
        assertEquals(2, findLinkedChannel(list, ChannelLink.Name("A&E"))?.number)
    }

    @Test
    fun wordsInTheMiddleBeatAPlainContains() {
        val list = listOf(ch(1, "Foxsportsone"), ch(2, "US Fox Sports 1"))
        assertEquals(2, findLinkedChannel(list, ChannelLink.Name("Fox Sports"))?.number)
    }

    @Test
    fun shorterNameWinsATie() {
        val list = listOf(ch(1, "CNN International"), ch(2, "CNN HD"))
        assertEquals(2, findLinkedChannel(list, ChannelLink.Name("CNN"))?.number)
    }

    @Test
    fun noMatchGivesNull() {
        val list = listOf(ch(1, "CBS"), ch(2, "NBC"))
        assertNull(findLinkedChannel(list, ChannelLink.Name("HBO")))
        assertNull(findLinkedChannel(list, ChannelLink.Name("&&")))
        assertNull(findLinkedChannel(emptyList(), ChannelLink.Name("CBS")))
    }

    @Test
    fun describesTheLinkForMessages() {
        assertEquals("Channel 12", describeChannelLink(ChannelLink.Number(12)))
        assertEquals("ESPN", describeChannelLink(ChannelLink.Name("ESPN")))
    }
}
