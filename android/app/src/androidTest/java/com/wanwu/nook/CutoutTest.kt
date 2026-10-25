package com.wanwu.nook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wanwu.nook.image.ColorExtractor
import com.wanwu.nook.image.CutoutService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the on-device cut-out chain on a real photo (bundled in the test APK). */
@RunWith(AndroidJUnit4::class)
class CutoutTest {
    @Test fun cutsOutMugOnDevice() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val photo = inst.context.assets.open("mug.jpg").use { BitmapFactory.decodeStream(it) }
        val result = CutoutService(inst.targetContext).cutout(photo, engine = "device", server = null)
        Log.i("NOOKCUTOUT", "engine=${result.engine} size=${result.image.width}x${result.image.height}")
        val img = result.image
        assertTrue("cropped to the subject", img.width < photo.width && img.height < photo.height)
        assertEquals("corner is transparent", 0, Color.alpha(img.getPixel(0, 0)))
        assertTrue("centre is opaque", Color.alpha(img.getPixel(img.width / 2, img.height / 2)) > 200)
        val colors = ColorExtractor.palette(img)
        Log.i("NOOKCUTOUT", "colors=$colors")
        assertTrue(colors.isNotEmpty())
        val dir = File(inst.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "30-cutout-${result.engine}.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
