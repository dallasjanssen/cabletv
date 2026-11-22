package com.dallas.cabletv.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/** Parsed guide: programs keyed by XMLTV channel id, plus display names mapped to those ids. */
class ParsedGuide(
    val programs: Map<String, List<Program>>,
    /** Lower-cased <display-name> to channel id. */
    val displayNames: Map<String, String>
)

/**
 * Streams an XMLTV guide (the format IPTV providers and xmltv.net publish). It keeps only
 * programs that end after [keepAfterMs] and start before [keepUntilMs], so a long guide does
 * not fill memory on a TV box.
 */
object XmlTvParser {

    fun parseFull(input: InputStream, keepAfterMs: Long, keepUntilMs: Long = Long.MAX_VALUE): ParsedGuide {
        val handler = Handler(keepAfterMs, keepUntilMs)
        SAXParserFactory.newInstance().newSAXParser().parse(input, handler)
        return handler.result()
    }

    fun parse(input: InputStream, keepAfterMs: Long, keepUntilMs: Long = Long.MAX_VALUE): Map<String, List<Program>> =
        parseFull(input, keepAfterMs, keepUntilMs).programs

    fun parse(text: String, keepAfterMs: Long): Map<String, List<Program>> =
        parse(text.byteInputStream(Charsets.UTF_8), keepAfterMs)

    /** XMLTV times look like 20260822140000 +0000, or without the offset. */
    internal fun parseTime(raw: String): Long? {
        val value = raw.trim()
        if (value.length < 14) return null
        val digits = value.substring(0, 14)
        val offset = value.substring(14).trim()
        val fmt = formatterFor(offset)
        return runCatching { fmt.parse(digits)?.time }.getOrNull()
    }

    private val formatters = ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

    /** One formatter per offset, reused across programmes instead of created for each one. */
    private fun formatterFor(offset: String): SimpleDateFormat =
        formatters.get().getOrPut(offset) {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(offsetToZone(offset))
                isLenient = false
            }
        }

    /** "+0100" becomes "GMT+01:00". An empty or unknown offset means UTC. */
    private fun offsetToZone(offset: String): String {
        val m = Regex("^([+-])(\\d{2}):?(\\d{2})$").find(offset) ?: return "UTC"
        return "GMT${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}"
    }

    private class Handler(private val keepAfterMs: Long, private val keepUntilMs: Long) : DefaultHandler() {
        private val out = HashMap<String, MutableList<Program>>()
        private val names = HashMap<String, String>()

        private var channelId: String? = null
        private var channelNameDone = true
        private var start: Long? = null
        private var stop: Long? = null
        private var title: String? = null
        private var desc: String? = null
        private var category: String? = null
        private var rating: String? = null
        private var inRating = false
        private var inChannel = false
        private var inDisplayName = false
        private var text = StringBuilder()
        private var inProgramme = false

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            text = StringBuilder()
            when (qName) {
                "channel" -> {
                    inChannel = true
                    channelId = attrs.getValue("id")
                    channelNameDone = false
                }
                "display-name" -> if (inChannel) inDisplayName = true
                "programme" -> {
                    inProgramme = true
                    channelId = attrs.getValue("channel")
                    start = attrs.getValue("start")?.let { parseTime(it) }
                    stop = attrs.getValue("stop")?.let { parseTime(it) }
                    title = null
                    desc = null
                    category = null
                    rating = null
                }
                "rating" -> if (inProgramme) inRating = true
            }
        }

        override fun characters(ch: CharArray, offset: Int, length: Int) {
            text.append(ch, offset, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            text = StringBuilder()
            when (qName) {
                "channel" -> inChannel = false
                "display-name" -> {
                    if (inDisplayName && value.isNotEmpty() && !channelNameDone) {
                        channelId?.let { names.putIfAbsent(value.lowercase(), it) }
                        channelNameDone = true
                    }
                    inDisplayName = false
                }
                "programme" -> {
                    finishProgramme()
                    inProgramme = false
                }
                "title" -> if (inProgramme && title == null && value.isNotEmpty()) title = value
                "desc" -> if (inProgramme && desc == null && value.isNotEmpty()) desc = value
                "category" -> if (inProgramme && category == null && value.isNotEmpty()) category = value
                "value" -> if (inRating && rating == null && value.isNotEmpty()) rating = value
                "rating" -> inRating = false
            }
        }

        private fun finishProgramme() {
            val ch = channelId ?: return
            val s = start ?: return
            val e = stop ?: return
            if (e <= keepAfterMs || e <= s || s > keepUntilMs) return
            val program = Program(
                title = title ?: "Untitled",
                start = s,
                end = e,
                description = desc,
                rating = rating,
                category = category
            )
            out.getOrPut(ch) { mutableListOf() }.add(program)
        }

        fun result(): ParsedGuide = ParsedGuide(
            programs = out.mapValues { (_, list) -> list.sortedBy { it.start } },
            displayNames = names
        )
    }
}
