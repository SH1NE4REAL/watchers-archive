package org.watchersarchive.app

import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The remote page's continuation is separate from the number of ranked local matches. */
data class CatalogSearchPage(
    val records: List<CatalogSourceRecord>,
    val hasMore: Boolean,
    val expandedTerms: List<String> = emptyList(),
    /** One or more online requests failed; already obtained results remain usable. */
    val offlineFallback: Boolean = false,
)

/**
 * Bounded candidate discovery, not a claim of fuzzy support in WordPress itself.
 * Keep one service per repository so later pages retain the first page's query
 * plan. The small in-memory plan cache contains words only, never article bodies.
 */
internal class CatalogSearchService(
    private val localRecords: () -> List<CatalogSourceRecord>,
    private val fetch: suspend (query: String, page: Int, perPage: Int) -> List<CatalogSourceRecord>,
    private val persist: (List<CatalogSourceRecord>) -> Unit = {},
    private val expandQuery: (String) -> List<String> = { emptyList() },
) {
    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_REQUESTS = 3
        private const val MAX_LOCAL = 5_000
        private const val MAX_PLANS = 16
    }

    private data class QueryPlan(val remoteQueries: List<String>)
    private val planLock = Any()
    private val plans = LinkedHashMap<String, QueryPlan>(MAX_PLANS, .75f, true)

    suspend fun search(query: String, page: Int = 1): CatalogSearchPage {
        val requested = query.trim()
        require(requested.length in 2..120) { "请输入 2–120 个字符。" }
        require(page >= 1) { "搜索页码无效。" }
        currentCoroutineContext().ensureActive()

        val expanded = expandQuery(requested).asSequence().map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 120 }
            .distinctBy(CatalogSearchMatcher::normalized).take(MAX_REQUESTS).toList()
        val digits = Regex("[0-9]+").findAll(CatalogSearchMatcher.normalized(requested))
            .map { it.value }.distinct().toList()
        val baseQueries = (expanded.ifEmpty { listOf(requested) })
            .map { term -> withRequestedDigits(CatalogSearchMatcher.remoteQuery(term), digits) }
            .filter(String::isNotBlank).distinct().take(MAX_REQUESTS)
        require(baseQueries.isNotEmpty()) { "请输入漫画名称、系列或角色。" }
        val key = CatalogSearchMatcher.normalized(requested)
        val remembered = if (page > 1) synchronized(planLock) { plans[key] } else null
        val remoteQueries = remembered?.remoteQueries ?: baseQueries
        val local = if (page == 1) localRecords().take(MAX_LOCAL) else emptyList()
        val candidates = ArrayList<CatalogSourceRecord>(PAGE_SIZE * MAX_REQUESTS)
        var successfulRequests = 0
        var requests = 0
        var firstFailure: Exception? = null
        var hasMore = false
        var selectedQueries = remoteQueries
        var firstWasEmpty = false

        suspend fun fetchCandidates(term: String): Boolean {
            if (requests >= MAX_REQUESTS) return false
            requests++
            return try {
                currentCoroutineContext().ensureActive()
                val received = fetch(term, page, PAGE_SIZE)
                currentCoroutineContext().ensureActive()
                successfulRequests++
                hasMore = hasMore || received.size >= PAGE_SIZE
                candidates += received.take(PAGE_SIZE)
                if (requests == 1) firstWasEmpty = received.isEmpty()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (firstFailure == null) firstFailure = failure
                false
            }
        }

        for (term in remoteQueries) {
            val succeeded = fetchCandidates(term)
            // The source is unavailable; querying the same host with every
            // spelling is not an appropriate retry strategy.
            if (!succeeded && successfulRequests == 0) break
        }
        if (remembered == null && expanded.isEmpty() && successfulRequests == 1 && firstWasEmpty && firstFailure == null) {
            conservativePrefix(baseQueries.singleOrNull())?.let { fallback ->
                if (fetchCandidates(fallback)) selectedQueries = listOf(fallback)
            }
        }
        currentCoroutineContext().ensureActive()

        val rankedLocal = CatalogSearchMatcher.rank(local, requested, expanded)
        if (successfulRequests == 0 && firstFailure != null && rankedLocal.isEmpty()) {
            throw IOException("在线搜索暂时不可用，请检查网络后重试。", firstFailure)
        }
        val uniqueRemote = candidates.distinctBy { it.source to it.externalId }
        if (uniqueRemote.isNotEmpty()) persist(uniqueRemote)
        currentCoroutineContext().ensureActive()
        if (page == 1 && successfulRequests > 0) rememberPlan(key, selectedQueries)
        return CatalogSearchPage(
            // Fresh remote candidates must not be pushed past the matcher's
            // 5,000-record budget by an unrelated full local snapshot. Prefer
            // their current metadata when an article ID is already cached.
            records = CatalogSearchMatcher.rank(uniqueRemote + rankedLocal, requested, expanded),
            hasMore = hasMore,
            expandedTerms = expanded,
            offlineFallback = firstFailure != null,
        )
    }

    private fun rememberPlan(key: String, queries: List<String>) = synchronized(planLock) {
        plans[key] = QueryPlan(queries)
        while (plans.size > MAX_PLANS) {
            val oldest = plans.entries.iterator()
            oldest.next(); oldest.remove()
        }
    }

    private fun withRequestedDigits(term: String, digits: List<String>): String {
        val present = Regex("[0-9]+").findAll(term).map { it.value }.toSet()
        return (listOf(term) + digits.filterNot(present::contains)).joinToString(" ").trim()
    }

    private fun conservativePrefix(term: String?): String? {
        if (term == null || term.any { it.code > 127 }) return null
        val words = term.split(' ').filter(String::isNotBlank)
        val word = words.firstOrNull { it.length >= 5 && it.all(Char::isLetter) } ?: return null
        val count = (word.length / 2).coerceIn(4, 6)
        if (count >= word.length) return null
        val digits = words.filter { token -> token.all(Char::isDigit) }
        return (listOf(word.take(count)) + digits).joinToString(" ").takeUnless { it == term }
    }
}
