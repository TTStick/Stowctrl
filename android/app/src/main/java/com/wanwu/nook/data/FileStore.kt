package com.wanwu.nook.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Photo files on disk, named by id ("<UUID>.jpg" / "<UUID>.png"). */
class FileStore(val directory: File) {
    /** Fetches a file this device doesn't have yet (set by the sync engine). */
    var remoteFetcher: (suspend (String) -> ByteArray?)? = null

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray?>>()

    init { directory.mkdirs() }

    fun file(id: String) = File(directory, id)
    fun exists(id: String) = file(id).exists()
    fun write(bytes: ByteArray, id: String) {
        val tmp = File(directory, ".$id.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file(id))
    }
    fun read(id: String): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()
    fun delete(id: String) {
        file(id).delete()
        synchronized(cache) { cache.snapshot().keys.filter { it.startsWith("$id@") }.forEach { cache.remove(it) } }
    }
    fun deleteAll() {
        directory.deleteRecursively()
        directory.mkdirs()
        synchronized(cache) { cache.evictAll() }
    }
    fun allIds(): List<String> = directory.list()?.filter { !it.startsWith(".") } ?: emptyList()

    /** Local bytes, or the server's copy (saved locally) when missing. */
    suspend fun loadBytes(id: String): ByteArray? {
        withContext(Dispatchers.IO) { read(id) }?.let { return it }
        val fetcher = remoteFetcher ?: return null
        val (deferred, owner) = synchronized(inFlight) {
            inFlight[id]?.let { it to false } ?: CompletableDeferred<ByteArray?>().also { inFlight[id] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        val result = runCatching { fetcher(id) }.getOrNull()
        if (result != null) withContext(Dispatchers.IO) { write(result, id) }
        synchronized(inFlight) { inFlight.remove(id) }
        deferred.complete(result)
        return result
    }

    private fun bucket(maxPixel: Int) = listOf(64, 128, 256, 512, 1024, 2048).firstOrNull { it >= maxPixel } ?: 2048

    fun cached(id: String, maxPixel: Int): Bitmap? = synchronized(cache) { cache.get("$id@${bucket(maxPixel)}") }

    /** Downsampled bitmap for display, cached in memory. */
    suspend fun bitmap(id: String, maxPixel: Int): Bitmap? {
        val b = bucket(maxPixel)
        cached(id, maxPixel)?.let { return it }
        val bytes = loadBytes(id) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeSampled(bytes, b) } ?: return null
        synchronized(cache) { cache.put("$id@$b", bmp) }
        return bmp
    }

    companion object {
        fun decodeSampled(bytes: ByteArray, maxPixel: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixel) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val longest = maxOf(decoded.width, decoded.height)
            if (longest <= maxPixel) return decoded
            val scale = maxPixel.toFloat() / longest
            return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
        }
    }
}
