package org.watchersarchive.app

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicEnglishDictionaryProviderTest {
    @Test fun normalizationPreservesMeaningfulContractionsAndOnlyStripsBalancedQuotes() {
        assertEquals("hero", BundleEnglishDictionary.normalize("‘HERO’!"))
        assertEquals("'cause", BundleEnglishDictionary.normalize("’CAUSE"))
        assertEquals("'em", BundleEnglishDictionary.normalize("'em"))
        assertEquals("m'kay", BundleEnglishDictionary.normalize("m’kay?"))
        assertEquals("don't", BundleEnglishDictionary.normalize("DON'T"))
    }
    private class Translator(var ready: Boolean = false) : EnglishDefinitionTranslator {
        var downloads = 0
        var lastWifi: Boolean? = null
        var closed = false
        val translated = mutableListOf<String>()
        override suspend fun modelReady() = ready
        override suspend fun downloadModel(wifiOnly: Boolean) { downloads++; lastWifi = wifiOnly; ready = true }
        override suspend fun translate(text: String): String { translated += text; return "译文：$text" }
        override fun close() { closed = true }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun token(word: String, sentence: String = "The hero saves the city.") = EnglishToken("word", word, RectF(1f, 1f, 20f, 20f), sentence)
    private fun page(token: EnglishToken) = EnglishPageTokens("page", 80, 120, listOf(token), false)
    private fun dictionary(content: String = """{"hero":["hero","ˈhɪərəʊ","n. 英雄；勇士","n. a brave person","n:100"],"slow down":["slow down","","放慢速度","To become slower.","v:100"]}"""): BundleEnglishDictionary {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(content.toByteArray()) }
        return BundleEnglishDictionary { ByteArrayInputStream(output.toByteArray()) }
    }

    @Test fun commonChineseAndPhoneticAreLocalWithoutDownloadingAModel() = runBlocking {
        val translator = Translator()
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary())
        val token = token("HERO!")
        val entry = checkNotNull(provider.lookup(token, page(token)))
        assertEquals("n. 英雄；勇士", entry.meaning)
        assertEquals("/ˈhɪərəʊ/", entry.phonetic)
        assertEquals("名词", entry.partOfSpeech)
        assertEquals(BundleEnglishDictionary.LABEL, entry.sourceLabel)
        assertEquals(BundleEnglishDictionary.SOURCE, entry.sourceUrl)
        assertTrue(entry.requiresModel)
        assertNull(entry.contextMeaning)
        assertEquals(0, translator.downloads)
        assertTrue(translator.translated.isEmpty())
        provider.close()
    }

    @Test fun phraseLookupIsExactAndDoesNotInventPhonetics() = runBlocking {
        val provider = PublicEnglishDictionaryProvider(context, Translator(), dictionary())
        val token = token("ＳＬＯＷ  DOWN", "Please slow down.")
        val entry = checkNotNull(provider.lookup(token, page(token)))
        assertEquals("放慢速度", entry.meaning)
        assertNull(entry.phonetic)
        assertEquals("动词", entry.partOfSpeech)
        provider.close()
    }

    @Test fun readyDeviceModelTranslatesContextLocallyAndLabelsItAsMachineReference() = runBlocking {
        val translator = Translator(true)
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary())
        val token = token("hero")
        val entry = checkNotNull(provider.lookup(token, page(token)))
        assertEquals("n. 英雄；勇士", entry.meaning)
        assertEquals("译文：The hero saves the city.", entry.contextMeaning)
        assertEquals(listOf("The hero saves the city."), translator.translated)
        assertTrue(entry.notice!!.contains("未自动判定义项"))
        assertFalse(entry.requiresModel)
        assertEquals(0, translator.downloads)
        provider.close()
    }

    @Test fun missingPhraseWithAModelUsesClearlyLabelledMachineFallback() = runBlocking {
        val translator = Translator(true)
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary("{}"))
        val token = token("m'kay", "Slow down, m'kay?")
        val entry = checkNotNull(provider.lookup(token, page(token)))
        assertEquals("译文：m'kay（机器参考译义）", entry.meaning)
        assertNull(entry.phonetic)
        assertNull(entry.partOfSpeech)
        assertNull(entry.sourceLabel)
        provider.close()
    }

    @Test fun missingWordWithoutModelHasHonestFallbackAndNoAutomaticDownloads() = runBlocking {
        val translator = Translator()
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary("{}"))
        val token = token("SNNNIKT")
        val entry = checkNotNull(provider.lookup(token, page(token)))
        assertEquals("离线词典暂未收录这个词语。", entry.meaning)
        assertNull(entry.phonetic)
        assertNull(entry.partOfSpeech)
        assertTrue(entry.requiresModel)
        assertEquals(0, translator.downloads)
        provider.close()
    }

    @Test fun onlyExplicitDownloadUsesTheUsersNetworkChoiceAndCloseDisposesModel() = runBlocking {
        val translator = Translator()
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary())
        assertFalse(provider.modelReady())
        assertEquals(0, translator.downloads)
        provider.downloadModel(wifiOnly = false)
        assertEquals(false, translator.lastWifi)
        assertTrue(provider.modelReady())
        provider.close()
        assertTrue(translator.closed)
        assertTrue(runCatching { provider.downloadModel(wifiOnly = true) }.isFailure)
    }

    @Test fun exactLookupCachesAWordAndDoesNotLoadTheWholeDictionary() = runBlocking {
        val translator = Translator()
        var shardsOpened = 0
        val raw = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write("""{"hero":["hero","","英雄","","n:100"]}""".toByteArray()) } }.toByteArray()
        val shard = BundleEnglishDictionary { shardsOpened++; ByteArrayInputStream(raw) }
        val provider = PublicEnglishDictionaryProvider(context, translator, shard)
        repeat(5) { val token = token("hero"); provider.lookup(token, page(token)) }
        assertEquals(1, shardsOpened)
        provider.close()
    }

    @Test fun invalidSelectionIsRejectedWithoutDictionaryOrModelWork() = runBlocking {
        val translator = Translator()
        val provider = PublicEnglishDictionaryProvider(context, translator, dictionary())
        val token = token("---")
        assertNull(provider.lookup(token, page(token)))
        assertEquals(0, translator.downloads)
        assertTrue(translator.translated.isEmpty())
        provider.close()
    }

    @Test fun corruptPackagedResourceIsNotReportedAsAnUnknownWord() = runBlocking {
        val provider = PublicEnglishDictionaryProvider(context, Translator(), BundleEnglishDictionary { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val token = token("hero")
        val entry = checkNotNull(provider.lookup(token, page(token)))
        assertEquals("离线词典暂不可用，请更新应用后重试。", entry.meaning)
        assertNull(entry.phonetic)
        assertFalse(entry.requiresModel)
        provider.close()
    }

    @Test fun packagedDictionaryHasVerifiedCoverageAndUsefulOrdinaryAndPhraseEntries() {
        val manifest = context.assets.open("english-dictionary/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("0e527e22e0e0d22f012b0ed251a8dddd5891773b", manifest.getString("upstreamGitBlob"))
        assertTrue(manifest.getInt("entries") > 100_000)
        val shardName = manifest.getJSONArray("shards").getJSONObject(0).getString("name")
        assertTrue(shardName.matches(Regex("[0-9a-f]{2}\\.dict")))
        // Inspect the merged/packaged asset, not only the source directory:
        // .gz names used to be expanded by AGP before this code could read them.
        context.assets.open("english-dictionary/$shardName").use { input ->
            assertEquals(0x1f, input.read())
            assertEquals(0x8b, input.read())
        }
        val bundled = BundleEnglishDictionary(context.assets)
        val hero = checkNotNull(bundled.lookup("HERO!"))
        assertTrue(hero.chinese!!.contains("英雄"))
        assertFalse(hero.phonetic.isNullOrBlank())
        assertFalse(checkNotNull(bundled.lookup("slow down")).chinese.isNullOrBlank())
        assertTrue(context.assets.open("english-dictionary/LICENSE.txt").bufferedReader().use { it.readText() }.startsWith("MIT License"))
        bundled.clear()
    }
}
