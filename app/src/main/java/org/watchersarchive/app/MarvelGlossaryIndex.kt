package org.watchersarchive.app

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Shared with the reproducible data builder; names never become file paths. */
internal object MarvelGlossaryNameNormalizer {
    const val VERSION = 1

    fun normalize(input: String): String {
        var value = Normalizer.normalize(input.take(2_000), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace('’', '\'').replace('‘', '\'')
        value = Regex("(?<![a-z])(?:[a-z]\\.){2,}[a-z]?\\.?").replace(value) { it.value.replace(".", "") }
        value = Regex("([a-z][a-z-]{1,80})'s\\b").replace(value, "$1")
        return value.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }
}

/** Resource corruption is different from a valid lookup with no matching name. */
internal class MarvelGlossaryResourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Two bounded hash indexes: normalised alias -> IDs, then ID -> entity record.
 * Invoke from an IO dispatcher. Neither construction nor a word lookup loads the
 * complete name/entity collection; each cache retains at most four small shards.
 */
internal class MarvelGlossaryIndex(private val open: (String) -> InputStream) : Closeable {
    constructor(assets: AssetManager) : this({ path -> assets.open(path) })

    private data class Shard(val path: String, val sha256: String, val compressedBytes: Int, val uncompressedBytes: Int, val count: Int)
    private val aliases = LinkedHashMap<String, Map<String, List<String>>>(4, .75f, true)
    private val entities = LinkedHashMap<String, Map<String, MarvelReadingTerm>>(4, .75f, true)
    private var manifestCache: Map<String, Shard>? = null
    private var manifestFailure: MarvelGlossaryResourceException? = null
    private val lock = ReentrantLock()
    @Volatile private var closed = false

    companion object {
        private const val ROOT = "marvel-glossary/"
        private const val MAX_SHARD_BYTES = 1_048_576
        private const val MAX_MANIFEST_BYTES = 524_288
        private const val MAX_NAMES = 64
        private const val MAX_ALIAS_IDS = 64
        private const val MAX_CANDIDATES = 256
        private const val CACHE_SIZE = 4

        internal fun bucket(value: String): String = ((MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))[0].toInt() and 255)).toString(16).padStart(2, '0')
    }

    /** Input order is significant; the caller ranks longest name spans first. */
    fun find(names: List<String>, limit: Int = 24, checkActive: () -> Unit = {}): List<MarvelReadingTerm> = lock.withLock {
        val checkpoint = {
            checkActive()
            if (closed) throw CancellationException("漫威专名索引已关闭")
        }
        try {
            checkpoint()
            findLocked(names, limit, checkpoint)
        } finally {
            // close() may run on the UI thread while this IO lookup owns the
            // lock. The lookup releases its retained resources before exit.
            if (closed) clearLocked()
        }
    }

    private fun findLocked(names: List<String>, limit: Int, checkpoint: () -> Unit): List<MarvelReadingTerm> {
        require(limit in 1..64) { "漫威专名候选上限无效" }
        val queries = names.asSequence().take(MAX_NAMES).filter { it.length <= 240 }
            .map(MarvelGlossaryNameNormalizer::normalize).filter(String::isNotBlank).distinct().toList()
        if (queries.isEmpty()) return emptyList()
        val wanted = linkedSetOf<String>()
        for (name in queries) {
            checkpoint()
            val key = bucket(name)
            val source = aliases[key] ?: readAliases(key, checkpoint).also { remember(aliases, key, it) }
            for (id in source[name].orEmpty()) {
                checkpoint()
                wanted += id
                if (wanted.size >= MAX_CANDIDATES) break
            }
            if (wanted.size >= MAX_CANDIDATES) break
        }
        val result = ArrayList<MarvelReadingTerm>(minOf(limit, wanted.size))
        for (id in wanted) {
            checkpoint()
            val key = bucket(id)
            val source = entities[key] ?: readEntities(key, checkpoint).also { remember(entities, key, it) }
            val entity = source[id] ?: throw MarvelGlossaryResourceException("漫威专名索引指向不存在的实体")
            result += entity
            if (result.size >= limit) break
        }
        return result
    }

    fun clear() = lock.withLock { clearLocked() }

    private fun clearLocked() {
        aliases.clear(); entities.clear(); manifestCache = null; manifestFailure = null
    }

    /** Never make a Compose disposal wait for an IO task holding the lock. */
    override fun close() {
        closed = true
        if (lock.tryLock()) {
            try { clearLocked() } finally { lock.unlock() }
        }
    }

    private fun <T> remember(cache: LinkedHashMap<String, T>, key: String, value: T) {
        while (cache.size >= CACHE_SIZE) cache.entries.iterator().let { it.next(); it.remove() }
        cache[key] = value
    }

    private fun manifest(checkpoint: () -> Unit): Map<String, Shard> {
        checkpoint()
        manifestCache?.let { return it }
        manifestFailure?.let { throw it }
        return try {
            val json = JSONObject(open(ROOT + "manifest.json").use { readBounded(it, MAX_MANIFEST_BYTES, checkpoint) }.toString(Charsets.UTF_8))
            check(integer(json, "formatVersion") == 1 && integer(json, "normalizationVersion") == MarvelGlossaryNameNormalizer.VERSION)
            val rows = json.getJSONArray("shards")
            check(rows.length() == 512)
            val parsed = linkedMapOf<String, Shard>()
            repeat(rows.length()) { index ->
                checkpoint()
                val row = rows.getJSONObject(index)
                val path = string(row, "path")
                check(path.matches(Regex("(?:aliases|entities)/[0-9a-f]{2}\\.dict")))
                val sha = string(row, "sha256")
                check(sha.matches(Regex("[0-9a-f]{64}")))
                val compressed = integer(row, "compressedBytes")
                val decoded = integer(row, "uncompressedBytes")
                val count = integer(row, "count")
                check(compressed in 1..MAX_SHARD_BYTES && decoded in 2..MAX_SHARD_BYTES)
                check(count in 0..(if (path.startsWith("aliases/")) 8192 else 2048))
                check(parsed.put(path, Shard(path, sha, compressed, decoded, count)) == null)
            }
            repeat(256) { number ->
                val key = number.toString(16).padStart(2, '0')
                check(parsed.containsKey("aliases/$key.dict") && parsed.containsKey("entities/$key.dict"))
            }
            parsed.also { manifestCache = it }
        } catch (failure: Exception) {
            val error = resourceError("漫威专名索引清单无法读取或格式无效", failure)
            manifestFailure = error
            throw error
        }
    }

    private fun decoded(path: String, checkpoint: () -> Unit): JSONObject {
        val descriptor = manifest(checkpoint)[path] ?: throw MarvelGlossaryResourceException("漫威专名索引分片缺少清单")
        return try {
            checkpoint()
            val encoded = open(ROOT + path).use { readBounded(it, MAX_SHARD_BYTES, checkpoint) }
            check(encoded.size == descriptor.compressedBytes && sha256(encoded) == descriptor.sha256)
            check(encoded.size >= 2 && encoded[0] == 0x1f.toByte() && encoded[1] == 0x8b.toByte())
            val raw = GZIPInputStream(ByteArrayInputStream(encoded)).use { readBounded(it, MAX_SHARD_BYTES, checkpoint) }
            checkpoint()
            check(raw.size == descriptor.uncompressedBytes)
            JSONObject(raw.toString(Charsets.UTF_8)).also { check(it.length() == descriptor.count) }
        } catch (failure: Exception) {
            throw resourceError("漫威专名索引分片无法读取或校验失败", failure)
        }
    }

    private fun readAliases(key: String, checkpoint: () -> Unit): Map<String, List<String>> {
        return try {
            val json = decoded("aliases/$key.dict", checkpoint)
            buildMap {
                json.keys().forEach { name ->
                    checkpoint()
                    check(name.length in 1..240 && MarvelGlossaryNameNormalizer.normalize(name) == name && bucket(name) == key)
                    val ids = strings(json.getJSONArray(name), MAX_ALIAS_IDS, 160)
                    check(ids.isNotEmpty() && ids.distinct().size == ids.size)
                    put(name, ids)
                }
            }
        } catch (failure: Exception) { throw resourceError("漫威专名别名索引无效", failure) }
    }

    private fun readEntities(key: String, checkpoint: () -> Unit): Map<String, MarvelReadingTerm> {
        return try {
            val json = decoded("entities/$key.dict", checkpoint)
            buildMap {
                json.keys().forEach { id ->
                    checkpoint()
                    check(validText(id, 160) && bucket(id) == key)
                    val row = json.getJSONObject(id)
                    val english = text(row, "englishName", 240)
                    val chinese = nullableText(row, "chineseName", 240)
                    val kind = MarvelNameKind.entries.firstOrNull { it.name == string(row, "kind") }
                        ?: error("未知实体类型")
                    val evidence = MarvelChineseEvidence.entries.firstOrNull { it.name == string(row, "chineseEvidence") }
                        ?: error("未知译名证据")
                    check(chinese != null || evidence.name == "UNTRANSLATED")
                    val source = text(row, "englishSourceUrl", 2048)
                    val uri = URI(source)
                    check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
                    val checked = text(row, "checkedAt", 10)
                    LocalDate.parse(checked)
                    put(id, MarvelReadingTerm(id, english, chinese ?: english,
                        strings(optionalArray(row, "aliases"), 64, 240), kind, source, evidence,
                        text(row, "note", 2000), checkedAt = checked, hasChineseName = chinese != null,
                        universe = nullableText(row, "universe", 160),
                        chineseAliases = strings(optionalArray(row, "chineseAliases"), 64, 240)))
                }
            }
        } catch (failure: Exception) { throw resourceError("漫威专名实体索引无效", failure) }
    }

    private fun strings(array: JSONArray, maximum: Int, characters: Int): List<String> {
        check(array.length() <= maximum)
        return (0 until array.length()).map { (array.get(it) as? String ?: error("索引字段应是文字")).also { value -> check(validText(value, characters)) } }
    }
    private fun optionalArray(row: JSONObject, field: String): JSONArray =
        if (!row.has(field)) JSONArray() else row.get(field) as? JSONArray ?: error("索引字段应是数组")
    private fun string(row: JSONObject, field: String): String = row.get(field) as? String ?: error("索引字段应是文字")
    private fun integer(row: JSONObject, field: String): Int {
        val value = row.get(field)
        check(value is Int || value is Long)
        val number = (value as Number).toLong()
        check(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }
    private fun text(row: JSONObject, field: String, maximum: Int): String = string(row, field).also { check(validText(it, maximum)) }
    private fun nullableText(row: JSONObject, field: String, maximum: Int): String? {
        if (!row.has(field) || row.isNull(field)) return null
        return text(row, field, maximum)
    }
    private fun validText(value: String, maximum: Int) = value.isNotBlank() && value.length <= maximum && value.none(Char::isISOControl)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun resourceError(message: String, failure: Exception): MarvelGlossaryResourceException {
        if (failure is CancellationException) throw failure
        return failure as? MarvelGlossaryResourceException ?: MarvelGlossaryResourceException(message, failure)
    }
    private fun readBounded(input: InputStream, maximum: Int, checkpoint: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            checkpoint()
            if (count < 0) break
            check(output.size() <= maximum - count) { "漫威专名索引超出读取上限" }
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
