package org.watchersarchive.app

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONObject

internal data class BundledEnglishLexeme(val word: String, val phonetic: String?, val chinese: String?, val english: String?, val parts: String?)

/** Reads one bounded hash shard; the entire dictionary is never loaded into RAM. */
internal class BundleEnglishDictionary(private val open: (String) -> InputStream) {
    constructor(assets: AssetManager) : this({ assets.open(it) })
    private val shards = LinkedHashMap<String, Map<String, BundledEnglishLexeme>>(4, .75f, true)
    companion object {
        const val SOURCE = "https://github.com/skywind3000/ECDICT"
        const val LABEL = "ECDICT · MIT"
        private const val MAXIMUM_SHARD_BYTES = 8 * 1024 * 1024

        fun normalize(value: String): String {
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'').replace(Regex("[‐‑‒–—−]"), "-")
                .replace(Regex("\\s+"), " ").trim().trim('.', ',', '!', '?', ';', ':', '"', '(', ')', '[', ']', '{', '}').trim()
            return if (normalized.length >= 2 && normalized.startsWith('\'') && normalized.endsWith('\''))
                normalized.substring(1, normalized.lastIndex).trim() else normalized
        }
    }

    @Synchronized fun lookup(text: String): BundledEnglishLexeme? {
        val word = normalize(text)
        if (word.length !in 1..120 || word.split(' ').size > 12 || word.any(Char::isISOControl)) return null
        val number = MessageDigest.getInstance("SHA-256").digest(word.toByteArray(Charsets.UTF_8))[0].toInt() and 255
        val bucket = number.toString(16).padStart(2, '0')
        val entries = shards[bucket] ?: readShard(bucket).also {
            while (shards.size >= 4) shards.entries.iterator().let { iterator -> iterator.next(); iterator.remove() }
            shards[bucket] = it
        }
        return entries[word]
    }

    private fun readShard(bucket: String): Map<String, BundledEnglishLexeme> {
        val decoded = open("english-dictionary/$bucket.dict").use { raw -> GZIPInputStream(raw).use { gzip ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = gzip.read(buffer)
                if (count < 0) break
                check(output.size() <= MAXIMUM_SHARD_BYTES - count) { "离线词典分片超出读取上限" }
                output.write(buffer, 0, count)
            }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        } }
        check(decoded.length() <= 8192) { "离线词典分片词条过多" }
        return buildMap {
            decoded.keys().forEach { key ->
                val row = decoded.optJSONArray(key) ?: return@forEach
                if (key.length <= 120 && row.length() == 5) put(key, BundledEnglishLexeme(
                    row.optString(0).take(160), row.optString(1).take(160).ifBlank { null },
                    row.optString(2).take(3000).ifBlank { null }, row.optString(3).take(2000).ifBlank { null },
                    row.optString(4).take(200).ifBlank { null }))
            }
        }
    }

    @Synchronized fun clear() = shards.clear()
}
