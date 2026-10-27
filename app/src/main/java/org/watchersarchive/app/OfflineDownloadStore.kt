package org.watchersarchive.app

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Explicit offline content is persistent user storage, separate from disposable page caches. */
data class OfflineDownloadSpec(
    val comicId: String,
    val title: String,
    val url: String,
    val eTag: String,
    val length: Long,
    val fileName: String? = null,
) {
    init {
        require(comicId.isNotBlank() && comicId.length <= 4096) { "漫画标识无效" }
        require(title.length <= 4096 && url.length <= 16_384 && eTag.length <= 4096) { "下载信息过长" }
        val parsed = URI(url)
        require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.fragment == null) { "下载地址无效" }
        require(eTag.length >= 2 && eTag.first() == '"' && eTag.last() == '"' &&
            eTag.substring(1, eTag.lastIndex).all { it.code >= 0x21 && it != '"' && it.code != 0x7f }) { "下载来源缺少可靠的版本标识" }
        require(length in 22..(64L * 1024 * 1024 * 1024)) { "漫画文件大小无效或超出支持范围" }
    }
    internal val assetKey: String get() = offlineDigest("$comicId\u0000$url\u0000$eTag\u0000$length")
}

enum class OfflineDownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class OfflineDownloadStatus(
    val comicId: String,
    val title: String,
    val state: OfflineDownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String? = null,
)

class CompletedOfflineDownload internal constructor(
    val spec: OfflineDownloadSpec,
    val file: File,
    internal val store: OfflineDownloadStore,
    internal val modified: Long,
)

/**
 * One small durable record per comic and version-hashed private archive names.
 * A process-wide lease table plus OS locks excludes download/removal races, including
 * accidentally constructing two store wrappers for the same private directory.
 */
class OfflineDownloadStore(val directory: File) {
    private data class Record(val spec: OfflineDownloadSpec, val state: OfflineDownloadState, val bytes: Long,
        val message: String? = null, val modified: Long = 0)
    private class Slot {
        var readers = 0
        var writer = false
        var channel: FileChannel? = null
        var fileLock: FileLock? = null
    }
    companion object {
        private val slots = ConcurrentHashMap<String, Slot>()
        private val metadataLocks = ConcurrentHashMap<String, Any>()
    }
    private val root: File
    private val metadataLock: Any

    init {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建离线漫画目录" }
        root = directory.canonicalFile
        metadataLock = metadataLocks.computeIfAbsent(root.path) { Any() }
        synchronized(metadataLock) { recoverInterruptedRecords() }
    }

    fun enqueue(spec: OfflineDownloadSpec): OfflineDownloadStatus = synchronized(metadataLock) {
        val previous = load(spec.comicId)
        if (previous != null && previous.spec.assetKey != spec.assetKey) {
            if (previous.state == OfflineDownloadState.COMPLETED) {
                throw IllegalStateException("该漫画已有其他版本的离线文件，请先删除原下载后再下载新版本")
            }
            exclusive(previous.spec).use {
                listOf(partial(previous.spec), archive(previous.spec)).forEach { old ->
                    if (old.exists() && !old.delete()) error("原下载暂时无法替换，请稍后重试")
                }
            }
        }
        if (previous?.spec?.assetKey == spec.assetKey && previous.state == OfflineDownloadState.COMPLETED && validCompleted(previous)) {
            return@synchronized previous.status()
        }
        if (previous?.spec?.assetKey == spec.assetKey && previous.state == OfflineDownloadState.DOWNLOADING) {
            return@synchronized previous.status()
        }
        if (previous?.spec?.assetKey == spec.assetKey && previous.bytes == spec.length &&
            archive(spec).isFile && archive(spec).length() == spec.length && previous.state != OfflineDownloadState.COMPLETED) {
            // A crash after atomic rename but before the final record write must
            // not force a second complete network download.
            val recovered = previous.copy(state = OfflineDownloadState.COMPLETED, modified = archive(spec).lastModified(), message = null)
            save(recovered)
            return@synchronized recovered.status()
        }
        val bytes = if (previous?.spec?.assetKey == spec.assetKey && previous.state != OfflineDownloadState.COMPLETED) previous.bytes else 0L
        val next = Record(spec, OfflineDownloadState.QUEUED, bytes)
        save(next)
        next.status()
    }

    fun status(comicId: String): OfflineDownloadStatus? = synchronized(metadataLock) { load(comicId)?.status() }
    fun spec(comicId: String): OfflineDownloadSpec? = synchronized(metadataLock) { load(comicId)?.spec }
    fun statuses(): List<OfflineDownloadStatus> = synchronized(metadataLock) {
        directory.listFiles { _, name -> name.matches(Regex("[0-9a-f]{64}\\.json")) }.orEmpty()
            .mapNotNull(::readRecord).map { it.status() }.sortedBy { it.title }
    }
    fun completed(comicId: String): CompletedOfflineDownload? = synchronized(metadataLock) {
        val record = load(comicId) ?: return@synchronized null
        if (record.state != OfflineDownloadState.COMPLETED || !validCompleted(record)) return@synchronized null
        CompletedOfflineDownload(record.spec, archive(record.spec), this, record.modified)
    }

    /** Does not touch source files, shelf membership, progress, notes or page caches. */
    fun remove(comicId: String) = synchronized(metadataLock) {
        val record = load(comicId) ?: return@synchronized
        exclusive(record.spec).use {
            val managed = listOf(partial(record.spec), archive(record.spec), metadata(comicId), pendingMetadata(comicId))
            managed.forEach { file -> if (file.exists() && !file.delete()) error("离线漫画暂时无法删除，请稍后重试") }
        }
    }

    /** Used by a worker before returning paused/failed without an active transfer. */
    fun markPaused(comicId: String, message: String? = null) = synchronized(metadataLock) {
        val record = load(comicId) ?: return@synchronized
        if (record.state != OfflineDownloadState.COMPLETED) save(record.copy(state = OfflineDownloadState.PAUSED, message = message))
    }
    fun markFailed(comicId: String, message: String) = synchronized(metadataLock) {
        val record = load(comicId) ?: return@synchronized
        if (record.state != OfflineDownloadState.COMPLETED) save(record.copy(state = OfflineDownloadState.FAILED, message = message))
    }

    internal fun exclusive(spec: OfflineDownloadSpec): Closeable = acquire(spec, reading = false)
    internal fun acquireReading(download: CompletedOfflineDownload): Closeable = synchronized(metadataLock) {
        val record = load(download.spec.comicId)
        require(record?.spec?.assetKey == download.spec.assetKey && record.state == OfflineDownloadState.COMPLETED && validCompleted(record)) { "离线漫画已改变，请重新打开" }
        acquire(download.spec, reading = true)
    }
    internal fun partial(spec: OfflineDownloadSpec) = managed(spec.assetKey + ".partial")
    internal fun archive(spec: OfflineDownloadSpec) = managed(spec.assetKey + ".archive")
    internal fun downloaded(spec: OfflineDownloadSpec): Long = synchronized(metadataLock) {
        val record = load(spec.comicId) ?: return@synchronized 0L
        require(record.spec.assetKey == spec.assetKey) { "离线下载版本已改变" }
        record.bytes
    }
    internal fun update(spec: OfflineDownloadSpec, state: OfflineDownloadState, bytes: Long, message: String? = null) = synchronized(metadataLock) {
        require(bytes in 0..spec.length)
        val current = load(spec.comicId)
        require(current == null || current.spec.assetKey == spec.assetKey) { "离线下载版本已改变" }
        save(Record(spec, state, bytes, message))
    }
    internal fun finish(spec: OfflineDownloadSpec): CompletedOfflineDownload = synchronized(metadataLock) {
        val partial = partial(spec)
        require(partial.length() == spec.length) { "离线漫画尚未下载完整" }
        atomicMove(partial, archive(spec))
        val finished = Record(spec, OfflineDownloadState.COMPLETED, spec.length, modified = archive(spec).lastModified())
        save(finished)
        CompletedOfflineDownload(spec, archive(spec), this, finished.modified)
    }

    private fun acquire(spec: OfflineDownloadSpec, reading: Boolean): Closeable {
        val key = root.path + File.separator + spec.assetKey
        val slot = slots.computeIfAbsent(key) { Slot() }
        synchronized(slot) {
            if (slot.writer || (!reading && slot.readers > 0)) error("这部漫画正在使用中，请结束阅读或下载后再操作")
            if (slot.readers == 0) {
                val channel = RandomAccessFile(managed(spec.assetKey + ".lock"), "rw").channel
                val lock = try { channel.tryLock(0, Long.MAX_VALUE, reading) }
                catch (error: Exception) { channel.close(); throw IllegalStateException("这部漫画正在使用中，请稍后重试", error) }
                if (lock == null) { channel.close(); error("这部漫画正在使用中，请稍后重试") }
                slot.channel = channel; slot.fileLock = lock
            }
            if (reading) slot.readers++ else slot.writer = true
        }
        var closed = false
        return Closeable {
            synchronized(slot) {
                if (!closed) {
                    closed = true
                    if (reading) slot.readers-- else slot.writer = false
                    if (!slot.writer && slot.readers == 0) {
                        slot.fileLock?.release(); slot.channel?.close(); slot.fileLock = null; slot.channel = null
                    }
                }
            }
        }
    }

    private fun recoverInterruptedRecords() {
        directory.listFiles { _, name -> name.matches(Regex("[0-9a-f]{64}\\.json")) }.orEmpty().forEach { file ->
            val record = readRecord(file) ?: return@forEach
            if (record.state == OfflineDownloadState.COMPLETED) return@forEach
            val lock = try { exclusive(record.spec) } catch (_: IllegalStateException) { return@forEach }
            lock.use {
                val ready = archive(record.spec)
                if (record.bytes == record.spec.length && ready.length() == record.spec.length) {
                    save(record.copy(state = OfflineDownloadState.COMPLETED, modified = ready.lastModified(), message = null))
                } else {
                    val part = partial(record.spec)
                    val checkpoint = minOf(record.bytes, if (part.isFile) part.length() else 0L)
                    if (part.isFile && part.length() != checkpoint) RandomAccessFile(part, "rw").use { it.setLength(checkpoint) }
                    save(record.copy(state = OfflineDownloadState.PAUSED, bytes = checkpoint, message = null))
                }
            }
        }
        // .pending is never authoritative. It has one bounded name per comic and
        // is replaced by the next durable write; do not remove another process's
        // potentially active record write during directory recovery.
    }
    private fun validCompleted(record: Record): Boolean {
        val file = archive(record.spec)
        return file.isFile && file.length() == record.spec.length && file.lastModified() == record.modified
    }
    private fun Record.status(): OfflineDownloadStatus {
        if (state == OfflineDownloadState.COMPLETED && !validCompleted(this)) {
            return OfflineDownloadStatus(spec.comicId, spec.title, OfflineDownloadState.FAILED, 0L, spec.length, "离线文件已变化，请重新下载")
        }
        return OfflineDownloadStatus(spec.comicId, spec.title, state, bytes, spec.length, message)
    }
    private fun metadata(comicId: String) = managed(offlineDigest(comicId) + ".json")
    private fun pendingMetadata(comicId: String) = managed(offlineDigest(comicId) + ".json.pending")
    private fun load(comicId: String): Record? = readRecord(metadata(comicId))?.takeIf { it.spec.comicId == comicId }
    private fun readRecord(file: File): Record? = runCatching {
        if (!file.isFile || file.length() > 64 * 1024) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val spec = OfflineDownloadSpec(json.getString("id"), json.getString("title"), json.getString("url"), json.getString("etag"),
            json.getLong("length"), json.optString("fileName").takeIf { it.isNotBlank() })
        require(file.name == offlineDigest(spec.comicId) + ".json")
        val bytes = json.getLong("bytes"); require(bytes in 0..spec.length)
        Record(spec, OfflineDownloadState.valueOf(json.getString("state")), bytes,
            json.optString("message").takeIf { it.isNotBlank() }, json.optLong("modified"))
    }.getOrNull()
    private fun save(record: Record) {
        val spec = record.spec
        val content = JSONObject().put("id", spec.comicId).put("title", spec.title).put("url", spec.url)
            .put("etag", spec.eTag).put("length", spec.length).put("fileName", spec.fileName.orEmpty())
            .put("state", record.state.name).put("bytes", record.bytes).put("message", record.message.orEmpty())
            .put("modified", record.modified).toString().toByteArray(Charsets.UTF_8)
        require(content.size <= 64 * 1024) { "下载信息过长，暂时无法保存" }
        FileOutputStream(pendingMetadata(spec.comicId)).use { it.write(content); it.fd.sync() }
        atomicMove(pendingMetadata(spec.comicId), metadata(spec.comicId))
    }
    private fun managed(name: String): File = File(root, name).also { check(it.canonicalFile.parentFile == root) { "非法离线文件路径" } }
    private fun atomicMove(source: File, destination: File) {
        try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

private fun offlineDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
