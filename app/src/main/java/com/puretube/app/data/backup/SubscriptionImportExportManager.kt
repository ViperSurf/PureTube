package com.puretube.app.data.backup

import com.puretube.app.data.local.entity.SubscriptionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline import/export for subscriptions.
 * The two parsers are pure functions so they can be unit-tested on the JVM.
 */
object SubscriptionParsers {

    private val json = Json { ignoreUnknownKeys = true }

    /** NewPipe export format: {"app_version":..., "subscriptions":[{"service_id":0,"url":"...","name":"..."}]} */
    fun parseNewPipeJson(text: String, nowEpochMs: Long = 0L): List<SubscriptionEntity> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val subs = root["subscriptions"]?.jsonArray ?: return emptyList()
        return subs.mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.content.orEmpty()
            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val channelId = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SubscriptionEntity(
                channelId = channelId,
                channelName = name.ifBlank { channelId },
                avatarUrl = "",
                subscriberCountText = null,
                addedAtEpochMs = nowEpochMs
            )
        }
    }

    /**
     * YouTube Takeout CSV format with header:
     * Channel Id,Channel Url,Channel Title
     */
    fun parseTakeoutCsv(text: String, nowEpochMs: Long = 0L): List<SubscriptionEntity> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val startIndex = if (lines.first().startsWith("Channel Id", ignoreCase = true)) 1 else 0
        return lines.drop(startIndex).mapNotNull { line ->
            val parts = splitCsvLine(line)
            if (parts.size < 3) return@mapNotNull null
            val channelId = parts[0].trim()
            val title = parts[2].trim()
            if (channelId.isBlank()) return@mapNotNull null
            SubscriptionEntity(
                channelId = channelId,
                channelName = title.ifBlank { channelId },
                avatarUrl = "",
                subscriberCountText = null,
                addedAtEpochMs = nowEpochMs
            )
        }
    }

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
            "{\"service_id\":0,\"url\":\"https://www.youtube.com/channel/${s.channelId}\",\"name\":\"$name\"}"
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

    fun parseAny(text: String): List<SubscriptionEntity> {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{")) parseNewPipeJson(text) else parseTakeoutCsv(text)
    }
}
