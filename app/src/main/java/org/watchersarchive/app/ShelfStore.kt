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

    /** Only collection membership changes; source records and user reading data are untouched. */
    fun remove(shelfName: String, comicId: String) = synchronized(lock) {
        val previous = Properties().also { it.putAll(properties) }
        try {
            val names = if (shelfName == "全部") shelves() else listOf(shelfName)
            names.forEach { name ->
                val key = "shelf.${encode(name)}.items"
                if (properties.containsKey(key)) properties.setProperty(key, (items(name) - comicId).joinToString(","))
            }
            commit()
        } catch (failure: Throwable) { properties.clear(); properties.putAll(previous); throw failure }
    }

    /** Move a root book or one category membership in one atomic snapshot. */
    fun move(from: String?, to: String, comicId: String) = synchronized(lock) {
        val clean = to.trim()
        require(clean.isNotBlank() && clean.length <= 40 && clean != "全部") { "请选择有效的分类。" }
        val previous = Properties().also { it.putAll(properties) }
        try {
            if (from == null || from == "全部") {
                shelves().forEach { name -> properties.setProperty("shelf.${encode(name)}.items", (items(name) - comicId).joinToString(",")) }
            } else {
                val key = "shelf.${encode(from)}.items"
                properties.setProperty(key, (items(from) - comicId).joinToString(","))
            }
            val prefix = "shelf.${encode(clean)}."
            properties.setProperty(prefix + "name", clean)
            properties.setProperty(prefix + "items", (items(clean) + comicId).joinToString(","))
            commit()
        } catch (failure: Throwable) { properties.clear(); properties.putAll(previous); throw failure }
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun commit() {
        pending.outputStream().use { output -> properties.store(output, "Watchers Archive shelves"); output.flush(); output.fd.sync() }
        runCatching { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

