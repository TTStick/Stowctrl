package com.wanwu.nook.image

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.wanwu.nook.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** One subject found by ML Kit, positioned inside the source photo. */
class SubjectPiece(val bitmap: Bitmap, val x: Int, val y: Int)

/** Result of a cut-out: the combined image and (for ML Kit) each subject separately. */
class CutoutResult(val image: Bitmap, val subjects: List<SubjectPiece>, val engine: String)

/**
 * Background removal, on-device first:
 * 1. ML Kit subject segmentation (needs Google Play services and its model),
 * 2. the bundled U²-Net-p model through ONNX Runtime (fully offline),
 * 3. the user's own server (rembg), if enabled.
 */
class CutoutService(private val context: Context) {
    private var session: OrtSession? = null

    suspend fun cutout(source: Bitmap, engine: String, server: ApiClient?): CutoutResult {
        var lastError: Throwable? = null
        if (engine != "server") {
            runCatching { return mlKit(source) }.onFailure { lastError = it }
            runCatching { return u2net(source) }.onFailure { lastError = it }
        }
        if (engine != "device" && server != null) {
            runCatching {
                val png = server.cutout(ImageTools.jpeg(source, 1600))
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("bad image")
                val trimmed = ImageTools.trimTransparent(bmp)
                return CutoutResult(trimmed, emptyList(), "server")
            }.onFailure { lastError = it }
        }
        throw IllegalStateException(lastError?.message ?: "抠图失败", lastError)
    }

    private suspend fun mlKit(source: Bitmap): CutoutResult {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build())
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val result = withTimeout(15_000) { segmenter.process(InputImage.fromBitmap(source, 0)).await() }
            val fg = result.foregroundBitmap ?: error("没有识别到主体")
            val subjects = result.subjects.mapNotNull { sub -> sub.bitmap?.let { SubjectPiece(it, sub.startX, sub.startY) } }
            return CutoutResult(ImageTools.trimTransparent(fg), subjects, "mlkit")
        } finally {
            segmenter.close()
        }
    }

    /** Recombines the chosen ML Kit subjects, each at its place in the photo. */
    fun combine(source: Bitmap, result: CutoutResult, keep: Set<Int>): Bitmap {
        if (result.subjects.isEmpty() || keep.size == result.subjects.size) return result.image
        val canvas = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        keep.mapNotNull { result.subjects.getOrNull(it) }.forEach { c.drawBitmap(it.bitmap, it.x.toFloat(), it.y.toFloat(), null) }
        return ImageTools.trimTransparent(canvas)
    }

    private suspend fun u2net(source: Bitmap): CutoutResult = withContext(Dispatchers.Default) {
        val env = OrtEnvironment.getEnvironment()
        val s = session ?: env.createSession(context.assets.open("u2netp.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
            .also { session = it }
        val size = 320
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        // rembg normalisation: divide by the max channel value, then ImageNet mean/std.
        var maxV = 1f
        for (p in px) maxV = maxOf(maxV, ((p shr 16) and 255).toFloat(), ((p shr 8) and 255).toFloat(), (p and 255).toFloat())
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * size * size)
        for (ch in 0 until 3) {
            val shift = 16 - 8 * ch
            for (p in px) input.put((((p shr shift) and 255) / maxV - mean[ch]) / std[ch])
        }
        input.rewind()
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong()))
        val mask = tensor.use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val arr = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for (row in arr) for (v in row) { lo = minOf(lo, v); hi = maxOf(hi, v) }
                val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
                IntArray(size * size) { i -> (((arr[i / size][i % size] - lo) / range) * 255).roundToInt().coerceIn(0, 255) }
            }
        }
        // Upscale the 320×320 mask bilinearly and apply it as alpha.
        val maskBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(IntArray(size * size) { Color.argb(mask[it], 0, 0, 0) }, 0, size, 0, 0, size, size)
        val fullMask = Bitmap.createScaledBitmap(maskBmp, source.width, source.height, true)
        val w = source.width; val h = source.height
        val src = IntArray(w * h); source.getPixels(src, 0, w, 0, 0, w, h)
        val m = IntArray(w * h); fullMask.getPixels(m, 0, w, 0, 0, w, h)
        var covered = 0
        for (i in src.indices) {
            val a = Color.alpha(m[i])
            if (a > 128) covered++
            src[i] = (src[i] and 0x00FFFFFF) or (a shl 24)
        }
        if (covered < src.size / 200) error("没有识别到主体，可以换个角度或背景再拍一张")
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(src, 0, w, 0, 0, w, h)
        CutoutResult(ImageTools.trimTransparent(out), emptyList(), "u2net")
    }
}

object ImageTools {
    fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxSide) return b
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    fun jpeg(b: Bitmap, maxSide: Int = 1600): ByteArray =
        ByteArrayOutputStream().also { scaled(b, maxSide).compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()

    fun png(b: Bitmap, maxSide: Int = 1024): ByteArray =
        ByteArrayOutputStream().also { scaled(b, maxSide).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Crops transparent borders and adds a 4% margin. */
    fun trimTransparent(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (Color.alpha(px[y * w + x]) > 12) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < minX) return b
        val cropped = Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1)
        val pad = (maxOf(cropped.width, cropped.height) * 0.04f).roundToInt()
        val out = Bitmap.createBitmap(cropped.width + pad * 2, cropped.height + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(cropped, pad.toFloat(), pad.toFloat(), null)
        return out
    }
}

/** Dominant colours of a garment, ignoring transparent pixels (same K-means as iOS). */
object ColorExtractor {
    fun palette(b: Bitmap, count: Int = 3): List<String> {
        val side = 64
        val small = Bitmap.createScaledBitmap(b, side, side, true)
        val px = IntArray(side * side); small.getPixels(px, 0, side, 0, 0, side, side)
        var samples = px.filter { Color.alpha(it) > 200 }.map { floatArrayOf(Color.red(it).toFloat(), Color.green(it).toFloat(), Color.blue(it).toFloat()) }
        if (samples.size == side * side) {
            samples = buildList {
                for (y in side / 4 until side * 3 / 4) for (x in side / 4 until side * 3 / 4) {
                    val p = px[y * side + x]; add(floatArrayOf(Color.red(p).toFloat(), Color.green(p).toFloat(), Color.blue(p).toFloat()))
                }
            }
        }
        if (samples.size < 10) return emptyList()
        val k = minOf(5, samples.size)
        val centers = Array(k) { samples[it * samples.size / k].copyOf() }
        val assign = IntArray(samples.size)
        repeat(12) {
            samples.forEachIndexed { i, s ->
                var best = 0; var bestD = Float.MAX_VALUE
                centers.forEachIndexed { j, c ->
                    val d = (s[0] - c[0]) * (s[0] - c[0]) + (s[1] - c[1]) * (s[1] - c[1]) + (s[2] - c[2]) * (s[2] - c[2])
                    if (d < bestD) { bestD = d; best = j }
                }
                assign[i] = best
            }
            val sums = Array(k) { FloatArray(3) }; val counts = IntArray(k)
            samples.forEachIndexed { i, s -> val a = assign[i]; counts[a]++; for (c in 0..2) sums[a][c] += s[c] }
            for (j in 0 until k) if (counts[j] > 0) for (c in 0..2) centers[j][c] = sums[j][c] / counts[j]
        }
        val counts = IntArray(k); assign.forEach { counts[it]++ }
        val result = mutableListOf<FloatArray>()
        for (j in (0 until k).sortedByDescending { counts[it] }) {
            if (counts[j] <= samples.size / 30) continue
            val c = centers[j]
            if (result.any { r -> (r[0] - c[0]) * (r[0] - c[0]) + (r[1] - c[1]) * (r[1] - c[1]) + (r[2] - c[2]) * (r[2] - c[2]) < 900 }) continue
            result += c
        }
        return result.take(count).map { "#%02x%02x%02x".format(it[0].roundToInt().coerceIn(0, 255), it[1].roundToInt().coerceIn(0, 255), it[2].roundToInt().coerceIn(0, 255)) }
    }
}
