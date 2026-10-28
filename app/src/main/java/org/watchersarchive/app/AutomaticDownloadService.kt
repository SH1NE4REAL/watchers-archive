package org.watchersarchive.app

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

/**
 * One user-requested offline job may try normal public mirrors in sequence.
 * Selecting a new spec is delegated to the store, which keeps source versions
 * and incomplete data isolated. A transfer must validate the local archive before
 * it returns; this class never marks an unverified link READY by itself.
 */
internal class AutomaticDownloadService(
    private val record: CatalogSourceRecord,
    private val inspect: suspend () -> SourceAssetInspection,
    private val resolve: suspend (SourceAsset) -> SourceAsset,
    private val probe: suspend (SourceAsset) -> OfflineDownloadSpec,
    private val prepare: (OfflineDownloadSpec) -> Unit,
    private val transfer: suspend (OfflineDownloadSpec, (OfflineDownloadStatus) -> Unit) -> Unit,
    private val onInspection: (SourceAssetInspection) -> Unit = {},
    private val onProgress: (OfflineDownloadStatus) -> Unit = {},
    private val candidateTimeoutMillis: Long = 45_000,
) {
    init { require(candidateTimeoutMillis > 0) }

    suspend fun download(initialSpec: OfflineDownloadSpec? = null): OfflineDownloadSpec {
        val attemptedTransfers = mutableSetOf<String>()
        val failures = mutableListOf<String>()
        var tried = 0
        suspend fun attempt(spec: OfflineDownloadSpec): Boolean {
            currentCoroutineContext().ensureActive()
            require(spec.comicId == record.externalId && spec.mode != OfflineTransferMode.DISCOVER) { "下载任务与漫画不一致。" }
            if (attemptedTransfers.size >= 12 || !attemptedTransfers.add(transferIdentity(spec))) return false
            tried++
            try {
                prepare(spec)
                transfer(spec, onProgress)
                currentCoroutineContext().ensureActive()
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure is ArchiveException && failure.code == ArchiveError.CANCELLED) throw failure
                if (!isSourceFailure(failure)) throw failure
                failures += safeReason(failure)
                return false
            }
        }
        if (initialSpec != null && initialSpec.mode != OfflineTransferMode.DISCOVER) {
            if (attempt(initialSpec)) return initialSpec
        }

        val original = try { inspect() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw ComicSourceException(SourceAssetStatus.TRANSIENT_ERROR, "暂时无法取得其他下载来源，请检查网络后继续。")
        }
        val assets = original.assets.toMutableList()
        val candidates = original.assets.asSequence().distinctBy { it.candidateUrl }.take(12).toList()
        fun update(asset: SourceAsset) {
            val index = assets.indexOfFirst { it.id == asset.id }
            if (index >= 0) assets[index] = asset
            onInspection(original.copy(assets = assets.toList(), canStartRemoteReader = assets.any(::isVerifiedReady)))
        }
        for ((number, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(OfflineDownloadStatus(record.externalId, record.displayTitle, OfflineDownloadState.QUEUED,
                0, 0, "正在尝试下载来源 ${number + 1}/${candidates.size}…"))
            var resolved = candidate
            try {
                val spec = withTimeoutOrNull(candidateTimeoutMillis) {
                    resolved = resolve(candidate)
                    currentCoroutineContext().ensureActive()
                    update(resolved)
                    if (resolved.status !in setOf(SourceAssetStatus.METADATA_ONLY, SourceAssetStatus.READY, SourceAssetStatus.RANGE_UNSUPPORTED)) {
                        return@withTimeoutOrNull null
                    }
                    probe(resolved)
                }
                currentCoroutineContext().ensureActive()
                if (spec == null) {
                    val reason = if (resolved.status in setOf(SourceAssetStatus.METADATA_ONLY, SourceAssetStatus.READY, SourceAssetStatus.RANGE_UNSUPPORTED))
                        "这个来源响应较慢，已尝试其他来源。" else resolved.reason
                    failures += reason
                    if (reason != resolved.reason) update(resolved.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = reason, checkedAt = Instant.now().toString()))
                    continue
                }
                if (attempt(spec)) {
                    update(resolved.copy(reason = "整本文件已下载并通过本地阅读检查。", checkedAt = Instant.now().toString()))
                    return spec
                }
                update(resolved.copy(status = SourceAssetStatus.TRANSIENT_ERROR, reason = failures.lastOrNull()
                    ?: "这个文件来源未能完成下载。", checkedAt = Instant.now().toString(),
                    verifiedUrl = null, verifiedETag = null, verifiedLength = null, verifiedPageCount = null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure is ArchiveException && failure.code == ArchiveError.CANCELLED) throw failure
                if (!isSourceFailure(failure)) throw failure
                val reason = safeReason(failure)
                failures += reason
                update(resolved.copy(status = (failure as? ComicSourceException)?.status ?: SourceAssetStatus.TRANSIENT_ERROR,
                    reason = reason, checkedAt = Instant.now().toString(), verifiedUrl = null, verifiedETag = null,
                    verifiedLength = null, verifiedPageCount = null))
            }
        }
        val reason = failures.firstOrNull { it.contains("网页") || it.contains("额度") || it.contains("验证") }
            ?: failures.firstOrNull() ?: "文章暂未提供可自动下载的漫画文件。"
        throw ComicSourceException(SourceAssetStatus.NEEDS_USER_ACTION,
            "已尝试 ${maxOf(candidates.size, tried)} 个来源，暂未完成下载。$reason")
    }

    private fun transferIdentity(spec: OfflineDownloadSpec) = listOf(spec.url, spec.length.toString(), spec.mode.name,
        if (spec.verifyRemoteETag) spec.eTag else "full-restart").joinToString("|")

    private fun isSourceFailure(failure: Exception): Boolean = failure is ComicSourceException || failure is ArchiveException

    private fun safeReason(failure: Exception): String = when (failure) {
        is ComicSourceException -> failure.message?.take(240) ?: "这个来源暂时无法下载。"
        is ArchiveException -> when (failure.code) {
            ArchiveError.NETWORK, ArchiveError.HTTP_ERROR -> "这个文件来源连接中断，正在尝试其他来源。"
            ArchiveError.OBJECT_CHANGED -> "这个来源的文件版本已变化，正在尝试其他来源。"
            ArchiveError.UNSUPPORTED_ARCHIVE, ArchiveError.UNSUPPORTED_COMPRESSION, ArchiveError.UNSUPPORTED_IMAGE,
            ArchiveError.CRC_MISMATCH, ArchiveError.CORRUPT_ARCHIVE -> "这个来源的漫画文件无法正常读取，正在尝试其他来源。"
            else -> "这个来源没有返回可确认的完整漫画文件。"
        }
        else -> "下载未完成。"
    }
}
