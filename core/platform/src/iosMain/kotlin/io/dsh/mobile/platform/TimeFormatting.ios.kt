package io.dsh.mobile.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatMessageTime(epochMillis: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis.toDouble() / 1000)
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    val sameDay = formatter.stringFromDate(date) == formatter.stringFromDate(NSDate())
    formatter.dateFormat = "yyyy"
    val sameYear = formatter.stringFromDate(date) == formatter.stringFromDate(NSDate())
    formatter.dateFormat = if (sameDay) "HH:mm" else if (sameYear) "M-d HH:mm" else "yyyy-M-d HH:mm"
    return formatter.stringFromDate(date)
}
