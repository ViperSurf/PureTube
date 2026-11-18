package com.puretube.app.data.backup

import com.puretube.app.data.remote.extractor.StreamExtractorRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One video parsed from a playlist import file. */
data class ParsedPlaylistVideo(
    val videoId: String,
    val title: String = "",
    val url: String = ""
)

/**
 * Playlist import parsers for JSON / CSV / TXT files. Pure + unit-tested.
 * - TXT: one URL or bare video ID per line (# comments allowed).
 * - CSV: first column URL/ID, optional second column title.
 * - JSON: array of strings/objects, or {"videos":[...]} / {"items":[...]}.
 */
object PlaylistImportParsers {

    fun parseTxt(text: String): List<ParsedPlaylistVideo> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> ParsedPlaylistVideo(videoId = idOf(line), url = line) }
            .filter { it.videoId.isNotBlank() }
            .toList()

    fun parseCsv(text: String): List<ParsedPlaylistVideo> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("video", ignoreCase = true) && it.contains(",") && it.contains("url", ignoreCase = true) }
            .mapNotNull { line ->
                val parts = SubscriptionParsers.splitCsvLine(line)
                val first = parts.firstOrNull()?.trim().orEmpty()
                if (first.isBlank()) return@mapNotNull null
                ParsedPlaylistVideo(
                    videoId = idOf(first),
                    title = parts.getOrNull(1)?.trim().orEmpty(),
                    url = first
                )
            }
            .filter { it.videoId.isNotBlank() }
            .toList()

    fun parseJson(text: String): List<ParsedPlaylistVideo> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = when {
            root is kotlinx.serialization.json.JsonArray -> root
            root is kotlinx.serialization.json.JsonObject ->
                (root["videos"] ?: root["items"] ?: root["entries"]) as? kotlinx.serialization.json.JsonArray
                    ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { el ->
            when (el) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    val v = el.content
                    ParsedPlaylistVideo(videoId = idOf(v), url = v)
                }
                is kotlinx.serialization.json.JsonObject -> {
                    val raw = el["url"]?.jsonPrimitive?.content
                        ?: el["videoId"]?.jsonPrimitive?.content
                        ?: el["id"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                    ParsedPlaylistVideo(
                        videoId = idOf(raw),
                        title = el["title"]?.jsonPrimitive?.content.orEmpty(),
                        url = raw
                    )
                }
                else -> null
            }
        }.filter { it.videoId.isNotBlank() }
    }

    /** Dispatches on content shape, not file extension. */
    fun parseAny(text: String): List<ParsedPlaylistVideo> {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJson(text)
            trimmed.contains(",") && !trimmed.contains("youtube") && !trimmed.contains("youtu.be") -> parseCsv(text)
            else -> parseTxt(text).ifEmpty { parseCsv(text) }
        }
    }

    private fun idOf(raw: String): String =
        StreamExtractorRepository.extractVideoId(raw)
}
