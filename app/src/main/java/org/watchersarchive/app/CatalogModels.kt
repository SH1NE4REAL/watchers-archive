package org.watchersarchive.app

/** Provenance is kept with every source record; a source article is not a comic edition. */
enum class SourceConfidence { UNKNOWN, LOW, MEDIUM, HIGH, VERIFIED }

enum class SourceAssetStatus {
    METADATA_ONLY,
    NEEDS_USER_ACTION,
    READY,
    RANGE_UNSUPPORTED,
    UNSUPPORTED_FORMAT,
    EXPIRED,
    UNAUTHORIZED,
    TRANSIENT_ERROR,
}

/** Curated source failure text which is safe to present without protocol diagnostics. */
class ComicSourceException(val status: SourceAssetStatus, message: String) : java.io.IOException(message)

data class CatalogSourceRecord(
    val source: String,
    val externalId: String,
    val canonicalUrl: String,
    val title: String,
    val synopsis: String?,
    val coverUrl: String?,
    val sourcePublishedAt: String?,
    val sourceModifiedAt: String?,
    val firstDiscoveredAt: String,
    val categories: List<String>,
    val yearLabel: String?,
    val sizeLabel: String?,
    val formatLabel: String?,
    val confidence: SourceConfidence,
)

data class SourceAsset(
    val id: String,
    val sourceRecordId: String,
    val host: String?,
    val candidateUrl: String,
    val fileName: String?,
    val declaredFormat: String?,
    val declaredSize: String?,
    val status: SourceAssetStatus,
    val reason: String,
    val checkedAt: String,
    val verifiedUrl: String? = null,
    val verifiedETag: String? = null,
    val verifiedLength: Long? = null,
    val verifiedPageCount: Int? = null,
)

data class SourceAssetInspection(
    val articleUrl: String,
    val assets: List<SourceAsset>,
    val canStartRemoteReader: Boolean,
)

data class OfficialGuide(
    val externalId: String?,
    val title: String,
    val description: String?,
    val canonicalUrl: String,
    val sourceFetchedAt: String,
    val sections: List<OfficialGuideSection>,
    val confidence: SourceConfidence = SourceConfidence.HIGH,
)

data class OfficialGuideSection(
    val order: Int,
    val title: String,
    val issues: List<OfficialGuideIssue>,
)

data class OfficialGuideIssue(
    val order: Int,
    val issueExternalId: String?,
    val sourceUrl: String,
    val sourceEvidence: String,
)

data class OfficialCharacterProfile(
    val externalSlug: String,
    val name: String,
    val sourceUrl: String,
    val overview: String?,
    val aliases: List<String>,
    val relatedLinks: List<String>,
    val fetchedAt: String,
)

data class OfficialRelationEvidence(
    val subjectId: String,
    val relationType: String,
    val objectId: String,
    val sourceUrl: String,
    val sourceType: String,
    val fetchedAt: String,
    val confidence: SourceConfidence,
)

/** A sourced collection remains browsable when the official site is unreachable. */
data class RecommendationCollection(
    val id: String,
    val title: String,
    val description: String,
    val sourceUrl: String,
    val searchTerm: String,
    val sourceLabel: String,
    /** An actual source cover; a collection without one resolves a matching saved article cover. */
    val coverUrl: String? = null,
    val coverSourceUrl: String? = null,
)

