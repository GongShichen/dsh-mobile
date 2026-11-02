package io.dsh.mobile.sync

import io.dsh.mobile.model.*
import kotlin.test.*

class HLGoogleSetupReminderPolicyTest {
    private fun snapshot(vararg requests: String) = HLPersonalSnapshot(revision = requests.size.toLong(), available = true,
        profile = HLPersonalProfile(id = "profile", name = "助理", enabled = true, paused = false, revision = 1),
        serviceRequests = requests.mapIndexed { index, id -> HLPersonalServiceRequest(id = id, service = "gmail", createdAt = index.toLong()) })

    @Test fun enteringPersonalDoesNotPromptOrReplayHistoricalRequests() {
        val policy = HLGoogleSetupReminderPolicy()
        assertNull(policy.observe("desktop", snapshot("old"), true, false))
        assertNull(policy.observe("desktop", snapshot("old"), true, false))
        assertEquals("new", policy.observe("desktop", snapshot("old", "new"), true, false)?.id)
        assertNull(policy.observe("desktop", snapshot("old", "new"), true, false))
    }
    @Test fun normalModeConsumesRequestsWithoutPromptingOnLaterModeSwitch() {
        val policy = HLGoogleSetupReminderPolicy()
        policy.observe("desktop", snapshot(), false, false)
        assertNull(policy.observe("desktop", snapshot("normal"), false, false))
        assertNull(policy.observe("desktop", snapshot("normal"), true, false))
        assertEquals("personal", policy.observe("desktop", snapshot("normal", "personal"), true, false)?.id)
    }
    @Test fun doNotRemindSuppressesAllNewRequestsWithoutReplayingWhenReenabled() {
        val policy = HLGoogleSetupReminderPolicy()
        policy.observe("desktop", snapshot(), true, true)
        assertNull(policy.observe("desktop", snapshot("ignored"), true, true))
        assertNull(policy.observe("desktop", snapshot("ignored"), true, false))
        assertEquals("later", policy.observe("desktop", snapshot("ignored", "later"), true, false)?.id)
    }
    @Test fun switchingComputersOrReconnectingNeverReplaysAStaleRequest() {
        val policy = HLGoogleSetupReminderPolicy()
        policy.observe("a", snapshot(), true, false)
        assertEquals("one", policy.observe("a", snapshot("one"), true, false)?.id)
        assertNull(policy.observe("a", null, false, false))
        assertNull(policy.observe("a", snapshot("one"), true, false))
        assertNull(policy.observe("b", snapshot("one"), true, false))
    }
    @Test fun configuredConnectedAccountsNeedNoSetupReminder() {
        val policy = HLGoogleSetupReminderPolicy()
        policy.observe("a", snapshot(), true, false)
        val connected = snapshot("request").copy(googleConfigured = true,
            connections = listOf(HLPersonalConnection("account", "google", "fixture@example.com", "connected")))
        assertNull(policy.observe("a", connected, true, false))
    }
}
