package org.watchersarchive.app

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal enum class MarvelNameKind(val label: String) {
    CHARACTER("角色"), TEAM("团队"), ORGANIZATION("组织"), PLACE("地点"),
}

internal enum class MarvelChineseEvidence(val label: String) {
    COMMON("常用中文名"), USER_PREFERRED("用户指定译名"),
}

/** English reference links and Chinese-name evidence deliberately remain separate. */
internal data class MarvelReadingTerm(
    val id: String,
    val englishName: String,
    val chineseName: String,
    val aliases: List<String>,
    val kind: MarvelNameKind,
    val englishSourceUrl: String,
    val chineseEvidence: MarvelChineseEvidence,
    val note: String,
    val checkedAt: String = "2026-10-28",
)

/** A candidate in the reading context, never an assertion replacing ordinary meaning. */
internal data class MarvelNameMatch(
    val term: MarvelReadingTerm,
    val matchedText: String,
    val contextHint: String? = null,
    val correctedOcr: Boolean = false,
)

internal object MarvelReadingGlossary {
    private const val commonNote = "常用中文名，未核实对应授权中文出版书目。英文资料链接用于核对，本轮未读取页面正文；普通词义仍可能适用。"

    private fun character(id: String, english: String, chinese: String, slug: String,
        aliases: List<String> = emptyList(), note: String = commonNote) = MarvelReadingTerm(
        id, english, chinese, aliases, MarvelNameKind.CHARACTER,
        "https://www.marvel.com/characters/$slug", MarvelChineseEvidence.COMMON, note,
    )

    private fun subject(id: String, english: String, chinese: String, kind: MarvelNameKind,
        aliases: List<String> = emptyList()) = MarvelReadingTerm(
        id, english, chinese, aliases, kind,
        "https://www.marvel.com/search?query=" + URLEncoder.encode(english, "UTF-8"),
        MarvelChineseEvidence.COMMON, "常用中文名，未核实授权中文出版书目；链接是 Marvel 英文主题检索入口，不是中文官译证据。",
    )

    val entries: List<MarvelReadingTerm> = listOf(
        MarvelReadingTerm("cyclops", "Cyclops", "镭射眼", listOf("Scott Summers"), MarvelNameKind.CHARACTER,
            "https://www.marvel.com/characters/cyclops-scott-summers", MarvelChineseEvidence.USER_PREFERRED,
            "中文名采用用户指定的“镭射眼”；不是本轮核验的授权出版官译。Cyclops 也有“独眼巨人”的普通词义，请核对原句。英文页面本轮正常访问返回 403，未读取正文。"),
        character("storm", "Storm", "暴风女", "storm-ororo-munroe", listOf("Ororo Munroe")),
        character("beast", "Beast", "野兽", "beast-henry-hank-mccoy", listOf("Hank McCoy", "Henry McCoy")),
        character("thing", "Thing", "石头人", "the-thing-benjamin-grimm", listOf("The Thing", "Ben Grimm", "Benjamin Grimm")),
        character("vision", "Vision", "幻视", "vision", listOf("The Vision")),
        character("angel", "Angel", "天使", "angel-warren-worthington-iii", listOf("Warren Worthington III", "Warren Worthington")),
        character("iceman", "Iceman", "冰人", "iceman-bobby-drake", listOf("Bobby Drake", "Robert Drake")),
        character("professor-x", "Professor X", "X教授", "professor-x-charles-xavier", listOf("Professor Xavier", "Charles Xavier", "ProfessorX")),
        character("magneto", "Magneto", "万磁王", "magneto-max-eisenhardt", listOf("Max Eisenhardt", "Erik Lehnsherr")),
        character("wolverine", "Wolverine", "金刚狼", "wolverine-james-howlett", listOf("Logan", "James Howlett")),
        character("colossus", "Colossus", "钢力士", "colossus-piotr-rasputin", listOf("Piotr Rasputin", "Peter Rasputin")),
        character("nightcrawler", "Nightcrawler", "夜行者", "nightcrawler-kurt-wagner", listOf("Kurt Wagner")),
        character("scarlet-witch", "Scarlet Witch", "绯红女巫", "scarlet-witch-wanda-maximoff", listOf("Wanda Maximoff")),
        character("iron-man", "Iron Man", "钢铁侠", "iron-man-tony-stark", listOf("Tony Stark", "Anthony Stark")),
        character("spider-man", "Spider-Man", "蜘蛛侠", "spider-man-peter-parker", listOf("Spider Man", "Spiderman", "Peter Parker"),
            commonNote + "Spider-Man 代号存在不同角色，本候选不据此断定当前作品中的身份或宇宙。"),
        subject("x-men", "X-Men", "X战警", MarvelNameKind.TEAM, listOf("X Men", "Xmen")),
        subject("shield", "S.H.I.E.L.D.", "神盾局", MarvelNameKind.ORGANIZATION, listOf("SHIELD")),
        subject("hydra", "Hydra", "九头蛇", MarvelNameKind.ORGANIZATION),
        subject("wakanda", "Wakanda", "瓦坎达", MarvelNameKind.PLACE),
        subject("asgard", "Asgard", "阿斯加德", MarvelNameKind.PLACE),
    )

    private data class Alias(val normalized: String, val term: MarvelReadingTerm)
    private data class Hit(val match: MarvelNameMatch, val words: Int, val characters: Int, val position: Int)
    private val aliases = entries.flatMap { term ->
        (listOf(term.englishName) + term.aliases).map { Alias(normalized(it), term) }
    }.distinctBy { it.term.id to it.normalized }.groupBy { it.normalized }
    private val xMenIds = setOf("cyclops", "storm", "beast", "angel", "iceman", "professor-x", "magneto",
        "wolverine", "colossus", "nightcrawler", "x-men")
    private val expansionStopWords = setOf("the", "a", "an", "and", "of", "to", "in", "at")

    /**
     * Only known complete names are matched. A name window must overlap the
     * selected word/span and stay within its OCR line, with at most five words.
     * Case folding does not make a candidate more certain: comics are often caps.
     */
    fun matches(token: EnglishToken, page: EnglishPageTokens, comicTitleHint: String? = null): List<MarvelNameMatch> {
        if (page.tokens.size > 512 || token.text.length > 2_000) return emptyList()
        val selection = selection(page, token) ?: return emptyList()
        val exactWordOnly = selection.first == selection.last && normalized(token.text) in expansionStopWords
        val selectedLine = page.tokens[selection.first].lineId
        if (selection.any { page.tokens[it].lineId != selectedLine }) return emptyList()
        val lineIndices = page.tokens.indices.filter { page.tokens[it].lineId == selectedLine }
        val title = normalized(comicTitleHint.orEmpty().take(512))
        val hasXMenTitle = Regex("(?:^| )x men(?: |$)").containsMatchIn(title) ||
            title.contains("xmen") || title.contains("x战警") || title.contains("x戰警")
        val hits = mutableListOf<Hit>()
        for (start in lineIndices.indices) {
            for (end in start until minOf(start + 5, lineIndices.size)) {
                val indices = lineIndices.subList(start, end + 1)
                if (indices.none { it in selection }) continue
                // Tapping an article/preposition asks about that ordinary word.
                // A user-selected full phrase such as The Thing still matches.
                if (exactWordOnly && (start != end || indices.single() != selection.first)) continue
                val original = indices.joinToString(" ") { page.tokens[it].text }
                if (original.length > 240) continue
                val normalized = normalized(original)
                aliases[normalized].orEmpty().forEach { alias ->
                    val hint = if (hasXMenTitle && alias.term.id in xMenIds)
                        "当前书名含 X-Men 相关词，可优先核对这个候选；仍需结合原句。" else null
                    hits += Hit(MarvelNameMatch(alias.term, original, hint), end - start + 1, normalized.length, indices.first())
                }
            }
        }
        return hits.sortedWith(compareByDescending<Hit> { it.words }.thenByDescending { it.characters }
            .thenByDescending { it.match.contextHint != null }.thenBy { it.position })
            .distinctBy { it.match.term.id }.take(3).map { it.match }
    }

    private fun selection(page: EnglishPageTokens, token: EnglishToken): IntRange? {
        val exact = page.tokens.indexOfFirst { it.id == token.id }
        if (exact >= 0) return if (page.tokens[exact] == token) exact..exact else null
        val match = Regex("phrase:([0-9]+):([0-9]+)").matchEntire(token.id) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val last = match.groupValues[2].toIntOrNull() ?: return null
        if (first < 0 || last < first || last >= page.tokens.size || last - first >= 12) return null
        val actual = phraseFromRange(page, page.tokens[first].id, page.tokens[last].id)
        return if (actual == token) first..last else null
    }

    private fun normalized(input: String): String {
        var value = Normalizer.normalize(input.take(2_000), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace('’', '\'').replace('‘', '\'')
        // Registered initialisms are one name; do not join arbitrary adjacent words.
        value = Regex("(?<![a-z])(?:[a-z]\\.){2,}[a-z]?\\.?").replace(value) { it.value.replace(".", "") }
        value = Regex("([a-z][a-z-]{1,80})'s\\b").replace(value, "$1")
        return value.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }
}
