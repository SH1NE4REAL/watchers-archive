package org.watchersarchive.app

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** User-owned labels live outside source metadata and the disposable reading cache. */
class PersonalBookNames(private val file: File) {
    private val pending = File(file.parentFile, file.name + ".pending")
    private val lock = Any()
    private var properties = Properties()

    init {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建个人书名目录。" } }
        if (file.isFile) file.inputStream().use { properties.load(it) }
        // A pending snapshot is never authoritative after process interruption.
        if (pending.isFile) pending.delete()
    }

    fun title(comicId: String): String? = synchronized(lock) {
        properties.getProperty(key(comicId))
    }

    fun titles(): Map<String, String> = synchronized(lock) {
        properties.stringPropertyNames().asSequence()
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .mapNotNull { name ->
                runCatching {
                    val id = String(Base64.getUrlDecoder().decode(name.removePrefix(PREFIX).removeSuffix(SUFFIX)), Charsets.UTF_8)
                    val label = properties.getProperty(name)
                    require(id.isNotBlank() && label.isNotBlank())
                    id to label
                }.getOrNull()
            }.toMap()
    }

    /** Null or blank resets to the original source title; no comic file is renamed. */
    fun set(comicId: String, name: String?) = synchronized(lock) {
        val propertyKey = key(comicId)
        val clean = name?.trim()?.takeIf(String::isNotEmpty)
        if (clean != null) {
            require(name.orEmpty().none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) { "书名不能包含换行或控制字符。" }
            require(clean.codePointCount(0, clean.length) <= 120) { "自定义书名最多为 120 个字符。" }
        }
        if (properties.getProperty(propertyKey) == clean) return@synchronized
        val updated = Properties().also { it.putAll(properties) }
        if (clean == null) updated.remove(propertyKey) else updated.setProperty(propertyKey, clean)
        try {
            pending.outputStream().use { output ->
                updated.store(output, "Watchers Archive personal book names")
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            properties = updated
        } catch (failure: Throwable) {
            // Memory keeps the last committed snapshot if disk replacement fails.
            if (pending.isFile) pending.delete()
            throw failure
        }
    }

    private fun key(comicId: String): String {
        require(comicId.isNotBlank() && comicId.length <= 4096) { "漫画标识无效。" }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(comicId.toByteArray(Charsets.UTF_8)) + SUFFIX
    }

    private companion object {
        const val PREFIX = "book."
        const val SUFFIX = ".title"
    }
}
