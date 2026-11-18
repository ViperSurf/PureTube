package com.puretube.app.data.backup

import com.puretube.app.data.local.entity.SubscriptionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result of a tolerant import: what was recognised, and what was skipped. */
data class ImportReport(
    val subscriptions: List<SubscriptionEntity>,
    val skipped: Int,
    val format: String
) {
    val imported: Int get() = subscriptions.size
}

/**
 * Offline import/export for subscriptions.
 *
 * The v1.2 parser is deliberately structure-tolerant (Bara: "the thing
 * shouldn't be exact"): it sniffs the format by content and accepts NewPipe
 * JSON, Google Takeout (subscriptions.csv, the Takeout JSON shape, and OPML),
 * SkyTube JSON exports, Morphe-style JSON exports, generic CSV with synonym
 * headers in any order/casing, OPML/XML outline files, and plain-text URL
 * lists. Unrecognised rows are counted as skipped instead of failing the
 * whole file. All parsers are pure functions, unit-tested on the JVM.
 */
object SubscriptionParsers {

    private val json = Json { ignoreUnknownKeys = true }

    private val CHANNEL_ID_REGEX = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val CHANNEL_ID_IN_TEXT = Regex("UC[A-Za-z0-9_-]{22}")
    private val HANDLE_REGEX = Regex("^@[A-Za-z0-9._-]{3,}$")

    private val ID_KEYS = setOf(
        "channelid", "channel_id", "channel", "id", "ucid", "channelucid"
    )
    private val URL_KEYS = setOf(
        "url", "link", "href", "channelurl", "channel_url", "xmlurl", "htmlurl"
    )
    private val NAME_KEYS = setOf(
        "name", "title", "channelname", "channel_name", "channeltitle",
        "channel_title", "text", "displayname"
    )

    // ------------------------------------------------------------------
    // Tolerant entry point
    // ------------------------------------------------------------------

    fun parseAnyWithReport(text: String, nowEpochMs: Long = 0L): ImportReport {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportReport(emptyList(), 0, "empty")

        // OPML / XML (Takeout OPML, generic RSS-reader exports)
        if (trimmed.startsWith("<") && (trimmed.contains("<opml", ignoreCase = true) ||
                trimmed.contains("<outline", ignoreCase = true))
        ) {
            val subs = parseOpml(trimmed, nowEpochMs)
            return ImportReport(subs, 0, "OPML")
        }

        // JSON in any of the known (or unknown-but-similar) shapes
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            if (element != null) {
                val found = mutableListOf<SubscriptionEntity>()
                var skipped = 0
                collectFromJson(element, found, nowEpochMs) { skipped++ }
                val deduped = found.distinctBy { it.channelId }
                return ImportReport(deduped, skipped, "JSON")
            }
        }

        // XML without opml/outline markers: still scan for channel ids/urls.
        if (trimmed.startsWith("<")) {
            val subs = parseOpml(trimmed, nowEpochMs)
            if (subs.isNotEmpty()) return ImportReport(subs, 0, "XML")
        }

        // CSV (header synonyms, any order) or plain-text lines of URLs/IDs
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
        val first = lines.firstOrNull().orEmpty().lowercase()
        val looksCsv = first.contains(",") &&
            (first.contains("channel") || first.contains("url") || first.contains("title") ||
                first.contains("id") || first.contains("name"))
        return if (looksCsv) {
            parseGenericCsv(lines, nowEpochMs)
        } else {
            // Plain text: one URL / @handle / channel ID per line.
            val subs = mutableListOf<SubscriptionEntity>()
            var skipped = 0
            for (line in lines) {
                val entity = entityFromLooseReference(line, "", nowEpochMs)
                if (entity != null) subs.add(entity) else skipped++
            }
            ImportReport(subs.distinctBy { it.channelId }, skipped, "TXT")
        }
    }

    // ------------------------------------------------------------------
    // JSON: recursive, key-synonym tolerant collection
    // ------------------------------------------------------------------

    private fun collectFromJson(
        element: JsonElement,
        out: MutableList<SubscriptionEntity>,
        nowEpochMs: Long,
        onSkip: () -> Unit
    ) {
        when (element) {
            is JsonArray -> element.forEach { collectFromJson(it, out, nowEpochMs, onSkip) }
            is JsonObject -> {
                val entity = entityFromJsonObject(element, nowEpochMs)
                if (entity != null) {
                    out.add(entity)
                } else {
                    // Not itself a subscription object: look one level down
                    // in every child (covers {"subscriptions":[...]},
                    // {"items":[...]}, Takeout's {"snippet":{...}} nesting,
                    // SkyTube/Morphe wrapper objects, etc.)
                    var childProduced = false
                    for ((_, child) in element) {
                        val before = out.size
                        collectFromJson(child, out, nowEpochMs, onSkip)
                        if (out.size > before) childProduced = true
                    }
                    if (!childProduced && looksLikeSubscriptionObject(element)) onSkip()
                }
            }
            is JsonPrimitive -> {
                // Bare strings in an array: channel IDs or URLs (SkyTube
                // exports can be close to this shape).
                val s = element.contentOrNullSafe() ?: return
                entityFromLooseReference(s, "", nowEpochMs)?.let { out.add(it) }
            }
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun looksLikeSubscriptionObject(obj: JsonObject): Boolean {
        val keys = obj.keys.map { it.lowercase().replace("-", "_") }
        return keys.any { it in ID_KEYS || it in URL_KEYS } &&
            keys.none { it == "subscriptions" || it == "items" }
    }

    private fun entityFromJsonObject(obj: JsonObject, nowEpochMs: Long): SubscriptionEntity? {
        // Flatten one level of nesting (e.g. Takeout JSON: snippet.resourceId.channelId)
        val flat = mutableMapOf<String, String>()
        fun absorb(o: JsonObject) {
            for ((k, v) in o) {
                val key = k.lowercase().replace("-", "_")
                when (v) {
                    is JsonPrimitive -> flat.putIfAbsent(key, v.contentOrNullSafe().orEmpty())
                    is JsonObject -> absorb(v)
                    else -> Unit
                }
            }
        }
        absorb(obj)

        val name = NAME_KEYS.firstNotNullOfOrNull { flat[it]?.takeIf { n -> n.isNotBlank() } }.orEmpty()

        // 1) Direct channel ID under any synonym key.
        for (key in ID_KEYS) {
            val value = flat[key]?.trim().orEmpty()
            if (CHANNEL_ID_REGEX.matches(value)) {
                return SubscriptionEntity(value, name.ifBlank { value }, "", null, nowEpochMs)
            }
        }
        // 2) URL under any synonym key (channel URL, @handle URL, xmlUrl...).
        for (key in URL_KEYS) {
            val value = flat[key]?.trim().orEmpty()
            if (value.isNotBlank()) {
                entityFromLooseReference(value, name, nowEpochMs)?.let { return it }
            }
        }
        // 3) Any value in the object that *is* a channel ID or channel URL.
        for ((_, value) in flat) {
            val v = value.trim()
            if (CHANNEL_ID_REGEX.matches(v)) {
                return SubscriptionEntity(v, name.ifBlank { v }, "", null, nowEpochMs)
            }
            if (v.contains("youtube.com/channel/") || v.contains("youtube.com/@")) {
                entityFromLooseReference(v, name, nowEpochMs)?.let { return it }
            }
        }
        return null
    }

    /**
     * Turns a loose reference (channel URL, @handle, bare UC id, OPML xmlUrl
     * with a channel_id parameter) into a subscription. Handles are kept as
     * the channelId (they resolve through the same URL path everywhere the
     * app builds channel URLs) when no UC id is present.
     */
    fun entityFromLooseReference(ref: String, name: String, nowEpochMs: Long): SubscriptionEntity? {
        val value = ref.trim().trim('"')
        if (value.isBlank()) return null
        CHANNEL_ID_IN_TEXT.find(value)?.let { match ->
            val id = match.value
            // A full channel URL or a bare ID both land here.
            if (value == id || value.contains("channel/$id") || value.contains("channel_id=$id")) {
                return SubscriptionEntity(id, name.ifBlank { id }, "", null, nowEpochMs)
            }
        }
        if (CHANNEL_ID_REGEX.matches(value)) {
            return SubscriptionEntity(value, name.ifBlank { value }, "", null, nowEpochMs)
        }
        if (value.contains("youtube.com/@") || HANDLE_REGEX.matches(value)) {
            val handle = if (value.startsWith("@")) value
            else "@" + value.substringAfter("youtube.com/@").takeWhile { it != '/' && it != '?' && it != '&' }
            if (handle.length > 1) {
                return SubscriptionEntity(handle, name.ifBlank { handle }, "", null, nowEpochMs)
            }
        }
        if (value.contains("youtube.com/channel/")) {
            val id = value.substringAfter("youtube.com/channel/")
                .takeWhile { it != '/' && it != '?' && it != '&' }
            if (id.isNotBlank()) return SubscriptionEntity(id, name.ifBlank { id }, "", null, nowEpochMs)
        }
        return null
    }

    // ------------------------------------------------------------------
    // OPML
    // ------------------------------------------------------------------

    private val OUTLINE_REGEX = Regex("<outline[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR_REGEX = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"|(\\w+)\\s*=\\s*'([^']*)'")

    fun parseOpml(text: String, nowEpochMs: Long = 0L): List<SubscriptionEntity> {
        val out = mutableListOf<SubscriptionEntity>()
        for (tagMatch in OUTLINE_REGEX.findAll(text)) {
            val attrs = mutableMapOf<String, String>()
            for (m in ATTR_REGEX.findAll(tagMatch.value)) {
                val key = (m.groupValues[1].ifBlank { m.groupValues[3] }).lowercase()
                val value = m.groupValues[2].ifBlank { m.groupValues[4] }
                if (key.isNotBlank()) attrs[key] = value
            }
            val name = attrs["title"] ?: attrs["text"] ?: ""
            val ref = attrs["xmlurl"] ?: attrs["htmlurl"] ?: attrs["url"] ?: continue
            entityFromLooseReference(ref, name, nowEpochMs)?.let { out.add(it) }
        }
        return out.distinctBy { it.channelId }
    }

    // ------------------------------------------------------------------
    // CSV: synonym headers in any order/casing; positional fallback for
    // the classic Takeout layout (Id, Url, Title).
    // ------------------------------------------------------------------

    private fun parseGenericCsv(lines: List<String>, nowEpochMs: Long): ImportReport {
        val header = splitCsvLine(lines.first()).map { it.trim().lowercase().replace(" ", "_") }
        fun column(vararg synonyms: String): Int =
            header.indexOfFirst { h -> synonyms.any { s -> h == s || h.contains(s) } }
        val idCol = column("channel_id", "channelid", "id")
        val urlCol = column("channel_url", "channelurl", "url", "link")
        val nameCol = column("channel_title", "channeltitle", "title", "name")

        val subs = mutableListOf<SubscriptionEntity>()
        var skipped = 0
        for (line in lines.drop(1)) {
            val parts = splitCsvLine(line)
            fun cell(idx: Int): String = if (idx in parts.indices) parts[idx].trim() else ""
            val name = cell(nameCol)
            val candidate = when {
                idCol >= 0 && CHANNEL_ID_REGEX.matches(cell(idCol)) -> cell(idCol)
                urlCol >= 0 && cell(urlCol).isNotBlank() -> cell(urlCol)
                idCol >= 0 && cell(idCol).isNotBlank() -> cell(idCol)
                else -> parts.firstOrNull { CHANNEL_ID_IN_TEXT.containsMatchIn(it) }
                    ?: parts.firstOrNull { it.contains("youtube.com/") }
                    ?: ""
            }
            val entity = if (candidate.isBlank()) null
            else entityFromLooseReference(candidate, name, nowEpochMs)
            if (entity != null) subs.add(entity) else skipped++
        }
        return ImportReport(subs.distinctBy { it.channelId }, skipped, "CSV")
    }

    // ------------------------------------------------------------------
    // Legacy exact parsers (kept for compatibility; delegate to tolerant)
    // ------------------------------------------------------------------

    /** NewPipe export format: {"app_version":..., "subscriptions":[{"service_id":0,"url":"...","name":"..."}]} */
    fun parseNewPipeJson(text: String, nowEpochMs: Long = 0L): List<SubscriptionEntity> =
        parseAnyWithReport(text, nowEpochMs).subscriptions

    /** YouTube Takeout CSV format with header: Channel Id,Channel Url,Channel Title */
    fun parseTakeoutCsv(text: String, nowEpochMs: Long = 0L): List<SubscriptionEntity> =
        parseAnyWithReport(text, nowEpochMs).subscriptions

    /** Minimal CSV splitter handling quoted fields with embedded commas. */
    fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    fun exportNewPipeJson(subscriptions: List<SubscriptionEntity>): String {
        val items = subscriptions.joinToString(",") { s ->
            val name = s.channelName.replace("\\", "\\\\").replace("\"", "\\\"")
            val url = if (s.channelId.startsWith("@")) {
                "https://www.youtube.com/${s.channelId}"
            } else {
                "https://www.youtube.com/channel/${s.channelId}"
            }
            "{\"service_id\":0,\"url\":\"$url\",\"name\":\"$name\"}"
        }
        return "{\"app_version\":\"1.0.0\",\"app_version_int\":10000,\"subscriptions\":[$items]}"
    }
}

class SubscriptionImportExportManager {
    fun parseNewPipeJson(text: String): List<SubscriptionEntity> =
        SubscriptionParsers.parseNewPipeJson(text, System.currentTimeMillis())

    fun parseTakeoutCsv(text: String): List<SubscriptionEntity> =
        SubscriptionParsers.parseTakeoutCsv(text, System.currentTimeMillis())

    fun exportNewPipeJson(subscriptions: List<SubscriptionEntity>): String =
        SubscriptionParsers.exportNewPipeJson(subscriptions)

    fun parseAny(text: String): List<SubscriptionEntity> =
        SubscriptionParsers.parseAnyWithReport(text, System.currentTimeMillis()).subscriptions

    fun parseAnyWithReport(text: String): ImportReport =
        SubscriptionParsers.parseAnyWithReport(text, System.currentTimeMillis())
}
