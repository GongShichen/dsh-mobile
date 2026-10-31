package org.harnesslink.app

import android.app.KeyguardManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

/** Opt-in real-device test. The Host must be the isolated fixture, never the user's model profile. */
@RunWith(AndroidJUnit4::class)
class HLInteractionsFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as HLApplication
    private val status get() = File(app.filesDir, "hl-interaction-qa-status.json")
    private fun stage(name: String, session: String? = null) {
        status.writeText(JSONObject().put("stage", name).put("sessionId", session).toString())
    }
    private fun <T> remote(block: suspend HLRepository.() -> T): T = runBlocking {
        withTimeout(90_000) { withContext(Dispatchers.Main) { app.repository.block() } }
    }
    private fun waitText(text: String, timeout: Long = 30_000) = device.wait(Until.findObject(By.text(text)), timeout)
        ?: throw AssertionError("Expected interaction control: $text")
    private fun tap(text: String) { waitText(text).click() }
    private fun gone() {
        assertTrue("The answered/cancelled dialog must close", device.wait(Until.gone(By.text("稍后处理")), 15_000))
    }
    private fun snapshot(name: String) { assertTrue(device.takeScreenshot(File(app.filesDir, "hl-interaction-$name.png"))) }

    @Test fun synchronizeAndAnswerDesktopInteractions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("HL_INTERACTION_QA") == "1")
        device.wakeUp()
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stage("needs-unlock"); throw AssertionError("Unlock the connected Android phone before interaction QA")
        }
        val file = File(app.filesDir, "hl-interaction-qa.json")
        val config = JSONObject(file.readText()); file.delete()
        val host = config.getString("endpointId")
        ActivityScenario.launch(HLMainActivity::class.java).use {
            remote { onForeground() }
            val restore = File(app.filesDir, "hl-interaction-qa-restore.json")
            val previous = if (restore.exists()) JSONObject(restore.readText()).optString("hostId").takeIf { it.isNotBlank() }
                else remote { state.value.activeHostId }.also { restore.writeText(JSONObject().put("hostId", it ?: "").toString()) }
            try {
                remote { pair(config.getString("uri"), "Android interaction QA") }
                val connected = remote { withTimeout(40_000) { state.first { (it.connected && it.activeHostId == host && it.interactionsReady) || it.connection == io.dsh.mobile.model.HLConnectionStatus.ERROR } } }
                assertTrue("Pairing must reach the authenticated fixture; inspect private transport diagnostics", connected.connected && connected.interactionsReady)
                assertEquals("Harness Link interaction QA", remote { state.value.activeHost?.name })
                assertTrue(remote { "interaction-waits" in state.value.capabilities })
                stage("paired")

                fun start(mode: String): String {
                    assertEquals("Only the isolated fixture may receive QA prompts", host, remote { state.value.activeHostId })
                    val id = remote { createSession("HL QA · $mode") }
                    remote { call("models.select", buildJsonObject { put("sessionId", id); put("provider", "hl-interaction-fixture"); put("model", "hl-interaction-fixture") }) }
                    if (mode == "approval") {
                        // The old implementation lost this request because a different conversation was visible.
                        remote { createSession("HL QA · unrelated visible conversation") }
                    }
                    remote { call("sessions.prompt", buildJsonObject {
                        put("sessionId", id); put("requestId", UUID.randomUUID().toString()); put("mode", "queue")
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "[HL_QA:$mode:${UUID.randomUUID()}]") }) })
                    }) }
                    return id
                }

                val only = InstrumentationRegistry.getArguments().getString("HL_ONLY_MODE")
                val modes = listOf("approval", "reject", "desktop", "cancel", "reconnect", "permission", "plan", "questions", "skip", "timed")
                require(only == null || only in modes)
                for (mode in modes.filter { only == null || it == only }) {
                    if (mode == "permission") {
                        device.wait(Until.findObject(By.desc("菜单")), 10_000)?.click()
                        tap("设置")
                    }
                    val id = start(mode)
                    when (mode) {
                        "approval" -> {
                            waitText("允许这一次"); snapshot("approval"); waitText("来自：HL QA · approval")
                            tap("允许这一次"); gone(); stage("phone-approved", id)
                        }
                        "reject" -> { tap("拒绝"); gone(); stage("phone-rejected", id) }
                        "desktop" -> { waitText("允许这一次"); stage("desktop-visible", id); gone(); stage("desktop-settled", id) }
                        "cancel" -> { waitText("允许这一次"); stage("cancel-visible", id); gone(); stage("host-cancelled", id) }
                        "reconnect" -> {
                            waitText("允许这一次"); remote { disconnect(); connect(host) }
                            tap("允许这一次"); gone(); stage("reconnected-approved", id)
                        }
                        "permission" -> { waitText("是否允许访问测试资源？"); snapshot("permission"); tap("始终允许"); gone(); stage("permission-answered", id) }
                        "plan" -> { waitText("计划确认"); snapshot("plan"); tap("同意测试计划"); tap("提交决定"); gone(); stage("plan-answered", id) }
                        "questions" -> {
                            waitText("需要你的回答"); tap("消息同步"); tap("权限确认")
                            var answer = device.findObject(By.descContains("补充回答：备注"))
                            if (answer == null) {
                                val scroller = device.findObject(By.desc("待处理请求内容")) ?: device.findObjects(By.scrollable(true)).firstOrNull()
                                scroller?.scrollUntil(Direction.DOWN, Until.hasObject(By.descContains("补充回答：备注")))
                                answer = device.wait(Until.findObject(By.descContains("补充回答：备注")), 5_000)
                            }
                            val described = requireNotNull(answer) { "The free-text answer must be available" }
                            val field = generateSequence(described) { it.parent }.take(6).firstOrNull { it.className == "android.widget.EditText" }
                                ?: throw AssertionError("The described answer must expose an editable field")
                            field.click(); field.text = "HL physical device answer"; tap("需要你的回答")
                            snapshot("questions"); tap("提交回答"); gone(); stage("questions-answered", id)
                        }
                        "skip" -> { tap("跳过此题"); tap("提交回答"); gone(); stage("question-skipped", id) }
                        "timed" -> {
                            waitText("请补充测试结论")
                            remote { withTimeout(10_000) { state.first { s -> s.interactions.any { q -> q.sessionId == id && q.timing?.let { it.attached && it.paused } == true } } } }
                            Thread.sleep(4000) // Longer than the fixture's 3s budget: focus must hold the Host claim.
                            assertTrue(remote { state.value.pendingQuestions.any { q -> q.sessionId == id && q.state == "open" } })
                            tap("稍后处理")
                            remote { withTimeout(20_000) { state.first { s -> s.pendingQuestions.any { q -> q.sessionId == id && q.state == "continued" } } } }
                            tap("查看待处理"); waitText("请补充测试结论"); snapshot("continued")
                            tap("补答已完成"); tap("提交回答"); gone(); stage("continued-answered", id)
                        }
                    }
                }
                stage("complete")
            } catch (error: Throwable) {
                runCatching { snapshot("failure"); device.dumpWindowHierarchy(File(app.filesDir, "hl-interaction-failure.xml")) }
                throw error
            } finally {
                runCatching { remote { forgetHost(host) } }
                if (previous != null && previous != host) runCatching { remote { connect(previous) } }
                restore.delete()
            }
        }
    }
}
