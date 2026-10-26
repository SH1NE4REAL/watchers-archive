package org.watchersarchive.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** Small atomic source snapshot store used until the catalogue moves to Room. */
class CatalogMetadataStore(private val file: File) {
    private val pending = File(file.parentFile, file.name + ".pending")
    private val properties = Properties()
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        if (file.isFile) file.inputStream().use { properties.load(it) }
        if (pending.exists()) pending.delete()
    }

    fun upsertAll(records: List<CatalogSourceRecord>) = synchronized(lock) {
        records.forEach { record ->
            val prefix = "record.${encode(record.externalId)}."
            properties.setProperty(prefix + "source", record.source)
            properties.setProperty(prefix + "id", record.externalId)
            properties.setProperty(prefix + "url", record.canonicalUrl)
            properties.setProperty(prefix + "title", record.title)
            properties.setProperty(prefix + "synopsis", record.synopsis.orEmpty())
            properties.setProperty(prefix + "cover", record.coverUrl.orEmpty())
            properties.setProperty(prefix + "published", record.sourcePublishedAt.orEmpty())
            properties.setProperty(prefix + "modified", record.sourceModifiedAt.orEmpty())
            properties.setProperty(prefix + "discovered", record.firstDiscoveredAt)
            properties.setProperty(prefix + "categories", record.categories.joinToString("\u001e"))
            properties.setProperty(prefix + "year", record.yearLabel.orEmpty())
            properties.setProperty(prefix + "size", record.sizeLabel.orEmpty())
            properties.setProperty(prefix + "format", record.formatLabel.orEmpty())
            properties.setProperty(prefix + "confidence", record.confidence.name)
        }
        commit()
    }

    fun records(): List<CatalogSourceRecord> = synchronized(lock) {
        properties.stringPropertyNames()
            .filter { it.startsWith("record.") && it.endsWith(".id") }
            .mapNotNull { idKey ->
                val prefix = idKey.removeSuffix("id")
                val id = properties.getProperty(idKey) ?: return@mapNotNull null
                CatalogSourceRecord(
                    source = properties.getProperty(prefix + "source", "getcomics"),
                    externalId = id,
                    canonicalUrl = properties.getProperty(prefix + "url", ""),
                    title = properties.getProperty(prefix + "title", id),
                    synopsis = properties.getProperty(prefix + "synopsis", "").ifBlank { null },
                    coverUrl = properties.getProperty(prefix + "cover", "").ifBlank { null },
                    sourcePublishedAt = properties.getProperty(prefix + "published", "").ifBlank { null },
                    sourceModifiedAt = properties.getProperty(prefix + "modified", "").ifBlank { null },
                    firstDiscoveredAt = properties.getProperty(prefix + "discovered", ""),
                    categories = properties.getProperty(prefix + "categories", "").split('\u001e').filter { it.isNotBlank() },
                    yearLabel = properties.getProperty(prefix + "year", "").ifBlank { null },
                    sizeLabel = properties.getProperty(prefix + "size", "").ifBlank { null },
                    formatLabel = properties.getProperty(prefix + "format", "").ifBlank { null },
                    confidence = runCatching { SourceConfidence.valueOf(properties.getProperty(prefix + "confidence", "UNKNOWN")) }
                        .getOrDefault(SourceConfidence.UNKNOWN),
                )
            }
            .sortedByDescending { it.sourcePublishedAt ?: it.firstDiscoveredAt }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun commit() {
        pending.outputStream().use { output ->
            properties.store(output, "Watchers Archive source metadata")
            output.flush()
            output.fd.sync()
        }
        runCatching {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

