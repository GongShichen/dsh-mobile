package org.harnesslink.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.dsh.mobile.model.*
import io.dsh.mobile.sync.HLRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in simulator or device regression; the name and Host id must match the isolated fixture. */
@RunWith(AndroidJUnit4::class)
class HLPersonalFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as HLApplication
    private fun <T> remote(block: suspend HLRepository.() -> T): T = runBlocking {
        withTimeout(70_000) { withContext(Dispatchers.Main) { app.repository.block() } }
    }
    private fun control(label: String) = device.wait(Until.findObject(By.desc(label)), 20_000) ?: throw AssertionError("Missing: $label")
    private fun tap(text: String) { (device.wait(Until.findObject(By.text(text)), 20_000) ?: throw AssertionError("Missing: $text")).click() }
    private fun draft(value: String) {
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000) ?: throw AssertionError("Missing composer")
        input.text = value
        remote { state.first { it.draft == value } }
    }
    private fun capture(name: String) { assertTrue(device.takeScreenshot(File(app.filesDir, "hl-personal-$name.png"))) }

    @Test fun modesDelegateAndOpenRealSessions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("HL_PERSONAL_QA") == "1")
        val file = File(app.filesDir, "hl-personal-qa.json")
        val config = JSONObject(file.readText()); file.delete()
        val hostId = config.getString("endpointId")
        device.wakeUp()
        ActivityScenario.launch(HLMainActivity::class.java).use {
            try {
                remote { onForeground(); pair(config.getString("uri"), "HL Personal Android QA") }
                val ready = remote { state.first { it.connected && it.activeHostId == hostId && it.personal?.available == true && it.personal?.profile?.mainSessionReady == true } }
                assertEquals("Harness Link Personal QA", ready.activeHost?.name)
                assertTrue("personal-agent-v1" in ready.capabilities)
                assertTrue(ready.personal!!.goals.size >= 2)
                remote { switchConversationMode(HLConversationMode.NORMAL); refreshSessions(); selectSession(config.getString("sessionId")); renameSession(config.getString("sessionId"), "HL QA · normal") }
                draft("HL normal draft")
                control("私人助理模式").click()
                remote { state.first { it.conversationMode == HLConversationMode.PERSONAL && it.selectedSessionId == it.personal?.profile?.mainSessionId } }
                draft("HL private draft"); capture("assistant")
                val offlineTarget = HLExecutionTarget("workspace-new", config.getString("workspaceId"))
                remote { selectPersonalTarget(offlineTarget) }
                remote { disconnect() }
                draft("HL private offline draft")
                assertNotNull(device.wait(Until.findObject(By.desc("发送").clickable(false)), 5_000))
                assertNotNull(remote { state.value.personal })
                capture("offline")
                remote { connect(hostId); state.first { it.connected && it.personal?.profile?.mainSessionReady == true } }
                assertEquals("HL private offline draft", remote { state.value.draft })
                assertEquals(offlineTarget, remote { state.value.personalTarget })
                remote { selectPersonalTarget(null) }
                draft("HL private draft")
                control("会话模式").click()
                remote { state.first { it.conversationMode == HLConversationMode.NORMAL && it.draft == "HL normal draft" } }
                assertEquals(config.getString("sessionId"), remote { state.value.selectedSessionId })
                control("私人助理模式").click()
                remote { state.first { it.draft == "HL private draft" } }
                control("会话模式").click()
                control("对话更多操作").click(); tap("交给私人助理")
                remote { state.first { it.conversationMode == HLConversationMode.PERSONAL && it.personalTarget?.sessionId == config.getString("sessionId") } }
                val oldTasks = remote { state.value.personal!!.tasks.map { it.id }.toSet() }
                draft("HL_QA:personal:${UUID.randomUUID()}"); control("发送").click()
                val delegated = remote { state.first { it.personal?.tasks?.any { t -> t.id !in oldTasks && t.title == "HL QA 对话交办" && t.state == "waiting" } == true } }
                val task = delegated.personal!!.tasks.first { it.id !in oldTasks && it.title == "HL QA 对话交办" }
                assertEquals(config.getString("sessionId"), task.binding?.sessionId)
                assertEquals("borrowed", task.binding?.ownership)
                remote { personal.command("createTask", buildJsonObject { put("title", "HL QA 内部成果"); put("instruction", "保存测试结果文件"); put("resultKind", "files") }) }
                remote { state.first { it.personal?.tasks?.any { t -> t.title == "HL QA 内部成果" && t.state == "completed" } == true } }
                control("事务").click(); tap("HL QA 内部成果"); capture("tasks")
                tap("交办内容")
                assertNotNull(device.wait(Until.findObject(By.text("保存测试结果文件")), 10_000))
                control("关闭交办内容").click()
                val bindings = remote { state.value.personal!!.tasks.mapNotNull { it.binding?.sessionId }.toSet() }
                tap("打开执行会话")
                val opened = remote { state.first { it.conversationMode == HLConversationMode.NORMAL && it.selectedSessionId in bindings } }
                assertTrue(opened.selectedSessionId in bindings)
                capture("opened-session")
            } catch (error: Throwable) {
                capture("failure"); device.dumpWindowHierarchy(File(app.filesDir, "hl-personal-failure.xml")); throw error
            } finally { remote { if (state.value.activeHostId == hostId) disconnect() } }
        }
    }
}
