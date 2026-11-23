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

const val MAX_REMINDERS = 40
private const val REMINDER_GRACE_MS = 10 * 60_000L

/** Drops reminders that started more than ten minutes ago. */
fun pruneReminders(reminders: List<Reminder>, nowMs: Long): List<Reminder> =
    reminders.filter { it.start >= nowMs - REMINDER_GRACE_MS }

/**
 * Adds the reminder, or removes it when it is already there. When the list is full the oldest
 * goes, never the new one. The Boolean is true only when the reminder was stored.
 */
fun toggleReminderIn(
    existing: List<Reminder>,
    reminder: Reminder,
    nowMs: Long,
    max: Int = MAX_REMINDERS
): Pair<List<Reminder>, Boolean> {
    val key = reminderKey(reminder)
    val list = pruneReminders(existing, nowMs)
    if (list.any { reminderKey(it) == key }) return list.filter { reminderKey(it) != key } to false
    if (reminder.start < nowMs - REMINDER_GRACE_MS) return list to false
    val all = (list + reminder).toMutableList()
    while (all.size > max) {
        val oldest = all.filter { it !== reminder }.minByOrNull { it.start } ?: break
        all.remove(oldest)
    }
    return all to true
}
