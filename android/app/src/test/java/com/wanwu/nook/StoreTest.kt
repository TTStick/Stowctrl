package com.wanwu.nook

import com.wanwu.nook.data.Catalog
import com.wanwu.nook.data.DefaultCategories
import com.wanwu.nook.data.DeletePolicy
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.data.HomeStore
import com.wanwu.nook.data.IconRef
import com.wanwu.nook.data.Item
import com.wanwu.nook.data.NookJson
import com.wanwu.nook.data.Room
import com.wanwu.nook.data.RoomType
import com.wanwu.nook.data.SyncChange
import com.wanwu.nook.image.PathOp
import com.wanwu.nook.image.SvgDocument
import com.wanwu.nook.image.SvgPath
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StoreTest {
    private fun store() = HomeStore(Files.createTempDirectory("nook").toFile())

    @Test fun buildHomeCreatesFurnishedRoomsWithStacking() {
        val s = store()
        s.buildHome("测试之家", listOf(RoomType.kitchen to 1, RoomType.bedroom to 2, RoomType.balcony to 1), true)
        assertTrue(s.didOnboard)
        assertEquals(setOf("厨房", "主卧", "次卧", "阳台"), s.rooms.values.map { it.name }.toSet())
        assertEquals(DefaultCategories.all.size, s.categories.size)
        val microwave = s.furniture.values.first { it.kind == "microwave" }
        assertEquals("base_cabinet", s.furniture[microwave.stackedOnId!!]!!.kind)
        val dryer = s.furniture.values.first { it.kind == "dryer" }
        assertEquals("washer", s.furniture[dryer.stackedOnId!!]!!.kind)
        for (f in s.furniture.values) {
            val r = s.rooms[f.roomId]!!
            assertTrue("${f.name} fits", f.x >= 0 && f.y >= 0 && f.rect.maxX <= r.width + 1e-6 && f.rect.maxY <= r.depth + 1e-6)
        }
    }

    @Test fun movingAndRotatingCarriesStackedPieces() {
        val s = store()
        val room = s.addRoom(RoomType.kitchen, "厨房", 400.0, 300.0)
        val cabinet = s.addFurniture(Catalog.kind("base_cabinet"), room.id)!!
        val micro = s.addFurniture(Catalog.kind("microwave"), room.id)!!
        s.setStack(micro.id, cabinet.id)
        val before = s.furniture[micro.id]!!
        assertTrue(s.furniture[cabinet.id]!!.rect.contains(before.centerX, before.centerY))
        s.moveFurniture(cabinet.id, cabinet.x + 100, cabinet.y + 50)
        val after = s.furniture[micro.id]!!
        assertEquals(100.0, after.x - before.x, 1e-6)
        assertEquals(50.0, after.y - before.y, 1e-6)
        s.rotateFurniture(cabinet.id)
        assertEquals(1, s.furniture[cabinet.id]!!.rotation)
        assertEquals(1, s.furniture[micro.id]!!.rotation)
        val c = s.furniture[cabinet.id]!!; val m = s.furniture[micro.id]!!
        assertTrue(c.rect.inset(-1.0).contains(m.centerX, m.centerY))
        s.setStack(cabinet.id, micro.id)
        assertNull("no cycles", s.furniture[cabinet.id]!!.stackedOnId)
    }

    @Test fun deletePoliciesKeepOrRemoveItems() {
        val s = store()
        val room = s.addRoom(RoomType.bedroom, "卧室")
        val wardrobe = s.addFurniture(Catalog.kind("wardrobe"), room.id)!!
        val rail = s.compartmentsOf(wardrobe.id)[0]
        val shirt = Item(name = "衬衫", compartmentId = rail.id)
        s.save(shirt); s.save(Item(name = "大衣", compartmentId = rail.id))
        assertEquals("卧室 › 衣柜 › 挂衣区", s.locationText(s.items[shirt.id]!!))
        s.deleteCompartment(rail.id, DeletePolicy.KeepItems)
        assertEquals(2, s.unsortedItems.size)
        val drawer = s.compartmentsOf(wardrobe.id)[0]
        s.moveItems(s.items.keys.toList(), drawer.id)
        s.deleteRoom(room.id, DeletePolicy.DeleteItems)
        assertTrue(s.items.isEmpty() && s.furniture.isEmpty() && s.compartments.isEmpty() && s.rooms.isEmpty())
    }

    @Test fun persistenceRoundTrip() {
        val dir = Files.createTempDirectory("nook").toFile()
        val a = HomeStore(dir)
        val room = a.addRoom(RoomType.study, "书房")
        a.save(Item(name = "台灯", icon = IconRef.symbol("lamp.desk"), tags = listOf("电器")))
        a.saveNow()
        val b = HomeStore(dir)
        assertEquals("书房", b.rooms[room.id]?.name)
        assertEquals(IconRef.symbol("lamp.desk"), b.items.values.first().icon)
        assertTrue(b.pendingCount > 0)
    }

    @Test fun remoteApplyRespectsNewerLocalEdits() {
        val s = store()
        s.save(Item(name = "本地名字"))
        val item = s.items.values.first()
        fun change(name: String, at: Long) = SyncChange("item", item.id, at, false,
            NookJson.encodeToJsonElement(Item.serializer(), item.copy(name = name)))
        s.applyRemote(listOf(change("旧的", item.updatedAt - 1000)))
        assertEquals("本地名字", s.items[item.id]!!.name)
        s.applyRemote(listOf(change("新的", item.updatedAt + 1000)))
        assertEquals("新的", s.items[item.id]!!.name)
        s.applyRemote(listOf(SyncChange("item", item.id, item.updatedAt + 2000, true, null)))
        assertNull(s.items[item.id])
    }

    @Test fun repairOrphansAfterConcurrentDeletes() {
        val s = store()
        val room = s.addRoom(RoomType.living, "客厅")
        val shelf = s.addFurniture(Catalog.kind("bookshelf"), room.id)!!
        s.save(Item(name = "书", compartmentId = s.compartmentsOf(shelf.id)[0].id))
        s.applyRemote(listOf(SyncChange("furniture", shelf.id, System.currentTimeMillis() + 5000, true, null)))
        s.repairOrphans()
        assertTrue(s.compartments.isEmpty())
        assertEquals(listOf("书"), s.unsortedItems.map { it.name })
    }

    /** Exactly what iOS's JSONEncoder writes: upper-case ids, fractional ms dates, nils omitted. */
    @Test fun decodesRecordsWrittenByIOS() {
        val json = """{"id":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F","roomId":"9B1DEB4D-3B7D-4BAD-9BDD-2B0D7B3DCB6D",
            "kind":"fridge","name":"冰箱","x":5,"y":5,"width":70,"depth":70,"rotation":1,
            "notes":"","haEntities":["sensor.fridge_temp"],"createdAt":1792885711234.5679,"updatedAt":1792885711300.25,
            "futureField":{"a":1}}"""
        val f = NookJson.decodeFromString(Furniture.serializer(), json)
        assertEquals("E621E1F8-C36C-495A-93FC-0C247A3E6E5F", f.id)
        assertEquals(1792885711235L, f.createdAt)
        assertEquals(70.0, f.footprintWidth, 0.0)
        assertNull(f.stackedOnId)
        val item = NookJson.decodeFromString(Item.serializer(),
            """{"id":"1B4E28BA-2FA1-11D2-883F-0016D3CCA427","name":"牛奶","quantity":2,"icon":{"kind":"symbol","value":"mug"},"wearLog":[1792885711234.5]}""")
        assertEquals(2.0, item.quantity, 0.0)
        assertEquals("个", item.unit)
        assertEquals(listOf(1792885711235L), item.wearLog)
        val room = NookJson.decodeFromString(Room.serializer(), """{"id":"a1b2c3d4-0000-0000-0000-000000000001","name":"厨房","type":"garage"}""")
        assertEquals("A1B2C3D4-0000-0000-0000-000000000001", room.id)
        assertEquals(RoomType.other, room.roomType)
    }

    @Test fun encodesRecordsTheWayIOSReadsThem() {
        val f = Furniture(roomId = "abc-1", kind = "sofa", name = "沙发", x = 1.0, y = 2.0, width = 220.0, depth = 90.0)
        val obj = NookJson.encodeToJsonElement(Furniture.serializer(), f).jsonObject
        assertEquals(f.id, obj["id"]!!.jsonPrimitive.content)
        assertEquals(f.id.uppercase(), f.id)
        assertEquals("ABC-1", obj["roomId"]!!.jsonPrimitive.content)
        assertEquals(f.createdAt, obj["createdAt"]!!.jsonPrimitive.long)
        assertFalse("nils are omitted like Swift does", obj.containsKey("stackedOnId"))
    }

    @Test fun svgParsesServerStyleIcon() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" stroke="currentColor" fill="none" stroke-width="2.5">
            <g stroke-linecap="round"><path d="M10 10 L38 38 m-4 0 h4 v-4"/></g>
            <circle cx="24" cy="24" r="6" fill="currentColor"/><rect x="4" y="4" width="40" height="40" rx="6"/>
            <polyline points="4,44 24,30 44,44"/><ellipse cx="24" cy="12" rx="8" ry="4"/><line x1="0" y1="0" x2="48" y2="48"/></svg>"""
        val doc = SvgDocument.parse(svg)
        assertNotNull(doc)
        assertEquals(6, doc!!.shapes.size)
        assertTrue(doc.shapes[0].stroke)
        assertFalse(doc.shapes[0].fill)
        assertEquals("round", doc.shapes[0].lineCap)
        assertTrue(doc.shapes[1].fill)
        assertNull(SvgDocument.parse("<svg viewBox='0 0 10 10'><script>x</script></svg>"))
        assertNull(SvgDocument.parse("not svg"))
    }

    @Test fun svgArcEndsAtTargetAndCompactNumbers() {
        val ops = SvgPath.parse("M10 20 a10 10 0 01 20 0")!!
        val last = ops.last() as PathOp.CubicTo
        assertEquals(30f, last.x, 0.01f)
        assertEquals(20f, last.y, 0.01f)
        assertNotNull(SvgPath.parse("M1.5.5L-2-3c1,1 2,2 3,3s1 1 2 2q1 1 2 2t2 2zM0 0H10V10H0Z"))
        assertNull(SvgPath.parse("10 10"))
    }
}
