package com.dallas.cabletv.data

/** A programme the viewer asked to be told about. */
data class Reminder(
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long
)

/** Due when the showing starts within the next minute, or started less than two minutes ago. */
fun dueReminders(reminders: List<Reminder>, nowMs: Long, fired: Set<String>): List<Reminder> =
    reminders.filter { reminder ->
        val key = reminderKey(reminder)
        key !in fired && nowMs >= reminder.start - 60_000L && nowMs < reminder.start + 2 * 60_000L
    }

fun reminderKey(reminder: Reminder): String = "${reminder.channelId}@${reminder.start}"
