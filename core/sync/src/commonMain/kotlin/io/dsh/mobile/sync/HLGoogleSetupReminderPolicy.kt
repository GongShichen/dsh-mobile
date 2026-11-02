package io.dsh.mobile.sync

import io.dsh.mobile.model.HLPersonalServiceRequest
import io.dsh.mobile.model.HLPersonalSnapshot

/** Only new authoritative service requests can prompt; mode entry and cached history cannot. */
class HLGoogleSetupReminderPolicy {
    private var host: String? = null
    private var initialized = false
    private val seen = mutableSetOf<String>()

    fun observe(hostId: String?, snapshot: HLPersonalSnapshot?, personalVisible: Boolean, suppressed: Boolean): HLPersonalServiceRequest? {
        if (host != hostId) { host = hostId; initialized = false; seen.clear() }
        if (snapshot == null) return null
        val incoming = snapshot.serviceRequests.filter { it.provider == "google" && it.service in setOf("gmail", "calendar") }
        val fresh = incoming.filter { it.id !in seen }
        seen.addAll(incoming.map { it.id })
        if (!initialized) { initialized = true; return null }
        if (!personalVisible || suppressed || snapshot.googleConfigured && snapshot.connections.any { it.provider == "google" && it.state == "connected" }) return null
        return fresh.maxByOrNull { it.createdAt }
    }
}
