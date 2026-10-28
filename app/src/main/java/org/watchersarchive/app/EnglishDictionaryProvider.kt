package org.watchersarchive.app

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface EnglishDefinitionTranslator : Closeable {
    suspend fun modelReady(): Boolean
    suspend fun downloadModel(wifiOnly: Boolean)
    suspend fun translate(text: String): String
}

internal class MlKitEnglishDefinitionTranslator : EnglishDefinitionTranslator {
    companion object { private val modelWork = kotlinx.coroutines.sync.Semaphore(1) }
    private val engineLock = Any()
    private var closed = false
    private val translator = lazy {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE).build())
    }
    override suspend fun modelReady(): Boolean {
        val task = synchronized(engineLock) {
            check(!closed)
            RemoteModelManager.getInstance().isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build())
        }
        return task.awaitEnglishDictionaryTask()
    }
    override suspend fun downloadModel(wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        runModelTask { translator.value.downloadModelIfNeeded(conditions) }
    }
    override suspend fun translate(text: String): String = runModelTask { translator.value.translate(text) }

    private suspend fun <T> runModelTask(create: () -> Task<T>): T {
        modelWork.acquire()
        val task = try {
            currentCoroutineContext().ensureActive()
            synchronized(engineLock) { check(!closed); create() }.also {
                // Native tasks can outlive a cancelled coroutine. Keep this gate
                // until Task completion so rapid taps cannot queue native work.
                it.addOnCompleteListener(java.util.concurrent.Executor { action -> action.run() }) { modelWork.release() }
            }
        } catch (failure: Throwable) { modelWork.release(); throw failure }
        return task.awaitEnglishDictionaryTask()
    }

    override fun close() = synchronized(engineLock) {
        if (!closed) { closed = true; if (translator.isInitialized()) translator.value.close() }
    }
}

/** Local English/Chinese definitions; contextual machine text uses an opt-in device model. */
internal class PublicEnglishDictionaryProvider(
    context: Context,
    private val translation: EnglishDefinitionTranslator = MlKitEnglishDefinitionTranslator(),
    private val dictionary: BundleEnglishDictionary = BundleEnglishDictionary(context.applicationContext.assets),
    private val comicTitleHint: String? = null,
    private val marvelIndex: MarvelGlossaryIndex = MarvelGlossaryIndex(context.applicationContext.assets),
) : EnglishDictionaryProvider, Closeable {
    private val definitions = LinkedHashMap<String, BundledEnglishLexeme?>(128, .75f, true)
    private val modelDownload = Mutex()
    @Volatile private var closed = false

    suspend fun modelReady(): Boolean = withContext(Dispatchers.IO) {
        check(!closed)
        try { translation.modelReady() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** The card calls this only after the user explicitly agrees to a model download. */
    suspend fun downloadModel(wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        modelDownload.withLock {
            check(!closed)
            translation.downloadModel(wifiOnly)
            currentCoroutineContext().ensureActive()
            check(!closed)
        }
    }

    override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry? = withContext(Dispatchers.IO) {
        check(!closed)
        val word = BundleEnglishDictionary.normalize(token.text)
        if (word.length !in 1..120 || word.split(' ').size > 12 || word.any(Char::isISOControl) || !word.any(Char::isLetter)) return@withContext null
        var marvelNotice: String? = null
        val lookupContext = currentCoroutineContext()
        val marvelNames = try {
            MarvelReadingGlossary.matches(token, page, comicTitleHint) { names ->
                marvelIndex.find(names, limit = 64) { lookupContext.ensureActive() }
            }
        } catch (_: MarvelGlossaryResourceException) {
            marvelNotice = "漫威扩展词库暂不可用，当前保留基础词表和普通释义。"
            MarvelReadingGlossary.matches(token, page, comicTitleHint)
        }
        val local = try { definition(word) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return@withContext EnglishDictionaryEntry(token.text, meaning = "离线词典暂不可用，请更新应用后重试。",
                notice = "词典资源读取失败，未将错误视作词语未收录。", marvelNames = marvelNames)
        }
        currentCoroutineContext().ensureActive()
        if (closed) return@withContext null
        val ready = modelReady()
        val originalContext = token.lineText.trim().take(800).takeIf { it.isNotBlank() && it != token.text.trim() }
        val localChinese = local?.chinese?.let(::readableLines)?.take(1800)
        val english = local?.english?.let(::readableLines)?.take(1500)
        val translatedWord = if (localChinese.isNullOrBlank() && ready) translated(word) else null
        val contextMeaning = if (ready && originalContext != null) translated(originalContext) else null
        currentCoroutineContext().ensureActive()
        if (closed) return@withContext null
        EnglishDictionaryEntry(
            original = token.text,
            phonetic = local?.phonetic?.let { if (it.startsWith('/') || it.startsWith('[')) it else "/$it/" },
            meaning = localChinese ?: translatedWord?.let { "$it（机器参考译义）" }
                ?: english?.let { "英文释义：$it" } ?: "离线词典暂未收录这个词语。",
            partOfSpeech = local?.let(::partOfSpeech),
            context = originalContext,
            englishDefinition = english,
            contextMeaning = contextMeaning,
            sourceLabel = if (local != null) BundleEnglishDictionary.LABEL else null,
            sourceUrl = if (local != null) BundleEnglishDictionary.SOURCE else null,
            requiresModel = !ready && (originalContext != null || localChinese.isNullOrBlank()),
            notice = listOfNotNull(when {
                local != null && contextMeaning != null -> "常用词义来自词典；原句译文由设备模型生成，未自动判定义项。"
                local != null -> "常用词义来自词典，请结合原句判断。"
                translatedWord != null -> "未取得词典条目，当前仅提供设备端机器参考译义。"
                else -> "人名、拟声词、俚语或识别错误可能查不到释义。"
            }, marvelNotice).joinToString(" "),
            marvelNames = marvelNames,
        )
    }

    private suspend fun definition(word: String): BundledEnglishLexeme? {
        synchronized(definitions) { if (definitions.containsKey(word)) return definitions[word] }
        val result = dictionary.lookup(word)
        synchronized(definitions) {
            while (definitions.size >= 128) definitions.entries.iterator().let { it.next(); it.remove() }
            definitions[word] = result
        }
        return result
    }

    private suspend fun translated(text: String): String? = try {
        translation.translate(text.take(1500)).take(2500).ifBlank { null }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun readableLines(text: String): String = text.replace("\\n", "\n").lineSequence().take(8).joinToString("\n").trim()
    private fun partOfSpeech(entry: BundledEnglishLexeme): String? {
        val names = linkedSetOf<String>()
        val raw = entry.parts.orEmpty() + " " + entry.chinese.orEmpty()
        val mapping = linkedMapOf("n" to "名词", "v" to "动词", "vt" to "及物动词", "vi" to "不及物动词",
            "a" to "形容词", "adj" to "形容词", "ad" to "副词", "adv" to "副词", "pron" to "代词",
            "prep" to "介词", "conj" to "连词", "int" to "感叹词", "interj" to "感叹词")
        Regex("(?:^|[ /\\n])([a-z]+)(?:[:.]|$)").findAll(readableLines(raw)).forEach {
            mapping[it.groupValues[1]]?.let(names::add)
        }
        return names.take(4).joinToString("、").ifBlank { entry.parts?.take(100) }
    }

    override fun close() {
        closed = true
        translation.close()
        dictionary.clear()
        marvelIndex.close()
        synchronized(definitions) { definitions.clear() }
    }
}

private suspend fun <T> Task<T>.awaitEnglishDictionaryTask(): T = suspendCancellableCoroutine { continuation ->
    val waiting = AtomicReference<kotlinx.coroutines.CancellableContinuation<T>?>(continuation)
    continuation.invokeOnCancellation { waiting.set(null) }
    addOnSuccessListener { value -> waiting.getAndSet(null)?.let { if (it.isActive) it.resume(value) } }
    addOnFailureListener { failure -> waiting.getAndSet(null)?.let { if (it.isActive) it.resumeWithException(failure) } }
    addOnCanceledListener { waiting.getAndSet(null)?.cancel() }
}
