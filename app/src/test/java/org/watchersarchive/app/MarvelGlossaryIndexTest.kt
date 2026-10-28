package org.watchersarchive.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MarvelGlossaryIndexTest {
    private class Fixture(
        private val recordOverrides: Map<String, JSONObject> = emptyMap(),
        private val aliasOverrides: Map<String, List<String>> = emptyMap(),
    ) {
        val resources = linkedMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()
        private val sourceRecords = if (recordOverrides.isEmpty()) mapOf(
            "wd:Q100" to record("Cyclops", "镭射眼"),
            "wiki:other-universe" to record("Cyclops", null, universe = "Earth-1610"),
        ) else recordOverrides
        private val sourceAliases = if (aliasOverrides.isEmpty()) mapOf(
            "cyclops" to listOf("wd:Q100", "wiki:other-universe"), "scott summers" to listOf("wd:Q100"),
        ) else aliasOverrides
        init {
            val aliasBuckets = Array(256) { JSONObject() }
            val recordBuckets = Array(256) { JSONObject() }
            sourceAliases.forEach { (alias, ids) -> aliasBuckets[MarvelGlossaryIndex.bucket(alias).toInt(16)].put(alias, JSONArray(ids)) }
            sourceRecords.forEach { (id, row) -> recordBuckets[MarvelGlossaryIndex.bucket(id).toInt(16)].put(id, row) }
            val descriptors = JSONArray()
            for ((folder, buckets) in listOf("aliases" to aliasBuckets, "entities" to recordBuckets)) {
                buckets.forEachIndexed { index, json ->
                    val raw = json.toString().toByteArray(Charsets.UTF_8)
                    val encoded = gzip(raw)
                    val path = "$folder/${index.toString(16).padStart(2, '0')}.dict"
                    resources["marvel-glossary/$path"] = encoded
                    descriptors.put(JSONObject().put("path", path).put("sha256", sha(encoded))
                        .put("compressedBytes", encoded.size).put("uncompressedBytes", raw.size).put("count", json.length()))
                }
            }
            resources["marvel-glossary/manifest.json"] = JSONObject().put("formatVersion", 1).put("normalizationVersion", 1)
                .put("shards", descriptors).toString().toByteArray(Charsets.UTF_8)
        }
        val index = MarvelGlossaryIndex { path -> reads += path; ByteArrayInputStream(resources[path] ?: throw FileNotFoundException(path)) }
        fun manifest(): JSONObject = JSONObject(checkNotNull(resources["marvel-glossary/manifest.json"]).toString(Charsets.UTF_8))
        fun saveManifest(json: JSONObject) { resources["marvel-glossary/manifest.json"] = json.toString().toByteArray(Charsets.UTF_8) }
        fun replaceShard(folder: String, key: String, bytes: ByteArray, decodedBytes: Int? = null, count: Int? = null) {
            val path = "$folder/$key.dict"
            resources["marvel-glossary/$path"] = bytes
            val manifest = manifest(); val rows = manifest.getJSONArray("shards")
            repeat(rows.length()) { n -> rows.getJSONObject(n).let { row -> if (row.getString("path") == path) {
                row.put("sha256", sha(bytes)).put("compressedBytes", bytes.size)
                decodedBytes?.let { row.put("uncompressedBytes", it) }; count?.let { row.put("count", it) }
            } } }
            saveManifest(manifest)
        }
    }

    @Test fun indexedLookupReadsOnlyNeededAliasAndEntityShardsAndCachesThem() {
        val fixture = Fixture()
        val result = fixture.index.find(listOf("CYCLOPS!", "Scott Summers"))
        assertEquals(listOf("wd:Q100", "wiki:other-universe"), result.map { it.id })
        assertEquals("镭射眼", result.first().chineseName)
        assertTrue(result.first().hasChineseName)
        assertFalse(result.last().hasChineseName)
        assertEquals("Cyclops", result.last().chineseName)
        assertEquals("Earth-1610", result.last().universe)
        assertTrue("A lookup must not open the complete 512-shard dataset", fixture.reads.size <= 5)
        val reads = fixture.reads.size
        fixture.index.find(listOf("cyclops", "Scott Summers"))
        assertEquals(reads, fixture.reads.size)
    }

    @Test fun missingNameIsAnEmptyMatchButMissingAssetIsAnExplicitResourceError() {
        val fixture = Fixture()
        assertTrue(fixture.index.find(listOf("unknown subject")).isEmpty())
        val key = MarvelGlossaryIndex.bucket("cyclops")
        fixture.resources.remove("marvel-glossary/aliases/$key.dict")
        assertTrue(runCatching { fixture.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
    }

    @Test fun manifestFailureIsBoundedAndCachedUntilClear() {
        var calls = 0
        val index = MarvelGlossaryIndex { calls++; throw FileNotFoundException(it) }
        repeat(3) { assertTrue(runCatching { index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException) }
        assertEquals(1, calls)
        index.clear()
        assertTrue(runCatching { index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
        assertEquals(2, calls)
    }

    @Test fun corruptCompressedDataAndHashMismatchCannotMasqueradeAsUnknownWords() {
        val corrupted = Fixture()
        val path = "marvel-glossary/aliases/${MarvelGlossaryIndex.bucket("cyclops")}.dict"
        corrupted.resources[path] = byteArrayOf(1, 2, 3)
        assertTrue(runCatching { corrupted.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
        val invalidGzip = Fixture()
        invalidGzip.replaceShard("aliases", MarvelGlossaryIndex.bucket("cyclops"), byteArrayOf(1,2,3))
        assertTrue(runCatching { invalidGzip.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
    }

    @Test fun oversizedDecodeIsRejectedEvenWhenCompressedHashMatches() {
        val fixture = Fixture()
        val raw = ByteArray(1_048_577) { 32 }
        // A false small declared length must not allow a zip bomb allocation.
        fixture.replaceShard("aliases", MarvelGlossaryIndex.bucket("cyclops"), gzip(raw), decodedBytes = 2, count = 0)
        assertTrue(runCatching { fixture.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
    }

    @Test fun anAliasCannotSilentlyPointToAnAbsentOrWrongBucketEntity() {
        val missing = Fixture(aliasOverrides = mapOf("cyclops" to listOf("absent-id")))
        assertTrue(runCatching { missing.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
        val wrongAliasBucket = Fixture()
        val key = MarvelGlossaryIndex.bucket("cyclops")
        val raw = "{\"a different name\":[\"wd:Q100\"]}".toByteArray()
        wrongAliasBucket.replaceShard("aliases", key, gzip(raw), raw.size, 1)
        assertTrue(runCatching { wrongAliasBucket.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
    }

    @Test fun limitedResultsPreserveSeparateSourceAndUniverseIdsWithoutTitleMerging() {
        val fixture = Fixture()
        assertEquals(1, fixture.index.find(listOf("cyclops"), limit = 1).size)
        assertEquals(2, fixture.index.find(listOf("cyclops"), limit = 64).size)
        assertTrue(runCatching { fixture.index.find(listOf("cyclops"), limit = 0) }.isFailure)
        assertTrue(runCatching { fixture.index.find(listOf("cyclops"), limit = 65) }.isFailure)
    }

    @Test fun malformedSchemaDoesNotCreateInventedChineseNames() {
        val row = record("Untranslated name", null).put("chineseEvidence", "COMMUNITY")
        val fixture = Fixture(recordOverrides = mapOf("wd:Q100" to row), aliasOverrides = mapOf("cyclops" to listOf("wd:Q100")))
        assertTrue(runCatching { fixture.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
        val invalidUrl = Fixture(recordOverrides = mapOf("wd:Q100" to record("Name", "中文").put("englishSourceUrl", "file:///private")),
            aliasOverrides = mapOf("cyclops" to listOf("wd:Q100")))
        assertTrue(runCatching { invalidUrl.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
        val malformedAliases = Fixture(recordOverrides = mapOf("wd:Q100" to record("Name", "中文").put("aliases", "A wrong scalar")),
            aliasOverrides = mapOf("cyclops" to listOf("wd:Q100")))
        assertTrue(runCatching { malformedAliases.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
    }

    @Test fun fourShardLruBoundsRetentionAndReopensAnEvictedBucket() {
        val aliases = linkedMapOf<String,List<String>>()
        val buckets = mutableSetOf<String>()
        var number = 0
        while (aliases.size < 5) { val name = "alias ${number++}"; if (buckets.add(MarvelGlossaryIndex.bucket(name))) aliases[name] = listOf("wd:Q100") }
        val fixture = Fixture(aliasOverrides = aliases)
        val names = aliases.keys.toList()
        names.forEach { fixture.index.find(listOf(it)) }
        val target = "marvel-glossary/aliases/${MarvelGlossaryIndex.bucket(names.first())}.dict"
        assertEquals(1, fixture.reads.count { it == target })
        fixture.index.find(listOf(names.first()))
        assertEquals(2, fixture.reads.count { it == target })
        assertEquals(1, fixture.reads.count { it == "marvel-glossary/manifest.json" })
    }

    @Test fun normalizationMatchesTheCuratedNameRulesAndKeepsChinese() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("iron man", MarvelGlossaryNameNormalizer.normalize("ＩＲＯＮ—ＭＡＮ’S"))
            assertEquals("shield", MarvelGlossaryNameNormalizer.normalize("S.H.I.E.L.D."))
            assertEquals("镭射眼", MarvelGlossaryNameNormalizer.normalize(" 镭射眼！ "))
        } finally { Locale.setDefault(old) }
    }

    @Test fun manifestCannotUseUnsafePathsOrUnsupportedNormalization() {
        val unsafe = Fixture()
        unsafe.saveManifest(unsafe.manifest().apply { getJSONArray("shards").getJSONObject(0).put("path", "../outside.dict") })
        assertTrue(runCatching { unsafe.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
        val unsupported = Fixture()
        unsupported.saveManifest(unsupported.manifest().put("normalizationVersion", 999))
        assertTrue(runCatching { unsupported.index.find(listOf("cyclops")) }.exceptionOrNull() is MarvelGlossaryResourceException)
    }

    @Test fun cancellationDuringManifestReadIsNotWrappedOrCachedAsResourceCorruption() {
        val fixture = Fixture()
        val cancellation = CancellationException("synthetic caller cancellation")
        var openedManifest = false
        var manifestOpens = 0
        var streamClosed = false
        val index = MarvelGlossaryIndex { path ->
            if (path.endsWith("manifest.json")) { openedManifest = true; manifestOpens++ }
            object : ByteArrayInputStream(checkNotNull(fixture.resources[path])) {
                override fun close() { streamClosed = true; super.close() }
            }
        }
        val failure = runCatching {
            index.find(listOf("cyclops")) { if (openedManifest) throw cancellation }
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(streamClosed)
        assertEquals(1, manifestOpens)
        assertEquals(2, index.find(listOf("cyclops")).size)
        assertEquals("Cancellation must not poison the cached manifest", 2, manifestOpens)
    }

    @Test fun cancellationIsAlsoCheckedWhenEveryRequestedShardIsAlreadyCached() {
        val fixture = Fixture()
        fixture.index.find(listOf("cyclops"))
        val reads = fixture.reads.size
        val cancellation = CancellationException("cancel cached candidates")
        var checkpoints = 0
        val failure = runCatching {
            fixture.index.find(listOf("cyclops")) {
                if (++checkpoints == 3) throw cancellation
            }
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals(3, checkpoints)
        assertEquals(reads, fixture.reads.size)
        assertEquals(2, fixture.index.find(listOf("cyclops")).size)
    }

    @Test fun closeReturnsWhileAnIoReadIsBlockedAndInflightLookupThenCancelsAndClosesItsStream() {
        val fixture = Fixture()
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closedStream = AtomicBoolean(false)
        val pauseOnce = AtomicBoolean(true)
        val index = MarvelGlossaryIndex { path ->
            object : ByteArrayInputStream(checkNotNull(fixture.resources[path])) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (pauseOnce.compareAndSet(true, false)) {
                        reading.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS)) { "test read was not released" }
                    }
                    return super.read(buffer, offset, length)
                }
                override fun close() { closedStream.set(true); super.close() }
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lookup = executor.submit<Throwable?> { runCatching { index.find(listOf("cyclops")) }.exceptionOrNull() }
            assertTrue("Lookup must own its IO lock before testing close", reading.await(5, TimeUnit.SECONDS))
            executor.submit { index.close() }.get(5, TimeUnit.SECONDS)
            assertFalse("close must finish before the deliberately blocked read", lookup.isDone)
            releaseRead.countDown()
            assertTrue(lookup.get(5, TimeUnit.SECONDS) is CancellationException)
            assertTrue(closedStream.get())
            assertTrue(runCatching { index.find(listOf("cyclops")) }.exceptionOrNull() is CancellationException)
            index.clear()
            assertTrue("clear cannot reopen a disposed index", runCatching { index.find(listOf("cyclops")) }.exceptionOrNull() is CancellationException)
            index.close()
        } finally {
            releaseRead.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        private fun record(english: String, chinese: String?, universe: String? = null) = JSONObject()
            .put("englishName", english).put("chineseName", chinese ?: JSONObject.NULL)
            .put("aliases", JSONArray(listOf("Scott Summers"))).put("chineseAliases", JSONArray(if (chinese == null) emptyList<String>() else listOf(chinese)))
            .put("kind", "CHARACTER").put("englishSourceUrl", "https://www.wikidata.org/wiki/Q100")
            .put("chineseEvidence", if (chinese == null) "UNTRANSLATED" else "COMMUNITY")
            .put("note", "Synthetic entity fixture").put("checkedAt", "2026-10-28").put("universe", universe ?: JSONObject.NULL)
        private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
