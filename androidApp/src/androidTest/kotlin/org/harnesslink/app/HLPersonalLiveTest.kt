package org.harnesslink.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.dsh.mobile.model.HLConversationMode
import io.dsh.mobile.sync.HLRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly opt-in. Uses the configured live DSH and its real model, never a model fixture. */
@RunWith(AndroidJUnit4::class)
class HLPersonalLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as HLApplication
    private fun <T> remote(block: suspend HLRepository.() -> T): T = runBlocking { withTimeout(140_000) { withContext(Dispatchers.Main) { app.repository.block() } } }
    private fun tap(text: String) { (device.wait(Until.findObject(By.text(text)), 120_000) ?: error("Missing: $text")).click() }
    private fun capture(name: String) { assertTrue(device.takeScreenshot(File(app.filesDir, "hl-live-$name.png"))); device.dumpWindowHierarchy(File(app.filesDir, "hl-live-$name.xml")) }
    private fun send(text: String) {
        // Exercise the actual authenticated P2P submission; this test targets reminder behavior.
        remote { sendMessage(text) }
    }
    @Test fun realGoogleRequestsShowOnceAndRespectTheLocalOptOut() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("HL_PERSONAL_LIVE") == "1")
        val file = File(app.filesDir, "hl-personal-live.json"); val config = JSONObject(file.readText()); file.delete()
        device.wakeUp()
        ActivityScenario.launch(HLMainActivity::class.java).use {
            try {
                remote { disconnect(); pair(config.getString("uri"), config.getString("name")); onForeground() }
                remote { state.first { it.connected && it.activeHostId == config.getString("endpointId") && it.personal?.available == true } }
                remote { setGoogleSetupReminderSuppressed(false); selectPersonalTarget(null); switchConversationMode(HLConversationMode.PERSONAL) }
                assertFalse(device.hasObject(By.text("不再提示")))
                val before = remote { state.value.personal!!.serviceRequests.map { it.id }.toSet() }
                send("我现在要连接 Gmail。请实际调用 hl_personal_request_google，service 选择 gmail，在手机显示账户连接提示；不要只回复操作步骤，也不要发送邮件。")
                remote { state.first { it.personal?.serviceRequests?.any { r -> r.id !in before } == true } }
                assertNotNull(device.wait(Until.findObject(By.text("不再提示")), 15_000)); capture("google-dialog")
                tap("不再提示"); remote { state.first { it.personalGoogleReminderSuppressed } }; capture("opted-out")
                remote { switchConversationMode(HLConversationMode.NORMAL); switchConversationMode(HLConversationMode.PERSONAL) }
                assertFalse(device.hasObject(By.text("不再提示")))
                val thirdBefore = remote { state.value.messages.map { it.id }.toSet() }
                send("再帮我查询明天的 Google 日历安排，只查看时间和标题，不要修改日程。")
                remote { state.first { it.messages.any { r -> r.id !in thirdBefore && r.role == "assistant" && !r.streaming && r.text.isNotBlank() } } }
                assertFalse(device.hasObject(By.text("不再提示"))); capture("no-repeat")
            } catch (failure: Throwable) { capture("failure"); throw failure }
        }
    }
}
