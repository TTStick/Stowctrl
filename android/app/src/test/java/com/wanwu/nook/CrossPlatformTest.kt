package com.wanwu.nook

import com.wanwu.nook.data.Catalog
import com.wanwu.nook.data.CustomIcon
import com.wanwu.nook.data.HomeStore
import com.wanwu.nook.data.IconRef
import com.wanwu.nook.data.Item
import com.wanwu.nook.data.RoomType
import com.wanwu.nook.image.SvgDocument
import com.wanwu.nook.net.ApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Android ↔ iOS interop through a real server, driven in phases by env vars:
 *   XPLAT_USER=<new user> XPLAT_PHASE=seed   → Android creates a home and items
 *   (then iOS CrossPlatformTests reads them and adds its own item)
 *   XPLAT_USER=<same user> XPLAT_PHASE=verify → Android reads what iOS wrote
 * Skipped unless XPLAT_USER is set.
 */
class CrossPlatformTest {
    private val user = System.getenv("XPLAT_USER").orEmpty()
    private val phase = System.getenv("XPLAT_PHASE").orEmpty()
    private val server = System.getenv("NOOK_SERVER") ?: "http://127.0.0.1:5858"

    private suspend fun syncAll(store: HomeStore, api: ApiClient) {
        while (true) {
            val out = store.outgoingChanges(400)
            val r = api.sync(store.cursor, out.map { it.change })
            store.acknowledge(out)
            store.applyRemote(r.changes)
            store.cursor = r.cursor
            if (!r.hasMore && (store.pendingCount == 0 || out.isEmpty())) break
        }
        store.repairOrphans()
    }

    @Test fun seed() = runBlocking {
        assumeTrue(user.isNotEmpty() && phase == "seed")
        val auth = ApiClient(server).login(user, "secret123", "android-test", register = true)
        val api = ApiClient(server, auth.token)
        val store = HomeStore(Files.createTempDirectory("xplat").toFile())
        store.buildHome("跨平台之家", listOf(RoomType.kitchen to 1, RoomType.bedroom to 1), withFurniture = true)
        val fridge = store.furniture.values.first { it.kind == "fridge" }
        val top = store.compartmentsOf(fridge.id).first { it.name == "冷藏上层" }
        val icon = CustomIcon(name = "安卓画的勺子", svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M24 6 a8 10 0 1 0 0.1 0 Z M24 26 V44" stroke="currentColor" fill="none" stroke-width="2.5"/></svg>""")
        store.save(icon)
        store.save(Item(name = "安卓杯子", compartmentId = top.id, categoryId = "00000000-0000-0000-0000-0000000C0004",
            icon = IconRef.symbol("mug"), quantity = 3.0, unit = "个", tags = listOf("安卓"), colors = listOf("#c62828")))
        store.save(Item(name = "安卓汤勺", compartmentId = top.id, icon = IconRef.custom(icon.id)))
        val wardrobe = store.furniture.values.first { it.kind == "wardrobe" }
        store.setStack(store.addFurniture(Catalog.kind("storage_box"), wardrobe.roomId)!!.id, wardrobe.id)
        syncAll(store, api)
        assertEquals(0, store.pendingCount)
    }

    @Test fun verify() = runBlocking {
        assumeTrue(user.isNotEmpty() && phase == "verify")
        val auth = ApiClient(server).login(user, "secret123", "android-test", register = false)
        val api = ApiClient(server, auth.token)
        val store = HomeStore(Files.createTempDirectory("xplat").toFile())
        syncAll(store, api)
        val milk = store.items.values.firstOrNull { it.name == "苹果手机记的牛奶" }
        assertNotNull("item written by iOS", milk)
        assertEquals("厨房 › 冰箱 › 冷藏中层", store.locationText(milk!!))
        assertEquals(2, milk.wearLog.size)
        assertNotNull(milk.expiresAt)
        assertEquals(IconRef.symbol("cup.and.saucer"), milk.icon)
        // iOS also bumped the Android cup's quantity.
        assertEquals(5.0, store.items.values.first { it.name == "安卓杯子" }.quantity, 0.0)
        val iosIcon = store.icons.values.first { it.name == "苹果画的杯子" }
        assertNotNull(SvgDocument.parse(iosIcon.svg))
        assertEquals("苹果改的名字", store.settings.homeName)
        api.deleteAccount("secret123")
    }
}
