package org.watchersarchive.app

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.tasks.Task

internal data class EnglishToken(
    val id: String,
    val text: String,
    val bounds: RectF,
    val lineText: String,
    val lineId: String = id,
)

internal data class EnglishPageTokens(
    val pageKey: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val tokens: List<EnglishToken>,
    val truncated: Boolean,
)

internal data class EnglishDictionaryEntry(
    val original: String,
    val phonetic: String? = null,
    val pronunciation: String? = null,
    val meaning: String = "暂未取得词典解释",
    val partOfSpeech: String? = null,
    val context: String? = null,
    val englishDefinition: String? = null,
    val contextMeaning: String? = null,
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    val requiresModel: Boolean = false,
    val notice: String? = null,
    /** Domain candidates are distinct from the ordinary dictionary definition. */
    val marvelNames: List<MarvelNameMatch> = emptyList(),
)

internal interface EnglishDictionaryProvider {
    suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry?
}

internal object EmptyEnglishDictionaryProvider : EnglishDictionaryProvider {
    override suspend fun lookup(token: EnglishToken, page: EnglishPageTokens): EnglishDictionaryEntry? = null
}

internal sealed interface EnglishAssistantState {
    data object Idle : EnglishAssistantState
    data object Recognizing : EnglishAssistantState
    data class Ready(val page: EnglishPageTokens) : EnglishAssistantState
    data class LookingUp(val page: EnglishPageTokens, val token: EnglishToken) : EnglishAssistantState
    data class Selected(val page: EnglishPageTokens, val token: EnglishToken, val entry: EnglishDictionaryEntry) : EnglishAssistantState
    data class Failed(val message: String) : EnglishAssistantState
}

internal class EnglishTokenCache(private val maxPages: Int = 16, private val maxCharacters: Int = 160_000) {
    private val pages = LinkedHashMap<String, EnglishPageTokens>(maxPages, .75f, true)
    init { require(maxPages in 1..16 && maxCharacters in 1..160_000) }
    private fun EnglishPageTokens.characters() = tokens.sumOf { it.text.length + it.lineText.length }
    @Synchronized fun get(key: String): EnglishPageTokens? = pages[key]
    @Synchronized fun put(page: EnglishPageTokens) {
        if (page.tokens.size > 512 || page.characters() > maxCharacters) return
        pages.remove(page.pageKey)
        while (pages.isNotEmpty() && (pages.size >= maxPages || pages.values.sumOf { it.characters() } + page.characters() > maxCharacters)) {
            pages.entries.iterator().let { it.next(); it.remove() }
        }
        pages[page.pageKey] = page
    }
    @Synchronized fun clear() = pages.clear()
}

internal interface EnglishOcrEngine : Closeable {
    suspend fun recognize(bitmap: Bitmap, pageKey: String): EnglishPageTokens
}

internal class MlKitEnglishOcrEngine : EnglishOcrEngine {
    companion object { private val recognitionGate = Semaphore(1) }
    private val recognizer: Lazy<TextRecognizer> = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val engineLock = Any()

    override suspend fun recognize(bitmap: Bitmap, pageKey: String): EnglishPageTokens {
        require(!bitmap.isRecycled && bitmap.width.toLong() * bitmap.height <= 4_100_000) { "阅读页面过大，无法开启英文助手" }
        // Cancelling the coroutine cannot cancel native ML Kit work. Keep the
        // single permit until that Task itself completes, so quick page turns do
        // not queue a Bitmap per page in the recognizer.
        recognitionGate.acquire()
        val task = try {
            currentCoroutineContext().ensureActive()
            synchronized(engineLock) {
                check(!closed.get())
                recognizer.value.process(InputImage.fromBitmap(bitmap, 0))
            }.also { task ->
                task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { recognitionGate.release() }
            }
        } catch (failure: Throwable) { recognitionGate.release(); throw failure }
        val result = task.awaitEnglishTask()
        var characters = 0
        var truncated = false
        val tokens = mutableListOf<EnglishToken>()
        val blocks = result.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
        if (blocks.size > 60) truncated = true
        recognition@ for ((blockIndex, block) in blocks.take(60).withIndex()) {
                for ((lineIndex, line) in block.lines.withIndex()) {
                    val lineText = line.text.trim().take(2_000)
                    val lineId = "$blockIndex:$lineIndex"
                    for (element in line.elements) {
                        val text = element.text.trim()
                        val rect = element.boundingBox
                        if (text.isBlank() || rect == null || text.none { it in 'A'..'Z' || it in 'a'..'z' }) continue
                        if (tokens.size >= 512 || characters + text.length > 16_000) { truncated = true; break@recognition }
                        val left = rect.left.coerceIn(0, bitmap.width).toFloat()
                        val top = rect.top.coerceIn(0, bitmap.height).toFloat()
                        val right = rect.right.coerceIn(0, bitmap.width).toFloat()
                        val bottom = rect.bottom.coerceIn(0, bitmap.height).toFloat()
                        if (right <= left || bottom <= top) continue
                        characters += text.length
                        tokens += EnglishToken("${tokens.size}:${rect.left}:${rect.top}", text, RectF(left, top, right, bottom), lineText, lineId)
                    }
                }
        }
        return EnglishPageTokens(pageKey, bitmap.width, bitmap.height, tokens, truncated)
    }

    override fun close() = synchronized(engineLock) {
        if (closed.compareAndSet(false, true) && recognizer.isInitialized()) recognizer.value.close()
    }
}

internal class EnglishAssistantController(
    private val engine: EnglishOcrEngine,
    private val dictionary: EnglishDictionaryProvider = EmptyEnglishDictionaryProvider,
    private val cache: EnglishTokenCache = EnglishTokenCache(),
) : Closeable {
    private val lock = Any()
    private var revision = 0L
    private var selectionRevision = 0L
    private val mutableState = MutableStateFlow<EnglishAssistantState>(EnglishAssistantState.Idle)
    val state: StateFlow<EnglishAssistantState> = mutableState
    private var currentPage = ""
    private var closed = false
    private var ocrJob: Job? = null
    private var lookupJob: Job? = null

    suspend fun openPage(pageKey: String, bitmap: Bitmap) = coroutineScope {
        require(pageKey.isNotBlank() && pageKey.length <= 2_048)
        val running = checkNotNull(currentCoroutineContext()[Job])
        val (ticket, previous) = synchronized(lock) {
            check(!closed)
            val previous = listOfNotNull(ocrJob, lookupJob)
            currentPage = pageKey
            revision++
            selectionRevision++
            ocrJob = running
            lookupJob = null
            mutableState.value = EnglishAssistantState.Recognizing
            revision to previous
        }
        previous.forEach { if (it !== running) it.cancel() }
        try {
            currentCoroutineContext().ensureActive()
            cache.get(pageKey)?.takeIf { it.imageWidth == bitmap.width && it.imageHeight == bitmap.height }?.let { page ->
                synchronized(lock) { if (isCurrent(pageKey, ticket)) mutableState.value = EnglishAssistantState.Ready(page) }
                return@coroutineScope
            }
            val page = engine.recognize(bitmap, pageKey)
            currentCoroutineContext().ensureActive()
            require(page.pageKey == pageKey && page.imageWidth == bitmap.width && page.imageHeight == bitmap.height)
            synchronized(lock) {
                if (isCurrent(pageKey, ticket)) {
                    cache.put(page)
                    mutableState.value = EnglishAssistantState.Ready(page)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { synchronized(lock) {
            if (isCurrent(pageKey, ticket)) mutableState.value = EnglishAssistantState.Failed("本页英文识别失败，请稍后重试。")
        } }
        finally { synchronized(lock) { if (ocrJob === running) ocrJob = null } }
    }

    suspend fun lookup(token: EnglishToken, expectedPageKey: String? = null) = coroutineScope {
        val running = checkNotNull(currentCoroutineContext()[Job])
        val request = synchronized(lock) {
            val page = mutableState.value.pageOrNull() ?: return@coroutineScope
            if (closed || currentPage != page.pageKey || (expectedPageKey != null && page.pageKey != expectedPageKey) ||
                !validSelection(page, token)) return@coroutineScope
            val previous = lookupJob
            lookupJob = running
            selectionRevision++
            mutableState.value = EnglishAssistantState.LookingUp(page, token)
            LookupRequest(page, revision, selectionRevision, previous)
        }
        request.previous?.let { if (it !== running) it.cancel() }
        try {
                val entry = dictionary.lookup(token, request.page)
                    ?: EnglishDictionaryEntry(token.text, context = token.lineText.takeIf { it != token.text })
                currentCoroutineContext().ensureActive()
                synchronized(lock) { if (isSelectionCurrent(request)) {
                    mutableState.value = EnglishAssistantState.Selected(request.page, token, entry)
                } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { synchronized(lock) {
            if (isSelectionCurrent(request)) {
                mutableState.value = EnglishAssistantState.Selected(request.page, token,
                    EnglishDictionaryEntry(token.text, context = token.lineText.takeIf { it != token.text }))
            }
        } }
        finally { synchronized(lock) { if (lookupJob === running) lookupJob = null } }
    }

    fun dismissSelection() {
        val previous = synchronized(lock) {
            val page = mutableState.value.pageOrNull() ?: return
            selectionRevision++
            mutableState.value = EnglishAssistantState.Ready(page)
            lookupJob.also { lookupJob = null }
        }
        previous?.cancel()
    }

    /** Disable without destroying the bounded text-only cache, so toggling back is cheap. */
    fun disable() {
        val previous = synchronized(lock) {
            revision++; selectionRevision++; currentPage = ""
            mutableState.value = EnglishAssistantState.Idle
            listOfNotNull(ocrJob, lookupJob).also { ocrJob = null; lookupJob = null }
        }
        previous.forEach { it.cancel() }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        disable()
        engine.close()
        cache.clear()
    }

    private fun isCurrent(pageKey: String, ticket: Long) = !closed && currentPage == pageKey && revision == ticket
    private data class LookupRequest(val page: EnglishPageTokens, val revision: Long, val selection: Long, val previous: Job?)
    private fun isSelectionCurrent(request: LookupRequest) = isCurrent(request.page.pageKey, request.revision) && selectionRevision == request.selection
}

private suspend fun <T> Task<T>.awaitEnglishTask(): T = suspendCancellableCoroutine { continuation ->
    // ML Kit does not expose task cancellation. Drop its reference to the
    // cancelled coroutine so its captured page Bitmap is not retained by listeners.
    val waiting = AtomicReference<kotlinx.coroutines.CancellableContinuation<T>?>(continuation)
    continuation.invokeOnCancellation { waiting.set(null) }
    addOnSuccessListener { value -> waiting.getAndSet(null)?.let { if (it.isActive) it.resume(value) } }
    addOnFailureListener { error -> waiting.getAndSet(null)?.let { if (it.isActive) it.resumeWithException(error) } }
    addOnCanceledListener { waiting.getAndSet(null)?.cancel() }
}

internal fun EnglishAssistantState.pageOrNull(): EnglishPageTokens? = when (this) {
    is EnglishAssistantState.Ready -> page
    is EnglishAssistantState.LookingUp -> page
    is EnglishAssistantState.Selected -> page
    else -> null
}

/** A phrase is an ordered, contiguous set of at most twelve words in one OCR line. */
internal fun phraseFromRange(page: EnglishPageTokens, startId: String, endId: String): EnglishToken? {
    val start = page.tokens.indexOfFirst { it.id == startId }
    val end = page.tokens.indexOfFirst { it.id == endId }
    if (start < 0 || end < 0) return null
    return phraseAtIndices(page, minOf(start, end), maxOf(start, end))
}

private fun phraseAtIndices(page: EnglishPageTokens, start: Int, end: Int): EnglishToken? {
    if (start < 0 || end >= page.tokens.size || end < start || end - start >= 12) return null
    val selected = page.tokens.subList(start, end + 1)
    val first = selected.first()
    if (selected.any { it.lineId != first.lineId }) return null
    if (selected.size == 1) return first
    val bounds = RectF(first.bounds)
    selected.drop(1).forEach { bounds.union(it.bounds) }
    return EnglishToken("phrase:$start:$end", selected.joinToString(" ") { it.text }, bounds, first.lineText, first.lineId)
}

private fun validSelection(page: EnglishPageTokens, token: EnglishToken): Boolean {
    val actual = page.tokens.firstOrNull { it.id == token.id }
        ?: Regex("phrase:([0-9]+):([0-9]+)").matchEntire(token.id)?.let { match ->
            val first = match.groupValues[1].toIntOrNull() ?: return@let null
            val last = match.groupValues[2].toIntOrNull() ?: return@let null
            phraseAtIndices(page, first, last)
        } ?: return false
    return actual == token
}
