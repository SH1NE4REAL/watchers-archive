package org.watchersarchive.app

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the production assets, Android AssetManager and the real lookup path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarvelPackagedGlossaryTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets
    private class NoModel : EnglishDefinitionTranslator {
        override suspend fun modelReady() = false
        override suspend fun downloadModel(wifiOnly: Boolean) = error("No implicit model downloads")
        override suspend fun translate(text: String): String = error("Offline names must not need translation")
        override fun close() { }
    }
    private fun page(vararg words: String) = EnglishPageTokens("fixture", 800, 1200,
        words.mapIndexed { i, word -> EnglishToken("w:$i", word, RectF(i * 50f, 20f, i * 50f + 45f, 45f), words.joinToString(" "), "line") }, false)

    @Test fun productionShardsHaveTheirDeclaredSizeHashAndRealChineseCoverage() {
        val manifest = JSONObject(assets.open("marvel-glossary/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val coverage = manifest.getJSONObject("coverage")
        assertTrue(coverage.getInt("entityCount") >= 19_000)
        assertEquals(947, coverage.getInt("entitiesWithHanName"))
        val shards = manifest.getJSONArray("shards")
        assertEquals(512, shards.length())
        var records = 0
        var chinese = 0
        var largest = 0
        repeat(shards.length()) { i ->
            val descriptor = shards.getJSONObject(i)
            val path = descriptor.getString("path")
            val raw = assets.open("marvel-glossary/$path").use { it.readBytes() }
            assertEquals(descriptor.getInt("compressedBytes"), raw.size)
            assertEquals(descriptor.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
            assertEquals(0x1f, raw[0].toInt() and 255)
            val bytes = GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            assertEquals(descriptor.getInt("uncompressedBytes"), bytes.size)
            largest = maxOf(largest, bytes.size)
            val shard = JSONObject(bytes.toString(Charsets.UTF_8))
            assertEquals(descriptor.getInt("count"), shard.length())
            if (path.startsWith("entities/")) {
                records += shard.length()
                shard.keys().forEach { id ->
                    val row = shard.getJSONObject(id)
                    if (!row.isNull("chineseName")) {
                        assertTrue(row.getString("chineseName").codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN })
                        chinese++
                    } else assertEquals("UNTRANSLATED", row.getString("chineseEvidence"))
                }
            }
        }
        assertEquals(coverage.getInt("entityCount"), records)
        assertEquals(947, chinese)
        assertTrue(largest < 100_000)
    }

    @Test fun bulkIndexContainsNamesAndKindsBeyondTheOriginalTwenty() {
        val index = MarvelGlossaryIndex(assets)
        val doom = index.find(listOf("Victor von Doom"), 64).first { it.id == "wikidata:Q430178" }
        assertEquals("末日博士", doom.chineseName)
        assertEquals(MarvelChineseEvidence.COMMUNITY, doom.chineseEvidence)
        assertTrue(index.find(listOf("Doreen Green"), 64).any { it.id == "wikidata:Q4353353" && it.hasChineseName })
        assertTrue(index.find(listOf("Galactus"), 64).any { it.id == "wikidata:Q1429836" && it.hasChineseName })
        assertTrue(index.find(listOf("Krakoa"), 64).any { it.kind == MarvelNameKind.PLACE && it.hasChineseName })
        val versions = index.find(listOf("Kamala Khan"), 64).filter { it.id in setOf("wikidata:Q15208946", "wikidata:Q113169240") }
        assertEquals(2, versions.size)
        assertEquals(1, versions.count { it.universe == "MCU" })
    }

    @Test fun aSingleWordLookupDoesNotOpenTheCompletePackagedCollection() {
        val reads = mutableListOf<String>()
        val index = MarvelGlossaryIndex { path -> reads += path; assets.open(path) }
        // This exact full-name alias has one record. Victor von Doom has six
        // distinct candidates; fetching those six must not be counted as a
        // whole-dataset load or be expected to fit a four-shard cache.
        assertTrue(index.find(listOf("Doreen Allene Green"), 64).isNotEmpty())
        assertTrue("One alias and bounded entity shards only: $reads", reads.size <= 5)
        val count = reads.size
        index.find(listOf("Doreen Allene Green"), 64)
        assertEquals(count, reads.size)
    }

    @Test fun realProviderResolvesIdentityAliasesAndKeepsMcuAsASeparateSecondaryCandidate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PublicEnglishDictionaryProvider(context, NoModel())
        try {
            val doom = page("Victor", "von", "Doom")
            val result = checkNotNull(provider.lookup(doom.tokens.last(), doom))
            assertTrue(result.marvelNames.any { it.term.id == "wikidata:Q430178" && it.matchedText == "Victor von Doom" })
            val kamala = page("Kamala", "Khan")
            val names = checkNotNull(provider.lookup(kamala.tokens.last(), kamala)).marvelNames
            assertEquals("wikidata:Q15208946", names.first().term.id)
            assertTrue(names.any { it.term.id == "wikidata:Q113169240" && it.term.universe == "MCU" })
        } finally { provider.close() }
    }

    @Test fun untranslatableHistoricalEntitiesAreNotPresentedAsChineseAndFunctionWordsStayOrdinary() = runBlocking {
        val index = MarvelGlossaryIndex(assets)
        val terms = index.find(listOf("He Who Remains"), 64)
        assertTrue(terms.isNotEmpty())
        assertTrue(terms.all { !it.hasChineseName && it.chineseEvidence == MarvelChineseEvidence.UNTRANSLATED })
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PublicEnglishDictionaryProvider(context, NoModel())
        try {
            for (word in listOf("A", "IT", "ONE")) {
                val page = page(word)
                val entry = checkNotNull(provider.lookup(page.tokens.single(), page))
                assertTrue(entry.marvelNames.isEmpty())
                assertFalse(entry.meaning.contains("暂未收录"))
            }
        } finally { provider.close() }
    }

    @Test fun aBrokenMarvelResourceGivesAnHonestNoticeAndKeepsTheOrdinaryDictionary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PublicEnglishDictionaryProvider(context, NoModel(), marvelIndex = MarvelGlossaryIndex { throw FileNotFoundException(it) })
        try {
            val page = page("Cyclops")
            val entry = checkNotNull(provider.lookup(page.tokens.single(), page))
            assertEquals("镭射眼", entry.marvelNames.first().term.chineseName)
            assertNotEquals("镭射眼", entry.meaning)
            assertTrue(entry.notice!!.contains("扩展词库暂不可用"))
        } finally { provider.close() }
    }
}
