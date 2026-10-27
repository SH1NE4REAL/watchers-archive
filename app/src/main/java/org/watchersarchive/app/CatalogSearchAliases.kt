package org.watchersarchive.app

import java.net.URLEncoder
import java.text.Normalizer

/** A search alias identifies a subject; it never identifies or merges an edition. */
enum class NameAliasKind { COMIC_TITLE, EVENT, CHARACTER }

enum class NameAliasEvidence { OFFICIAL_PUBLISHER, OFFICIAL_CHARACTER, COMMON }

data class CatalogNameAlias(
    val id: String,
    val chineseNames: List<String>,
    val englishNames: List<String>,
    val kind: NameAliasKind,
    val evidence: NameAliasEvidence,
    val sourceUrl: String,
    val evidenceNote: String,
    /** Edition/region notes are evidence, not an automatic version-selection rule. */
    val scope: String? = null,
    val checkedAt: String = "2026-10-27",
)

/**
 * Small editorial search vocabulary. These Chinese forms are explicitly COMMON:
 * no Chinese publisher/ISBN evidence was verified for them in this release.
 * Marvel links refer to the English subject, not proof of an official Chinese title.
 */
object CatalogSearchAliases {
    private const val commonNote = "常用中文检索别名；未核实对应授权中文出版书目，不是官方中文书名或中文正文证明。Marvel 链接仅为英文主题检索入口。"

    private fun common(id: String, chinese: List<String>, english: String, kind: NameAliasKind, scope: String? = null) =
        CatalogNameAlias(id, chinese, listOf(english), kind, NameAliasEvidence.COMMON,
            "https://www.marvel.com/search?query=" + URLEncoder.encode(english, "UTF-8"), commonNote, scope)

    val entries: List<CatalogNameAlias> = listOf(
        common("civil-war", listOf("内战", "內戰"), "Civil War", NameAliasKind.EVENT,
            "同名事件、后续作品和合集仍按各自年份、卷次、期号及来源记录分别保留。"),
        common("secret-wars", listOf("秘密战争", "秘密戰爭"), "Secret Wars", NameAliasKind.EVENT,
            "1984 年与 2015 年等同名作品是不同候选，别名不选择或合并其中一个版本。"),
        common("infinity-gauntlet", listOf("无限手套", "無限手套"), "Infinity Gauntlet", NameAliasKind.EVENT,
            "事件/同名系列主题检索；单期、重印、合集和 omnibus 保持独立。"),
        common("amazing-spider-man", listOf("神奇蜘蛛侠", "神奇蜘蛛俠"), "Amazing Spider-Man", NameAliasKind.COMIC_TITLE,
            "系列检索词，不选择某一卷或发行年；没有采用电影中文片名作为漫画官译证据。"),
        common("immortal-hulk", listOf("不朽浩克", "不朽绿巨人", "不朽綠巨人"), "Immortal Hulk", NameAliasKind.COMIC_TITLE),
        common("spider-man", listOf("蜘蛛侠", "蜘蛛俠", "蜘蛛人"), "Spider-Man", NameAliasKind.CHARACTER),
        common("iron-man", listOf("钢铁侠", "鋼鐵俠", "鋼鐵人"), "Iron Man", NameAliasKind.CHARACTER),
        common("captain-america", listOf("美国队长", "美國隊長"), "Captain America", NameAliasKind.CHARACTER),
        common("thor", listOf("雷神", "索尔", "索爾"), "Thor", NameAliasKind.CHARACTER),
        common("hulk", listOf("绿巨人", "綠巨人", "浩克"), "Hulk", NameAliasKind.CHARACTER),
        common("black-widow", listOf("黑寡妇", "黑寡婦"), "Black Widow", NameAliasKind.CHARACTER),
        common("wolverine", listOf("金刚狼", "金剛狼"), "Wolverine", NameAliasKind.CHARACTER),
        common("deadpool", listOf("死侍"), "Deadpool", NameAliasKind.CHARACTER),
        common("avengers", listOf("复仇者联盟", "復仇者聯盟", "复仇者", "復仇者"), "Avengers", NameAliasKind.CHARACTER,
            "团队名称检索；不等于某个复仇者漫画系列、电影或合集的版本匹配。"),
        common("x-men", listOf("X战警", "X戰警"), "X-Men", NameAliasKind.CHARACTER,
            "团队名称检索；年份、卷次和角色分支不自动合并。"),
    )

    private data class Replacement(val chinese: String, val english: String)
    private val replacements = entries.flatMap { entry ->
        entry.chineseNames.map { Replacement(normalize(it), entry.englishNames.first()) }
    }.sortedByDescending { it.chinese.length }

    /**
     * Translate only listed names, choosing the longest at a position. Year/issue
     * information and unknown text are retained; unknown Chinese is never guessed.
     * A two-or-more-character unambiguous prefix may broaden a subject search.
     */
    fun expandedTerms(query: String): List<String> {
        val input = normalize(query).trim()
        if (input.length !in 2..120) return emptyList()
        val expanded = StringBuilder(input.length + 32)
        var matched = false
        var position = 0
        while (position < input.length) {
            val alias = replacements.firstOrNull { input.regionMatches(position, it.chinese, 0, it.chinese.length, ignoreCase = true) }
            if (alias != null) {
                expanded.append(' ').append(alias.english).append(' ')
                position += alias.chinese.length
                matched = true
            } else expanded.append(input[position++])
        }
        if (matched) {
            val term = expanded.toString()
                .replace(Regex("第\\s*(\\d+)\\s*[期话話]"), " #$1 ")
                .replace(Regex("第\\s*(\\d+)\\s*卷"), " Vol. $1 ")
                .replace(Regex("\\s+"), " ").trim()
            return listOf(term).filter { it.length <= 240 }
        }
        // Prefixes help subjects such as 蜘蛛/复仇, without mapping one Han letter
        // or a longer different title (e.g. 无限战争) to an unrelated known event.
        if (input.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) {
            return replacements.filter { it.chinese.startsWith(input) }.map { it.english }.distinct().take(3)
        }
        return emptyList()
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
}
