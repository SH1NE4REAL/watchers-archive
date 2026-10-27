package org.watchersarchive.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Independent persistent metadata; clearing page/cover caches cannot remove this file. */
class CatalogSnapshots(private val file: File) {
    private var root: JSONObject
    init {
        file.parentFile?.mkdirs()
        root = if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    }

    @Synchronized fun saveInspection(id: String, inspection: SourceAssetInspection) = update {
        val inspections = optJSONObject("inspections") ?: JSONObject().also { put("inspections", it) }
        inspections.put(id, JSONObject().put("url", inspection.articleUrl).put("assets", JSONArray().also { array ->
            inspection.assets.forEach { a -> array.put(JSONObject()
                .put("id", a.id).put("record", a.sourceRecordId).put("host", a.host)
                .put("url", a.candidateUrl).put("file", a.fileName).put("format", a.declaredFormat)
                .put("size", a.declaredSize).put("status", a.status.name).put("reason", a.reason)
                .put("checked", a.checkedAt).put("verifiedUrl", a.verifiedUrl)
                .put("etag", a.verifiedETag).put("length", a.verifiedLength).put("pages", a.verifiedPageCount)) }
        }))
    }

    @Synchronized fun inspection(id: String): SourceAssetInspection? {
        val json = root.optJSONObject("inspections")?.optJSONObject(id) ?: return null
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val parsed = (0 until assets.length()).map { i -> assets.getJSONObject(i).let { a ->
            SourceAsset(a.getString("id"), a.getString("record"), a.nullable("host"), a.getString("url"),
                a.nullable("file"), a.nullable("format"), a.nullable("size"),
                runCatching { SourceAssetStatus.valueOf(a.getString("status")) }.getOrDefault(SourceAssetStatus.METADATA_ONLY),
                a.optString("reason"), a.optString("checked"), a.nullable("verifiedUrl"), a.nullable("etag"),
                if (a.has("length") && !a.isNull("length")) a.getLong("length") else null,
                if (a.has("pages") && !a.isNull("pages")) a.getInt("pages") else null)
        } }
        return SourceAssetInspection(json.getString("url"), parsed, parsed.any(::isVerifiedReady))
    }

    @Synchronized fun saveGuide(guide: OfficialGuide) = update {
        val guides = optJSONObject("guides") ?: JSONObject().also { put("guides", it) }
        guides.put(guide.canonicalUrl, JSONObject().put("id", guide.externalId).put("title", guide.title)
            .put("description", guide.description).put("url", guide.canonicalUrl).put("fetched", guide.sourceFetchedAt)
            .put("sections", JSONArray().also { array -> guide.sections.forEach { s ->
                array.put(JSONObject().put("order", s.order).put("title", s.title).put("issues", JSONArray().also { issues ->
                    s.issues.forEach { issue -> issues.put(JSONObject().put("order", issue.order).put("id", issue.issueExternalId)
                        .put("url", issue.sourceUrl).put("evidence", issue.sourceEvidence)) }
                }))
            } }))
    }

    @Synchronized fun guides(): List<OfficialGuide> {
        val guides = root.optJSONObject("guides") ?: return emptyList()
        return guides.keys().asSequence().map { key -> guides.getJSONObject(key).let { g ->
            val sections = g.getJSONArray("sections")
            OfficialGuide(g.nullable("id"), g.getString("title"), g.nullable("description"), g.getString("url"),
                g.getString("fetched"), (0 until sections.length()).map { i -> sections.getJSONObject(i).let { s ->
                    val issues = s.getJSONArray("issues")
                    OfficialGuideSection(s.getInt("order"), s.getString("title"), (0 until issues.length()).map { j ->
                        issues.getJSONObject(j).let { a -> OfficialGuideIssue(a.getInt("order"), a.nullable("id"), a.getString("url"), a.getString("evidence")) }
                    })
                } })
        } }.toList()
    }

    @Synchronized fun saveCharacter(character: OfficialCharacterProfile) = update {
        val chars = optJSONObject("characters") ?: JSONObject().also { put("characters", it) }
        chars.put(character.externalSlug, JSONObject().put("slug", character.externalSlug).put("name", character.name)
            .put("url", character.sourceUrl).put("overview", character.overview).put("aliases", JSONArray(character.aliases))
            .put("links", JSONArray(character.relatedLinks)).put("fetched", character.fetchedAt))
    }

    @Synchronized fun characters(): List<OfficialCharacterProfile> {
        val chars = root.optJSONObject("characters") ?: return emptyList()
        return chars.keys().asSequence().map { key -> chars.getJSONObject(key).let { c ->
            OfficialCharacterProfile(c.getString("slug"), c.getString("name"), c.getString("url"), c.nullable("overview"),
                c.getJSONArray("aliases").strings(), c.getJSONArray("links").strings(), c.getString("fetched"))
        } }.toList()
    }

    private fun update(change: JSONObject.() -> Unit) {
        val next = JSONObject(root.toString()).apply(change)
        val pending = File(file.parentFile, file.name + ".pending")
        pending.outputStream().use { output -> output.write(next.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        root = next
    }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}

fun isVerifiedReady(asset: SourceAsset): Boolean = asset.status == SourceAssetStatus.READY &&
    !asset.verifiedUrl.isNullOrBlank() && !asset.verifiedETag.isNullOrBlank() &&
    asset.verifiedLength != null && asset.verifiedLength > 0 &&
    asset.verifiedPageCount != null && asset.verifiedPageCount > 0
