package com.dallas.cabletv.ui

import com.dallas.cabletv.data.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
private val dayFmt = SimpleDateFormat("EEE M/d", Locale.US)
private val hourFmt = SimpleDateFormat("h:mm a", Locale.US)

/** "2:30 PM" */
fun formatTime(ms: Long): String = timeFmt.format(Date(ms))

/** "2:30 - 3:00 PM" */
fun formatRange(p: Program): String = "${formatTime(p.start)} - ${formatTime(p.end)}"

/** "FRI 8/28" */
fun formatDay(ms: Long): String = dayFmt.format(Date(ms)).uppercase()

/** Time label for the guide header. */
fun formatSlot(ms: Long): String = hourFmt.format(Date(ms))

/** "1h 05m" or "42m" */
fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
}

/** "1:02:33" or "4:05" for playback positions. */
fun formatPosition(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
