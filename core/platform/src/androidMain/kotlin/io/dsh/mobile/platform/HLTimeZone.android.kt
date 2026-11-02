package io.dsh.mobile.platform

actual fun currentTimeZoneId(): String = java.util.TimeZone.getDefault().id
