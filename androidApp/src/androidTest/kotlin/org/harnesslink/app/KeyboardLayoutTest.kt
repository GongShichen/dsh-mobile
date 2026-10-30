package org.harnesslink.app

import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeyboardLayoutTest {
    @Test fun inputKeepsKeyboardOpenAndComposerStaysAboveIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun ime(): Pair<Boolean, Int> {
                var value = false to 0
                scenario.onActivity { activity -> ViewCompat.getRootWindowInsets(activity.window.decorView)?.let { value = it.isVisible(WindowInsetsCompat.Type.ime()) to it.getInsets(WindowInsetsCompat.Type.ime()).bottom } }
                return value
            }
            val input = device.wait(Until.findObject(By.descContains("消息输入")), 15_000)
            assertNotNull("Message input should be available", input)
            input.click()
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!ime().first && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Keyboard must open", ime().first)
            SystemClock.sleep(700) // Include the platform's IME resize animation in the regression.
            val visible = ime()
            assertTrue("The original focus tap must not dismiss itself after resize", visible.first)
            val send = device.findObject(By.desc("发送"))
            assertNotNull(send)
            assertTrue("Composer must remain above the keyboard", send.visibleBounds.bottom <= device.displayHeight - visible.second + 4)
            assertTrue("Keyboard screenshot should be written", device.takeScreenshot(File(instrumentation.targetContext.filesDir, "keyboard-layout-open.png")))
            val menu = device.findObject(By.desc("菜单"))
            device.click(device.displayWidth / 2, menu.visibleBounds.centerY())
            val hideDeadline = SystemClock.uptimeMillis() + 5000
            while (ime().first && SystemClock.uptimeMillis() < hideDeadline) SystemClock.sleep(50)
            assertFalse("A tap outside the input should dismiss the keyboard", ime().first)
        }
    }
    @Test fun pairedConversationUsageAndTrajectoryAreReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            val menu = device.wait(Until.findObject(By.desc("菜单")), 15_000)
            assertNotNull(menu); menu.click()
            val conversation = device.wait(Until.findObject(By.text("问候")), 15_000)
            org.junit.Assume.assumeTrue("A paired host with the user's greeting conversation is required", conversation != null)
            conversation.click()
            val usage = device.wait(Until.findObject(By.textContains("用量")), 15_000)
            assertNotNull("Desktop usage must be visible", usage); usage.click()
            assertTrue(device.wait(Until.hasObject(By.text("本轮用量")), 5000))
            assertTrue(device.takeScreenshot(File(instrumentation.targetContext.filesDir, "desktop-usage.png")))
            device.findObject(By.text("关闭")).click()
            val trace = device.wait(Until.findObject(By.desc("查看对话轨迹")), 5000)
            assertNotNull(trace); trace.click()
            assertTrue(device.wait(Until.hasObject(By.text("对话轨迹")), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("搜索轨迹")), 5000))
            assertTrue(device.takeScreenshot(File(instrumentation.targetContext.filesDir, "desktop-trajectory.png")))
            device.findObject(By.desc("关闭轨迹")).click()
        }
    }

}
