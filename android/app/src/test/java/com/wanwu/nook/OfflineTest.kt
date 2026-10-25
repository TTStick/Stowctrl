package com.wanwu.nook

import com.wanwu.nook.data.Backup
import com.wanwu.nook.data.CustomIcon
import com.wanwu.nook.data.Entities
import com.wanwu.nook.data.HomeStore
import com.wanwu.nook.data.IconRef
import com.wanwu.nook.data.Item
import com.wanwu.nook.data.NookJson
import com.wanwu.nook.data.RoomType
import com.wanwu.nook.data.newId
import com.wanwu.nook.image.SvgDocument
import com.wanwu.nook.net.DirectClaude
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline mode: backups (no server) and direct AI drawing. */
class OfflineTest {
    private fun tempStore() = HomeStore(Files.createTempDirectory("nook").toFile())
    private val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9, 9)

    private fun sampleHome(store: HomeStore, itemName: String, home: String = "离线之家"): Item {
        store.buildHome(home, listOf(RoomType.kitchen to 1), true)
        val fridge = store.furniture.values.first { it.kind == "fridge" }
        val shelf = store.compartmentsOf(fridge.id).first { it.name == "冷藏上层" }
        val photo = newId() + ".jpg"
        store.files.write(photoBytes, photo)
        val icon = CustomIcon(name = "备份图标", svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><circle cx="24" cy="24" r="10" stroke="currentColor" fill="none"/></svg>""")
        store.save(icon)
        store.save(Item(name = itemName, compartmentId = shelf.id, photoId = photo, originalPhotoId = photo, icon = IconRef.custom(icon.id)))
        store.saveSettings(store.settings.copy(haURL = "http://ha.local:8123", haToken = "secret-ha-token"))
        return store.items.values.first { it.name == itemName }
    }

    private fun backupBytes(store: HomeStore) = ByteArrayOutputStream().also { runBlocking { Backup.export(store, it) } }.toByteArray()

    @Test fun backupRoundTripMergeAndReplace() {
        val a = tempStore()
        val item = sampleHome(a, "备份测试牛奶")
        val bytes = backupBytes(a)
        val work = Files.createTempDirectory("work").toFile()

        val b = tempStore()
        val loaded = Backup.load(ByteArrayInputStream(bytes), work)
        assertEquals(1, loaded.photoCount)
        assertEquals(1, loaded.count(Entities.ITEM))
        val s = Backup.restore(loaded, b, replace = false)
        assertEquals(1, s.items); assertEquals(1, s.photos)
        val restored = b.items[item.id]!!
        assertEquals("厨房 › 冰箱 › 冷藏上层", b.locationText(restored))
        assertArrayEquals(photoBytes, b.files.read(restored.photoId!!))
        assertNotNull(b.icons[restored.icon!!.value])
        assertEquals("离线之家", b.settings.homeName)
        assertEquals("backups never carry the HA token", "", b.settings.haToken)
        assertTrue(b.didOnboard)

        b.save(restored.copy(name = "本机改过的名字"))
        b.save(Item(name = "只在本机的东西"))
        val merged = Backup.restore(Backup.load(ByteArrayInputStream(bytes), work), b, replace = false)
        assertTrue(merged.skipped > 0)
        assertEquals("本机改过的名字", b.items[item.id]!!.name)
        Backup.restore(Backup.load(ByteArrayInputStream(bytes), work), b, replace = true)
        assertEquals("备份测试牛奶", b.items[item.id]!!.name)
        assertNull(b.items.values.firstOrNull { it.name == "只在本机的东西" })
        assertEquals(1, b.items.size)
    }

    @Test fun restoreIgnoresUnsafeFileNames() {
        val manifest = Backup.Manifest(exportedAt = 0, platform = "test", homeName = "x", records = emptyList())
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(Backup.MANIFEST)); z.write(NookJson.encodeToString(Backup.Manifest.serializer(), manifest).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("files/../../evil.jpg")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            z.putNextEntry(ZipEntry("files/${newId()}.png")); z.write(byteArrayOf(4, 5, 6)); z.closeEntry()
        }
        val work = Files.createTempDirectory("work").toFile()
        val store = tempStore()
        val s = Backup.restore(Backup.load(ByteArrayInputStream(out.toByteArray()), work), store, replace = false)
        assertEquals(1, s.photos)
        assertFalse(Backup.isSafeFileId("../../evil.jpg"))
        assertFalse(File(work.parentFile, "evil.jpg").exists())
        assertEquals(1, store.files.allIds().size)
        assertTrue(runCatching { Backup.load(ByteArrayInputStream(byteArrayOf(1, 2, 3)), work) }.isFailure)
    }

    /** Needs tools/fake_claude.py on 127.0.0.1:18200; skipped otherwise. */
    @Test fun directAIDrawingWithoutServer() = runBlocking<Unit> {
        val base = "http://127.0.0.1:18200"
        assumeTrue("fake Claude not running", runCatching { URL("$base/calls").readText() }.isSuccess)
        assertTrue(runCatching { DirectClaude("wrong-key", base, "claude-opus-5-5").checkKey() }.isFailure)
        DirectClaude("test-key", base, "claude-opus-5-5").checkKey()
        val svg = DirectClaude("test-key", base, "claude-opus-5-5").drawIcon("汤勺")
        assertNotNull(SvgDocument.parse(svg))
        val last = NookJson.parseToJsonElement(URL("$base/calls").readText()).jsonArray.last().jsonObject
        val headers = last["headers"]!!.jsonObject
        val body = last["body"]!!.jsonObject
        assertEquals("server-side-fallback-2026-07-01", headers["anthropic-beta"]!!.jsonPrimitive.content)
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val content = (body["messages"] as JsonArray).first().jsonObject["content"].toString()
        assertTrue(content, content.contains("汤勺"))
    }

    /** Backups made by iOS restore here and vice versa. Set XPLAT_DIR; see README. */
    @Test fun crossPlatformBackup() {
        val dir = System.getenv("XPLAT_DIR").orEmpty()
        assumeTrue(dir.isNotEmpty())
        val ios = File(dir, "ios-backup.zip")
        if (ios.exists()) {
            val store = tempStore()
            val s = Backup.restore(Backup.load(ios.inputStream(), Files.createTempDirectory("w").toFile()), store, replace = true)
            assertEquals(1, s.photos)
            val bowl = store.items.values.first { it.name == "苹果备份的碗" }
            assertEquals("厨房 › 冰箱 › 冷藏上层", store.locationText(bowl))
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3), store.files.read(bowl.photoId!!))
            assertNotNull(store.icons[bowl.icon!!.value.uppercase()])
            assertEquals("离线之家", store.settings.homeName)
        }
        val mine = tempStore()
        sampleHome(mine, "安卓备份的杯子", home = "安卓之家")
        File(dir, "android-backup.zip").writeBytes(backupBytes(mine))
    }
}
