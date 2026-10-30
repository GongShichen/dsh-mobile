package io.dsh.mobile.platform

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

actual fun formatMessageTime(epochMillis: Long): String {
    val value = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val pattern = if (value.toLocalDate() == today) "HH:mm" else if (value.year == today.year) "M-d HH:mm" else "yyyy-M-d HH:mm"
    return value.format(DateTimeFormatter.ofPattern(pattern))
}
