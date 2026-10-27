package org.watchersarchive.app

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reuses recent validation metadata, never archive bytes or a live connection.
 * Actual reader opening still discovers the object and compares its strong
 * ETag/length with this snapshot before a page can be delivered.
 */
internal object VerifiedReadingPreparation {
    private val maximumAge = Duration.ofMinutes(10)

    suspend fun resolve(
        record: CatalogSourceRecord,
        saved: SourceAssetInspection?,
        now: Instant = Instant.now(),
        refresh: suspend () -> SourceAsset,
    ): SourceAsset {
        currentCoroutineContext().ensureActive()
        reusableAsset(record, saved, now)?.let { return it }
        val verified = refresh()
        currentCoroutineContext().ensureActive()
        return verified
    }

    fun reusableAsset(record: CatalogSourceRecord, saved: SourceAssetInspection?, now: Instant = Instant.now()): SourceAsset? {
        val article = safeSourceUrl(record.canonicalUrl) ?: return null
        if (record.source != "getcomics" || saved == null || !saved.canStartRemoteReader || safeSourceUrl(saved.articleUrl) != article) return null
        val modified = record.sourceModifiedAt?.let { parseInstant(it) ?: return null }
        return saved.assets.firstOrNull { asset ->
            val checked = parseInstant(asset.checkedAt)
            val age = checked?.let { Duration.between(it, now) }
            isVerifiedReady(asset) &&
                asset.sourceRecordId in setOf(record.externalId, "article:$article") &&
                safeSourceUrl(asset.verifiedUrl) != null &&
                strongTag(asset.verifiedETag.orEmpty()) &&
                (asset.verifiedLength ?: 0L) in 22L..(64L * 1024 * 1024 * 1024) &&
                (asset.verifiedPageCount ?: 0) in 1..20_000 &&
                checked != null && age != null && !age.isNegative && age < maximumAge &&
                (modified == null || !modified.isAfter(checked))
        }
    }

    /** A failing open cannot leave an unusable link on the fast path until TTL expiry. */
    fun invalidate(saved: SourceAssetInspection, comic: DemoComic): SourceAssetInspection {
        var changed = false
        val assets = saved.assets.map { asset ->
            if (isVerifiedReady(asset) && asset.verifiedUrl == comic.sourceUrl &&
                asset.verifiedETag == comic.verifiedETag && asset.verifiedLength == comic.verifiedLength) {
                changed = true
                asset.copy(status = SourceAssetStatus.EXPIRED, reason = "已保存的文件地址暂不可用，下次打开会重新取得来源。",
                    verifiedUrl = null, verifiedETag = null, verifiedLength = null, verifiedPageCount = null)
            } else asset
        }
        return if (!changed) saved else saved.copy(assets = assets, canStartRemoteReader = assets.any(::isVerifiedReady))
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    private fun strongTag(value: String): Boolean = value.length >= 2 && value.first() == '"' && value.last() == '"' &&
        value.substring(1, value.lastIndex).all { it.code >= 0x21 && it != '"' && it.code != 0x7f }
}
