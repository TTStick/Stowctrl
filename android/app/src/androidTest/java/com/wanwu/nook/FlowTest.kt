package com.wanwu.nook

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end flows on a device/emulator. The sync server must be reachable
 * at 10.0.2.2:5858 (the host from the emulator); the HA test also needs
 * tools/fake_ha.py on 10.0.2.2:18123. Screenshots land in
 * /sdcard/Android/data/com.wanwu.nook/files/shots.
 */
@RunWith(AndroidJUnit4::class)
class FlowTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NookApplication
    private val server = "10.0.2.2:5858"

    @Before fun reset() {
        runBlocking { withContext(Dispatchers.Main) { app.container.resetForTests() } }
    }

    private fun launch() = ActivityScenario.launch(MainActivity::class.java)

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitTag(tag: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String, timeout: Long = 10_000, substring: Boolean = false) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tag(t: String): SemanticsNodeInteraction { waitTag(t); return rule.onNodeWithTag(t, useUnmergedTree = true) }

    private fun click(t: String) = tag(t).performClick()

    /** Taps `t` until `expect` appears (a sheet can miss a tap while the keyboard is closing). */
    private fun open(t: String, expect: String) {
        repeat(3) {
            click(t)
            val shown = runCatching { rule.waitUntil(4000) { rule.onAllNodesWithTag(expect, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
            if (shown) return
        }
        waitTag(expect)
    }

    /** Scrolls a lazy list until a tagged row exists, then clicks it. */
    private fun scrollClick(t: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(t))
        click(t)
    }

    private fun httpGet(url: String, token: String? = null): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.connectTimeout = 3000
        c.inputStream.bufferedReader().readText()
    }.getOrNull()

    /** A freshly booted emulator can take a few seconds before the host is reachable. */
    private fun reachable(url: String, token: String? = null): Boolean {
        repeat(8) { if (httpGet(url, token) != null) return true; Thread.sleep(2000) }
        return false
    }

    @Test fun fullFlowAndSyncToSecondDevice() {
        assumeTrue("sync server not reachable", reachable("http://$server/api/health"))
        launch()
        shot("01-welcome")
        click("onboarding.start")
        shot("02-wizard")
        scrollClick("wizard.create")
        waitTag("room.厨房")
        shot("03-floorplan")

        click("room.厨房")
        waitTag("furniture.冰箱")
        shot("04-kitchen")
        click("furniture.冰箱")
        waitTag("shelf.冷藏上层")
        Thread.sleep(1200)
        shot("05-fridge-open")

        click("add.冷藏上层")
        tag("item.name").performTextInput("牛奶")
        open("item.icon", "symbol.mug")
        click("symbol.mug")
        open("item.category", "cat.饮品")
        click("cat.饮品")
        shot("06-item-editor")
        click("editor.save")
        waitTag("thumb.牛奶")
        shot("07-fridge-with-milk")

        click("thumb.牛奶")
        waitText("1 个")
        click("qty.plus")
        waitText("2 个")
        shot("08-item-detail")
        click("item.edit")
        tag("item.name").performTextReplacement("鲜牛奶")
        click("editor.save")
        waitText("鲜牛奶")
        tag("item.delete").performScrollTo().performClick()
        click("choice.删除")
        waitTag("shelf.冷藏上层")
        check(rule.onAllNodesWithTag("thumb.鲜牛奶").fetchSemanticsNodes().isEmpty())

        click("nav.back")
        click("room.edit")
        click("furniture.水槽")
        click("tool.旋转")
        click("tool.复制")
        shot("09-room-editing")
        click("tool.删除")
        click("choice.删除")
        click("room.edit")
        waitTag("furniture.水槽")

        click("tab.物品")
        click("items.add")
        tag("item.name").performTextInput("螺丝刀")
        open("item.location", "loc.list")
        tag("loc.list").performScrollToNode(hasTestTag("loc.地柜"))
        click("loc.地柜")
        tag("loc.list").performScrollToNode(hasTestTag("pick.抽屉"))
        click("pick.抽屉")
        click("editor.save")
        waitTag("row.螺丝刀")
        tag("items.search").performTextInput("螺丝")
        waitText("厨房 › 地柜 › 抽屉", substring = true)
        shot("10-items-search")

        click("tab.设备")
        waitTag("ha.setup")
        shot("11-devices")

        val user = "and${System.currentTimeMillis() % 100_000_000}"
        click("tab.我的")
        click("settings.signin")
        tag("account.server").performTextReplacement(server)
        click("account.registerTab")
        tag("account.username").performTextInput(user)
        tag("account.password").performTextInput("secret123")
        click("account.submit")
        waitTag("settings.account", 20_000)
        waitTag("settings.synced", 20_000)
        shot("12-settings-synced")

        // Offline mode: edits queue up locally, then sync once it's turned off.
        click("settings.offline")
        click("tab.物品")
        click("items.add")
        tag("item.name").performTextInput("离线时加的电池")
        click("editor.save")
        click("tab.我的")
        waitTag("settings.paused")
        shot("12b-offline-paused")
        click("settings.offline")
        waitTag("settings.synced", 20_000)

        // A "second device": wipe everything local, sign in, the home comes back.
        runBlocking { withContext(Dispatchers.Main) { app.container.resetForTests() } }
        waitTag("onboarding.signin")
        click("onboarding.signin")
        tag("account.server").performTextReplacement(server)
        tag("account.username").performTextInput(user)
        tag("account.password").performTextInput("secret123")
        click("account.submit")
        waitTag("room.厨房", 30_000)
        click("tab.物品")
        waitTag("row.螺丝刀", 15_000)
        waitTag("row.离线时加的电池", 15_000)
        shot("13-second-device")

        click("tab.我的")
        tag("settings.deleteAccount").performScrollTo().performClick()
        tag("delete.password").performTextInput("secret123")
        click("delete.confirm")
        waitTag("settings.signin", 15_000)
    }

    @Test fun homeAssistantMappingAndControl() {
        val ha = "10.0.2.2:18123"
        assumeTrue("fake Home Assistant not running", reachable("http://$ha/api/", "test-token"))
        launch()
        click("onboarding.start")
        scrollClick("wizard.create")
        waitTag("room.客厅")

        click("tab.设备")
        click("ha.setup")
        tag("ha.url").performTextInput("http://$ha")
        tag("ha.token").performTextInput("test-token")
        click("ha.test")
        waitText("连接成功", substring = true)
        shot("20-ha-settings")
        click("nav.back")

        rule.waitUntil(10_000) { rule.onAllNodesWithTag("map.客厅灯").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("map.客厅灯"))
        click("map.客厅灯")
        click("pickFurniture.沙发")
        val toggle = "toggle.light.living_room"
        waitTag(toggle)
        val before = httpGet("http://$ha/calls", "test-token") ?: "[]"
        click(toggle)
        rule.waitUntil(10_000) { (httpGet("http://$ha/calls", "test-token") ?: "").length > before.length }
        val calls = httpGet("http://$ha/calls", "test-token")!!
        check(calls.contains("\"light\", \"turn_on\"") || calls.contains("\"light\", \"turn_off\"")) { calls }
        shot("21-ha-devices")

        click("tab.家")
        click("room.客厅")
        waitTag("furniture.沙发")
        Thread.sleep(1500)
        shot("22-ha-room")
        click("furniture.沙发")
        waitText("智能设备")
        shot("23-ha-furniture")
    }
}
