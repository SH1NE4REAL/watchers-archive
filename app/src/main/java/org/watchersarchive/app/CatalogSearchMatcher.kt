package org.watchersarchive.app

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Bounded local matching for source candidates already fetched by the catalogue.
 * Expanded terms are supplied by a separately sourced alias dictionary; matching
 * never renames records or treats similarly named editions as the same comic.
 */
internal object CatalogSearchMatcher {
    const val MAX_QUERY_CHARACTERS = 120
    const val MAX_CANDIDATES = 5_000
    private const val MAX_TITLE_CHARACTERS = 512
    private const val MAX_EXPANDED_TERMS = 8
    private const val MAX_QUERY_TOKENS = 16
    private const val MAX_FUZZY_COMPARISONS = 25_000
    private val digitRuns = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val issueNumbers = Regex("#\\s*([0-9]+(?:\\.[0-9]+)?)")
    private val chineseIssueNumbers = Regex("第\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[期话話]")

    /** Case, width and punctuation folding. Digits remain separate exact tokens. */
    fun normalized(text: String): String {
        val folded = Normalizer.normalize(text.take(MAX_TITLE_CHARACTERS), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = StringBuilder(folded.length)
        var offset = 0
        var separated = true
        while (offset < folded.length) {
            val point = folded.codePointAt(offset)
            offset += Character.charCount(point)
            if (Character.isLetterOrDigit(point)) {
                val digit = Character.digit(point, 10)
                if (digit >= 0) result.append(('0'.code + digit).toChar()) else result.appendCodePoint(point)
                separated = false
            } else if (!separated) {
                result.append(' ')
                separated = true
            }
        }
        return result.toString().trim()
    }

    /** Safe source-query normalization, not a promise that WordPress supports fuzzy search. */
    fun remoteQuery(query: String): String = normalized(query.take(MAX_QUERY_CHARACTERS))

    fun rank(
        records: List<CatalogSourceRecord>,
        query: String,
        expandedTerms: List<String> = emptyList(),
    ): List<CatalogSourceRecord> {
        if (query.length > MAX_QUERY_CHARACTERS) return emptyList()
        val primary = normalized(query)
        if (primary.isBlank()) return emptyList()
        val terms = (listOf(primary) + expandedTerms.asSequence().take(MAX_EXPANDED_TERMS)
            .filter { it.length <= MAX_QUERY_CHARACTERS }.map(::normalized).toList())
            .filter(String::isNotBlank).distinct().map(::Term)
            .filter { it.tokens.size <= MAX_QUERY_TOKENS }
        if (terms.isEmpty()) return emptyList()
        val requiredNumbers = numbers(query)
        val requiredIssues = issues(query)
        val fuzzyBudget = Budget(MAX_FUZZY_COMPARISONS)
        return records.asSequence().take(MAX_CANDIDATES)
            .distinctBy { it.source to it.externalId }
            .mapIndexedNotNull { position, record ->
                val score = listOf(record.title, record.displayTitle).distinct().mapNotNull { name ->
                    if (!numbers(name).containsAll(requiredNumbers) || !issues(name).containsAll(requiredIssues)) null
                    else terms.mapIndexedNotNull { index, term ->
                        score(Target(normalized(name)), term, fuzzyBudget)?.plus(if (index == 0) 20 else 0)
                    }.maxOrNull()
                }.maxOrNull() ?: return@mapIndexedNotNull null
                Match(record, score, position)
            }.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.position })
            .map { it.record }.toList()
    }

    private data class Match(val record: CatalogSourceRecord, val score: Int, val position: Int)
    private data class Term(val text: String) {
        val compact = text.replace(" ", "")
        val tokens = text.split(' ')
        val latinOnly = compact.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
    }
    private data class Target(val text: String) {
        val compact = text.replace(" ", "")
        val tokens = text.split(' ').filter(String::isNotBlank)
    }
    private class Budget(var remaining: Int)

    private fun score(target: Target, term: Term, budget: Budget): Int? {
        if (target.text == term.text) return 1_000
        if (target.compact == term.compact) return 980
        if ((" ${target.text} ").contains(" ${term.text} ")) return 920
        if (term.compact.length >= 4 && target.compact.contains(term.compact)) return 860
        // Chinese text only uses exact fragments or sourced alias expansions.
        // Misspelled Chinese characters are never guessed using edit distance.
        if (!term.latinOnly && term.tokens.any { token -> token.codePoints().anyMatch(::isHan) }) {
            return if (target.text.contains(term.text) || target.compact.contains(term.compact)) 850 else null
        }

        val used = mutableSetOf<Int>()
        var penalty = 0
        for (token in term.tokens) {
            val exact = target.tokens.indexOfFirstIndexed { index, candidate -> index !in used &&
                (candidate == token || (token.all(Char::isDigit) && candidate.all(Char::isDigit) && canonicalNumber(candidate) == canonicalNumber(token))) }
            if (exact >= 0) { used += exact; continue }
            val prefix = target.tokens.indexOfFirstIndexed { index, candidate ->
                index !in used && token.length >= 2 && candidate.startsWith(token) && !token.all(Char::isDigit)
            }
            if (prefix >= 0) { used += prefix; penalty += 18; continue }
            val maximum = editAllowance(token)
            if (maximum == 0) return null
            var bestIndex = -1
            var bestDistance = maximum + 1
            for ((index, candidate) in target.tokens.withIndex()) {
                if (index in used || !potentialTypo(token, candidate, maximum)) continue
                if (budget.remaining-- <= 0) break
                val distance = editDistance(token, candidate, maximum)
                if (distance < bestDistance) { bestIndex = index; bestDistance = distance }
            }
            if (bestIndex < 0) {
                // A user may omit a known title's hyphen/space and misspell one
                // letter: e.g. "spidermen" vs "Spider-Man". Try bounded joins.
                if (term.tokens.size != 1) return null
                return joinedTypoScore(target, token, maximum, budget)
            }
            used += bestIndex
            penalty += 60 * bestDistance
        }
        return 800 - penalty
    }

    private fun joinedTypoScore(target: Target, query: String, maximum: Int, budget: Budget): Int? {
        var best = maximum + 1
        for (start in target.tokens.indices) {
            var joined = ""
            for (end in start until minOf(start + 3, target.tokens.size)) {
                joined += target.tokens[end]
                if (end == start || !potentialTypo(query, joined, maximum)) continue
                if (budget.remaining-- <= 0) return null
                best = minOf(best, editDistance(query, joined, maximum))
            }
        }
        return if (best <= maximum) 730 - 60 * best else null
    }

    private fun editAllowance(word: String): Int = if (!word.codePoints().allMatch {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN
    }) 0 else when (word.codePointCount(0, word.length)) {
        in 4..7 -> 1
        in 8..32 -> 2
        else -> 0
    }

    private fun potentialTypo(query: String, candidate: String, maximum: Int): Boolean {
        if (candidate.length !in 2..34 || abs(query.length - candidate.length) > maximum) return false
        if (!candidate.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }) return false
        if (query.first() == candidate.first()) return true
        // Still allow a typo in the first letter if the rest has enough overlap.
        var shared = 0
        for (index in 0 until query.lastIndex) if (candidate.contains(query.substring(index, index + 2)) && ++shared >= 2) return true
        return false
    }

    /** Banded optimal-string-alignment distance; adjacent transpositions cost one. */
    private fun editDistance(first: String, second: String, maximum: Int): Int {
        val a = first.codePoints().toArray()
        val b = second.codePoints().toArray()
        if (abs(a.size - b.size) > maximum) return maximum + 1
        val infinity = maximum + 1
        var beforePrevious = IntArray(b.size + 1) { infinity }
        var previous = IntArray(b.size + 1) { minOf(it, infinity) }
        for (row in 1..a.size) {
            val current = IntArray(b.size + 1) { infinity }
            current[0] = minOf(row, infinity)
            var rowMinimum = infinity
            for (column in maxOf(1, row - maximum)..minOf(b.size, row + maximum)) {
                var value = minOf(current[column - 1] + 1, previous[column] + 1,
                    previous[column - 1] + if (a[row - 1] == b[column - 1]) 0 else 1)
                if (row > 1 && column > 1 && a[row - 1] == b[column - 2] && a[row - 2] == b[column - 1]) {
                    value = minOf(value, beforePrevious[column - 2] + 1)
                }
                current[column] = minOf(value, infinity)
                rowMinimum = minOf(rowMinimum, current[column])
            }
            if (rowMinimum > maximum) return infinity
            beforePrevious = previous
            previous = current
        }
        return previous[b.size]
    }

    private fun numbers(text: String): Set<String> = digitRuns.findAll(numberText(text)).map { canonicalNumeric(it.value) }.toSet()
    private fun issues(text: String): Set<String> {
        val folded = numberText(text)
        return sequenceOf(issueNumbers, chineseIssueNumbers).flatMap { it.findAll(folded) }
            .map { canonicalNumeric(it.groupValues[1]) }.toSet()
    }
    private fun canonicalNumeric(number: String): String = number.split('.', limit = 2).let { parts ->
        canonicalNumber(parts[0]) + (parts.getOrNull(1)?.let { ".$it" } ?: "")
    }
    private fun numberText(text: String): String {
        val folded = Normalizer.normalize(text.take(MAX_TITLE_CHARACTERS), Normalizer.Form.NFKC)
        return buildString(folded.length) {
            folded.codePoints().forEach { point ->
                val digit = Character.digit(point, 10)
                if (digit >= 0) append(('0'.code + digit).toChar()) else appendCodePoint(point)
            }
        }
    }
    private fun canonicalNumber(number: String): String = number.trimStart('0').ifEmpty { "0" }
    private fun isHan(point: Int): Boolean = Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN

    private inline fun <T> List<T>.indexOfFirstIndexed(predicate: (Int, T) -> Boolean): Int {
        for (index in indices) if (predicate(index, this[index])) return index
        return -1
    }
}
