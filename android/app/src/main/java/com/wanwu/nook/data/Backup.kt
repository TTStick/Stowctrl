package com.wanwu.nook.data

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Full backups that need no server — same format as the iOS app
 * (ios/Nook/Services/Backup.swift): a .zip with nook-backup.json (every
 * record, in the sync server's shape) and files/<id> (the photos).
 */
object Backup {
    const val MANIFEST = "nook-backup.json"

    @Serializable
    data class Manifest(
        val format: String = "nook-backup",
        val version: Int = 1,
        @Serializable(with = Millis::class) val exportedAt: Long,
        val platform: String,
        val homeName: String,
        val records: List<SyncChange>,
    )

    /** A parsed backup, with its photos already extracted to a temp folder. */
    class Loaded(val manifest: Manifest, val photoDir: File) {
        val photoCount get() = photoDir.listFiles()?.size ?: 0
        fun count(entity: String) = manifest.records.count { it.entity == entity && !it.deleted }
    }

    private val safeId = Regex("^[A-Za-z0-9-]{1,64}\\.(jpg|jpeg|png|heic|webp)$", RegexOption.IGNORE_CASE)

    /** Photo ids are "<UUID>.jpg/png"; anything else (e.g. "../x") is ignored. */
    fun isSafeFileId(id: String) = safeId.matches(id)

    suspend fun export(store: HomeStore, out: OutputStream) {
        val manifest = Manifest(exportedAt = now(), platform = "android", homeName = store.settings.homeName, records = store.backupChanges())
        val ids = store.items.values.flatMap { listOfNotNull(it.photoId, it.originalPhotoId) }.toSortedSet().filter(::isSafeFileId)
        // Photos only on the server are fetched first when signed in.
        val photos = ids.mapNotNull { id -> store.files.loadBytes(id)?.let { id to it } }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(NookJson.encodeToString(Manifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            for ((id, bytes) in photos) {
                zip.putNextEntry(ZipEntry("files/$id"))
                zip.write(bytes)
                zip.closeEntry()
            }
        } }
    }

    fun load(input: InputStream, workDir: File): Loaded {
        val photoDir = File(workDir, "restore-${newId()}").apply { mkdirs() }
        var manifest: Manifest? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == MANIFEST -> manifest = runCatching {
                        NookJson.decodeFromString(Manifest.serializer(), zip.readBytes().decodeToString())
                    }.getOrNull()
                    entry.name.startsWith("files/") -> {
                        val id = entry.name.removePrefix("files/")
                        if (isSafeFileId(id)) File(photoDir, id).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val m = manifest?.takeIf { it.format == "nook-backup" } ?: throw IllegalArgumentException("这不是万物收纳的备份文件")
        return Loaded(m, photoDir)
    }

    fun restore(loaded: Loaded, store: HomeStore, replace: Boolean): HomeStore.ImportSummary {
        var photos = 0
        loaded.photoDir.listFiles()?.forEach { f ->
            if (!isSafeFileId(f.name)) return@forEach
            if (!store.files.exists(f.name)) store.files.write(f.readBytes(), f.name)
            store.registerUpload(f.name)
            photos++
        }
        loaded.photoDir.deleteRecursively()
        return store.importBackup(loaded.manifest.records, replace).also { it.photos = photos }
    }
}
