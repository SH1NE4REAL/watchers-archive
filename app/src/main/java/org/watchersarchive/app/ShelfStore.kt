package org.watchersarchive.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** Small persistent shelf store for the Phase 1 catalogue shell. */
class ShelfStore(private val file: File) {
    private val pending = File(file.parentFile, file.name + ".pending")
    private val properties = Properties()
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        if (file.isFile) file.inputStream().use { properties.load(it) }
        if (pending.exists()) pending.delete()
        if (shelves().isEmpty()) createShelf("默认书架")
    }

    fun shelves(): List<String> = synchronized(lock) {
        properties.stringPropertyNames().filter { it.startsWith("shelf.") && it.endsWith(".name") }
            .map { properties.getProperty(it) }.sorted()
    }

    fun createShelf(name: String) = synchronized(lock) {
        val clean = name.trim()
        require(clean.isNotBlank() && clean.length <= 40 && clean != "全部") { "分类名称需为 1–40 个字，且不能使用“全部”。" }
        val key = encode(clean)
        if (!properties.containsKey("shelf.$key.name")) {
            properties.setProperty("shelf.$key.name", clean)
            properties.setProperty("shelf.$key.items", "")
            commit()
        }
    }

    fun add(shelfName: String, comicId: String) = synchronized(lock) {
        if (!properties.containsKey("shelf.${encode(shelfName)}.name")) createShelf(shelfName)
        val key = "shelf.${encode(shelfName)}.items"
        val items = properties.getProperty(key, "").split(',').filter { it.isNotBlank() }.toMutableSet()
        if (items.add(comicId)) { properties.setProperty(key, items.joinToString(",")); commit() }
    }

    fun items(shelfName: String): Set<String> = synchronized(lock) {
        properties.getProperty("shelf.${encode(shelfName)}.items", "").split(',').filter { it.isNotBlank() }.toSet()
    }

    fun allItems(): Set<String> = synchronized(lock) { shelves().flatMap { items(it) }.toSet() }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun commit() {
        pending.outputStream().use { output -> properties.store(output, "Watchers Archive shelves"); output.flush(); output.fd.sync() }
        runCatching { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

