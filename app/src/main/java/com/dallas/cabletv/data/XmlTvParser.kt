package com.dallas.cabletv.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/**
 * Streams an XMLTV guide (the format IPTV providers and xmltv.net publish) and
 * keeps only programs that end after [keepAfterMs], so a week-long guide does
 * not fill memory on a TV box.
 */
object XmlTvParser {

    fun parse(input: InputStream, keepAfterMs: Long): Map<String, List<Program>> {
        val handler = Handler(keepAfterMs)
        SAXParserFactory.newInstance().newSAXParser().parse(input, handler)
        return handler.result()
    }

    fun parse(text: String, keepAfterMs: Long): Map<String, List<Program>> =
        parse(text.byteInputStream(Charsets.UTF_8), keepAfterMs)

    /** XMLTV times look like 20260822140000 +0000, or without the offset. */
    internal fun parseTime(raw: String): Long? {
        val value = raw.trim()
        if (value.length < 14) return null
        val digits = value.substring(0, 14)
        val offset = value.substring(14).trim()
        val zone = offsetToZone(offset)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zone)
            isLenient = false
        }
        return runCatching { fmt.parse(digits)?.time }.getOrNull()
    }

    /** "+0100" becomes "GMT+01:00". An empty or unknown offset means UTC. */
    private fun offsetToZone(offset: String): String {
        val m = Regex("^([+-])(\\d{2}):?(\\d{2})$").find(offset) ?: return "UTC"
        return "GMT${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}"
    }

    private class Handler(private val keepAfterMs: Long) : DefaultHandler() {
        private val out = HashMap<String, MutableList<Program>>()

        private var channelId: String? = null
        private var start: Long? = null
        private var stop: Long? = null
        private var title: StringBuilder? = null
        private var desc: StringBuilder? = null
        private var category: StringBuilder? = null
        private var rating: StringBuilder? = null
        private var inRating = false
        private var text = StringBuilder()
        private var inProgramme = false

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            text = StringBuilder()
            when (qName) {
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
                "title", "desc", "category", "rating" -> {
                    if (inProgramme) {
                        if (qName == "title") title = StringBuilder()
                        if (qName == "desc") desc = StringBuilder()
                        if (qName == "category" && category == null) category = StringBuilder()
                        if (qName == "rating") {
                            rating = StringBuilder()
                            inRating = true
                        }
                    }
                }
                "value" -> if (inRating) text = StringBuilder()
            }
        }

        override fun characters(ch: CharArray, offset: Int, length: Int) {
            if (!inProgramme) return
            text.append(ch, offset, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (!inProgramme) return
            val value = text.toString().trim()
            when (qName) {
                "title" -> title?.append(value)
                "desc" -> desc?.append(value)
                "category" -> if (category?.isEmpty() == true) category?.append(value)
                "value" -> if (inRating && rating?.isEmpty() == true) rating?.append(value)
                "rating" -> inRating = false
                "programme" -> {
                    finishProgramme()
                    inProgramme = false
                }
            }
            text = StringBuilder()
        }

        private fun finishProgramme() {
            val ch = channelId ?: return
            val s = start ?: return
            val e = stop ?: return
            if (e <= keepAfterMs || e <= s) return
            val program = Program(
                title = title?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled",
                start = s,
                end = e,
                description = desc?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                rating = rating?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                category = category?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            )
            out.getOrPut(ch) { mutableListOf() }.add(program)
        }

        fun result(): Map<String, List<Program>> =
            out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }
}
