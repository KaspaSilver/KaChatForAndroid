package com.kachat.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared date/time formatting for message lists (1:1 chats and broadcast rooms) — day dividers plus per-message times revealed by swiping the list left. */
object ChatTimeFormat {
    fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /** "Today" / "Yesterday" / "July 6, 2026" — shown as a divider between days' worth of messages. */
    fun formatDateDivider(timestamp: Long): String {
        val now = System.currentTimeMillis()
        return when {
            isSameDay(timestamp, now) -> "Today"
            isSameDay(timestamp, now - 24L * 60 * 60 * 1000) -> "Yesterday"
            else -> SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(timestamp))
        }
    }

    /** "3:45pm" — revealed per-message only when the chat is swiped left. */
    fun formatMessageTime(timestamp: Long): String {
        return SimpleDateFormat("h:mma", Locale.US).format(Date(timestamp)).lowercase()
    }
}
