package org.watchersarchive.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TranslatedPageBlock(val original: String, val chinese: String)
internal data class TranslatedPage(val pageKey: String, val blocks: List<TranslatedPageBlock>, val truncated: Boolean)

internal sealed interface PageTranslationState {
    data object CheckingModel : PageTranslationState
    data object ModelRequired : PageTranslationState
    data object ReadyToTranslate : PageTranslationState
    data object Downloading : PageTranslationState
    data object Recognizing : PageTranslationState
    data class Translating(val completed: Int, val total: Int) : PageTranslationState
    data class Complete(val page: TranslatedPage) : PageTranslationState
    data object NoText : PageTranslationState
    data class Failed(val message: String) : PageTranslationState
}

/** The engine's methods are only invoked after explicit user action, except local model inspection. */
internal interface PageTranslationEngine<in Input> : Closeable {
    suspend fun modelAvailable(): Boolean
    suspend fun downloadModel(wifiOnly: Boolean)
    suspend fun recognize(input: Input): List<String>
    suspend fun translate(english: String): String
}

/** Process-memory text only; neither comic bitmaps nor source images are stored here. */
internal class PageTranslationCache(private val maximumPages: Int = 16, private val maximumCharacters: Int = 160_000) {
    private val pages = LinkedHashMap<String, TranslatedPage>(16, .75f, true)
    private fun key(pageKey: String) = "mlkit-latin16.0.1-translation17.0.3:en-zh:$pageKey"
    private fun TranslatedPage.characters() = blocks.sumOf { it.original.length + it.chinese.length }
    @Synchronized fun get(pageKey: String): TranslatedPage? = pages[key(pageKey)]
    @Synchronized fun put(page: TranslatedPage) {
        if (page.characters() > maximumCharacters || maximumPages <= 0) return
        pages.remove(key(page.pageKey))
        while (pages.isNotEmpty() && (pages.size >= maximumPages || pages.values.sumOf { it.characters() } + page.characters() > maximumCharacters)) {
            val iterator = pages.entries.iterator(); iterator.next(); iterator.remove()
        }
        pages[key(page.pageKey)] = page
    }
    @Synchronized fun clear() { pages.clear() }
}

internal class PageTranslationController<Input>(
    private val engine: PageTranslationEngine<Input>,
    private val cache: PageTranslationCache = PageTranslationCache(),
) : Closeable {
    private val revision = AtomicLong()
    private val operation = Mutex()
    private val mutableState = MutableStateFlow<PageTranslationState>(PageTranslationState.CheckingModel)
    val state: StateFlow<PageTranslationState> = mutableState
    private var selectedKey = ""
    private var running: Job? = null
    private var modelReady = false
    private var closed = false

    suspend fun openPage(pageKey: String) {
        check(!closed)
        running?.cancel()
        selectedKey = pageKey
        val ticket = revision.incrementAndGet()
        cache.get(pageKey)?.let { mutableState.value = PageTranslationState.Complete(it); return }
        mutableState.value = PageTranslationState.CheckingModel
        try {
            val available = engine.modelAvailable()
            currentCoroutineContext().ensureActive()
            if (isCurrent(pageKey, ticket)) {
                modelReady = available
                mutableState.value = if (available) PageTranslationState.ReadyToTranslate else PageTranslationState.ModelRequired
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (isCurrent(pageKey, ticket)) mutableState.value = PageTranslationState.Failed("暂时无法检查翻译模型。请关闭后重试。")
        }
    }

    suspend fun translatePage(pageKey: String, input: Input, allowModelDownload: Boolean, wifiOnly: Boolean = true) {
        val ticket = revision.get()
        if (!isCurrent(pageKey, ticket)) return
        operation.withLock {
            if (!isCurrent(pageKey, ticket)) return@withLock
            cache.get(pageKey)?.let { mutableState.value = PageTranslationState.Complete(it); return@withLock }
            running = currentCoroutineContext()[Job]
            var phase = "check"
            try {
                modelReady = engine.modelAvailable()
                currentCoroutineContext().ensureActive()
                if (!isCurrent(pageKey, ticket)) return@withLock
                if (!modelReady) {
                    if (!allowModelDownload) { mutableState.value = PageTranslationState.ModelRequired; return@withLock }
                    phase = "download"
                    mutableState.value = PageTranslationState.Downloading
                    engine.downloadModel(wifiOnly)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(pageKey, ticket)) return@withLock
                    modelReady = true
                }
                phase = "ocr"
                mutableState.value = PageTranslationState.Recognizing
                val recognized = engine.recognize(input).map(String::trim).filter(String::isNotEmpty)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(pageKey, ticket)) return@withLock
                if (recognized.isEmpty()) { mutableState.value = PageTranslationState.NoText; return@withLock }
                val blocks = mutableListOf<TranslatedPageBlock>()
                var remaining = 16_000
                var truncated = recognized.size > 60
                phase = "translate"
                val limited = recognized.take(60)
                for ((number, text) in limited.withIndex()) {
                    if (remaining <= 0) { truncated = true; break }
                    if (!isCurrent(pageKey, ticket)) return@withLock
                    val original = text.take(minOf(remaining, 2_000))
                    if (original.length != text.length) truncated = true
                    remaining -= original.length
                    mutableState.value = PageTranslationState.Translating(number, limited.size)
                    val translated = engine.translate(original)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(pageKey, ticket)) return@withLock
                    if (translated.length > 4_000) truncated = true
                    blocks += TranslatedPageBlock(original, translated.take(4_000).ifBlank { "（未取得译文）" })
                }
                if (isCurrent(pageKey, ticket)) {
                    val page = TranslatedPage(pageKey, blocks, truncated)
                    cache.put(page)
                    mutableState.value = PageTranslationState.Complete(page)
                }
            } catch (cancelled: CancellationException) {
                if (isCurrent(pageKey, ticket)) mutableState.value = if (modelReady) PageTranslationState.ReadyToTranslate else PageTranslationState.ModelRequired
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(pageKey, ticket)) mutableState.value = PageTranslationState.Failed(when (phase) {
                    "download" -> "语言模型尚未下载成功。请检查网络或更换 Wi-Fi 后重试；模型可用前不能离线翻译。"
                    "ocr" -> "本页文字识别失败。请重新打开本页后重试，艺术字体和过小文字可能无法识别。"
                    "translate" -> "本页翻译未完成。请关闭后重试，原漫画没有被修改。"
                    else -> "翻译模型暂不可用。请关闭后重试。"
                })
            } finally { if (running == currentCoroutineContext()[Job]) running = null }
        }
    }

    private fun isCurrent(pageKey: String, ticket: Long) = !closed && selectedKey == pageKey && revision.get() == ticket
    override fun close() {
        if (closed) return
        closed = true
        revision.incrementAndGet()
        running?.cancel()
        running = null
        engine.close()
    }
}

internal class MlKitPageTranslationEngine : PageTranslationEngine<Bitmap> {
    private val recognizer = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val translator = lazy { Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.CHINESE).build()) }

    override suspend fun modelAvailable(): Boolean = RemoteModelManager.getInstance().isModelDownloaded(
        TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build(),
    ).awaitTranslationTask()

    override suspend fun downloadModel(wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        translator.value.downloadModelIfNeeded(conditions).awaitTranslationTask()
    }

    override suspend fun recognize(input: Bitmap): List<String> {
        require(!input.isRecycled && input.width.toLong() * input.height <= 4_100_000) { "Page bitmap exceeds the translation input limit" }
        val result = recognizer.value.process(InputImage.fromBitmap(input, 0)).awaitTranslationTask()
        return result.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
            .map { it.text }
    }

    override suspend fun translate(english: String): String = translator.value.translate(english).awaitTranslationTask()
    override fun close() {
        if (recognizer.isInitialized()) recognizer.value.close()
        if (translator.isInitialized()) translator.value.close()
    }
}

private suspend fun <T> Task<T>.awaitTranslationTask(): T = suspendCancellableCoroutine { continuation ->
    // ML Kit model downloads do not expose a cancellation token. Do not let a
    // long-running Task retain a cancelled page's coroutine/Bitmap until it ends.
    val waiting = AtomicReference<kotlinx.coroutines.CancellableContinuation<T>?>(continuation)
    continuation.invokeOnCancellation { waiting.set(null) }
    addOnSuccessListener { value -> waiting.getAndSet(null)?.let { if (it.isActive) it.resume(value) } }
    addOnFailureListener { error -> waiting.getAndSet(null)?.let { if (it.isActive) it.resumeWithException(error) } }
    addOnCanceledListener { waiting.getAndSet(null)?.cancel() }
}

