package io.dsh.mobile.platform

import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

actual fun currentTimeZoneId(): String = NSTimeZone.localTimeZone.name
