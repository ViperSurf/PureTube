package com.puretube.app.domain

import com.puretube.app.data.local.datastore.BlurTargetGender
import com.puretube.app.data.remote.extractor.ChannelHit
import com.puretube.app.data.remote.extractor.FeedItem
import com.puretube.app.data.remote.extractor.PlaylistHit
import com.puretube.app.haramblur.engine.NormalizedRect
import kotlin.math.exp

/**
 * v1.3 pure logic: null-safe extractor item mapping, BlazeFace anchor/decode
 * math, the no-gender-model blur rule, the playback-session reuse decision,
 * and audio-only surface state. All JVM-testable, no Android types.
 */

// ---------------------------------------------------------------------------
// Error detail with stack frames (search diagnostics)
// ---------------------------------------------------------------------------

object ErrorDetail {
    /** Class chain + the first [frames] stack frames of the top exception. */
    fun withStack(t: Throwable?, frames: Int = 5): String {
        if (t == null) return "unknown error"
        val sb = StringBuilder(ErrorText.describe(t, 400))
        val trace = t.stackTrace.take(frames)
        if (trace.isNotEmpty()) {
            sb.append("\nStack (top ${trace.size}):")
            trace.forEach { frame -> sb.append("\n  at $frame") }
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------
// Null-safe extractor mapping (the v1.2 search NPE fix)
//
// Every Java platform getter on a NewPipe InfoItem is treated as nullable:
// the repository reads each getter into one of these raw holders first
// (per-field runCatching), and ONLY these mappers turn raw -> UI models.
// A raw item whose identity fields (url / name) are missing maps to null
// and is skipped by the caller via mapNotNull — one bad item can never
// fail a whole search again.
// ---------------------------------------------------------------------------

data class RawThumb(val url: String?, val height: Int)

data class RawStreamItem(
    val url: String? = null,
    val name: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val thumbnails: List<RawThumb> = emptyList(),
    val uploaderAvatars: List<RawThumb> = emptyList(),
    val durationSeconds: Long = 0L,
    val isShortForm: Boolean = false,
    val uploadEpochMs: Long = 0L
)

data class RawChannelItem(
    val url: String? = null,
    val name: String? = null,
    val thumbnails: List<RawThumb> = emptyList(),
    val subscriberCount: Long = -1L,
    val description: String? = null
)

data class RawPlaylistItem(
    val url: String? = null,
    val name: String? = null,
    val uploaderName: String? = null,
    val thumbnails: List<RawThumb> = emptyList(),
    val streamCount: Long = 0L
)

data class RawCommentItem(
    val commentId: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val text: String? = null,
    val likeCount: Int = -1,
    val uploaderAvatars: List<RawThumb> = emptyList(),
    val replyCount: Int = -1,
    val repliesPageUrl: String? = null,
    val repliesToken: String? = null
)

object ExtractorItemMapper {

    private fun bestThumbUrl(thumbs: List<RawThumb>): String =
        thumbs.filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.height }?.url.orEmpty()

    /** Null when the item has no usable url or title (skip it, never throw). */
    fun toFeedItem(raw: RawStreamItem, extractVideoId: (String) -> String): FeedItem? {
        val url = raw.url?.takeIf { it.isNotBlank() } ?: return null
        val title = raw.name?.takeIf { it.isNotBlank() } ?: return null
        return FeedItem(
            videoId = extractVideoId(url),
            title = title,
            channelName = raw.uploaderName.orEmpty(),
            channelId = raw.uploaderUrl?.substringAfterLast("/").orEmpty(),
            thumbnailUrl = bestThumbUrl(raw.thumbnails),
            durationSeconds = raw.durationSeconds.coerceAtLeast(0L),
            url = url,
            isShort = url.contains("/shorts/") || raw.isShortForm,
            channelAvatarUrl = bestThumbUrl(raw.uploaderAvatars),
            uploadEpochMs = raw.uploadEpochMs.coerceAtLeast(0L)
        )
    }

    fun toChannelHit(raw: RawChannelItem): ChannelHit? {
        val url = raw.url?.takeIf { it.isNotBlank() } ?: return null
        val name = raw.name?.takeIf { it.isNotBlank() } ?: return null
        return ChannelHit(
            channelId = url.substringAfterLast("/"),
            name = name,
            avatarUrl = bestThumbUrl(raw.thumbnails),
            subscriberCount = raw.subscriberCount,
            description = raw.description.orEmpty(),
            url = url
        )
    }

    fun toPlaylistHit(raw: RawPlaylistItem): PlaylistHit? {
        val url = raw.url?.takeIf { it.isNotBlank() } ?: return null
        val title = raw.name?.takeIf { it.isNotBlank() } ?: return null
        return PlaylistHit(
            url = url,
            title = title,
            channelName = raw.uploaderName.orEmpty(),
            thumbnailUrl = bestThumbUrl(raw.thumbnails),
            streamCount = raw.streamCount.coerceAtLeast(0L)
        )
    }

    fun toCommentItem(raw: RawCommentItem): CommentItem =
        CommentItem(
            id = raw.commentId.orEmpty(),
            authorName = raw.uploaderName.orEmpty(),
            authorChannelId = raw.uploaderUrl?.substringAfterLast("/").orEmpty(),
            text = raw.text.orEmpty(),
            likeCount = raw.likeCount,
            avatarUrl = bestThumbUrl(raw.uploaderAvatars),
            replyCount = raw.replyCount,
            repliesPageUrl = raw.repliesPageUrl.orEmpty(),
            repliesToken = raw.repliesToken.orEmpty()
        )
}

// ---------------------------------------------------------------------------
// BlazeFace short-range anchors + decode (matches the shipped .tflite)
//
// Model spec (verified from the file): input [1,128,128,3] float,
// regressors [1,896,16], classifiers [1,896,1].
// Anchor scheme (MediaPipe short-range): stride 8 -> 16x16 grid x 2 anchors,
// stride 16 -> 8x8 grid x 6 anchors; 512 + 384 = 896. Anchor w/h are fixed
// at 1.0 (fixed_anchor_size), so decode is linear in units of the 128px
// input: centre = raw_xy / 128 * anchor_wh + anchor_centre,
// size = raw_wh / 128 * anchor_wh. Regressor order is [dx, dy, w, h, ...].
// ---------------------------------------------------------------------------

object BlazeFaceAnchors {
    const val INPUT_SIZE = 128
    const val ANCHOR_COUNT = 896

    /** [cx, cy] per anchor, in normalized [0,1] coordinates, model order. */
    val centers: List<Pair<Float, Float>> by lazy { generate() }

    private fun generate(): List<Pair<Float, Float>> {
        // Cell-major order (all anchors of a cell together), matching the
        // tensor layout: stride-8 layer first (512), then stride-16 (384).
        val strides = listOf(8, 16)
        val perCell = listOf(2, 6)
        val out = ArrayList<Pair<Float, Float>>(ANCHOR_COUNT)
        for (layer in strides.indices) {
            val grid = INPUT_SIZE / strides[layer]
            for (row in 0 until grid) {
                for (col in 0 until grid) {
                    repeat(perCell[layer]) {
                        out.add((col + 0.5f) / grid to (row + 0.5f) / grid)
                    }
                }
            }
        }
        check(out.size == ANCHOR_COUNT) { "anchor count ${out.size}" }
        return out
    }

    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /**
     * Decodes flat regressor/classifier outputs into scored boxes, sorted by
     * score descending, NMS-applied. [regressors] is 896x16 row-major,
     * [logits] is 896. Pure — the engine and the host tests share this math.
     */
    fun decode(
        regressors: FloatArray,
        logits: FloatArray,
        minScore: Float,
        nmsIouThreshold: Float = 0.35f
    ): List<NormalizedRect> {
        require(regressors.size >= ANCHOR_COUNT * 16 && logits.size >= ANCHOR_COUNT) {
            "tensor sizes: regressors=${regressors.size} logits=${logits.size}"
        }
        data class Scored(val rect: NormalizedRect, val score: Float)
        val scored = ArrayList<Scored>()
        for (i in 0 until ANCHOR_COUNT) {
            val score = sigmoid(logits[i])
            if (score < minScore) continue
            val base = i * 16
            val (ax, ay) = centers[i]
            val cx = regressors[base] / INPUT_SIZE + ax
            val cy = regressors[base + 1] / INPUT_SIZE + ay
            val w = (regressors[base + 2] / INPUT_SIZE).coerceIn(0.02f, 1.5f)
            val h = (regressors[base + 3] / INPUT_SIZE).coerceIn(0.02f, 1.5f)
            scored.add(
                Scored(
                    NormalizedRect(
                        (cx - w / 2f).coerceIn(0f, 1f),
                        (cy - h / 2f).coerceIn(0f, 1f),
                        (cx + w / 2f).coerceIn(0f, 1f),
                        (cy + h / 2f).coerceIn(0f, 1f)
                    ),
                    score
                )
            )
        }
        scored.sortByDescending { it.score }
        val kept = ArrayList<NormalizedRect>()
        for (candidate in scored) {
            val overlaps = kept.any {
                com.puretube.app.haramblur.engine.BlurBoxMath.computeIoU(it, candidate.rect) > nmsIouThreshold
            }
            if (!overlaps) kept.add(candidate.rect)
        }
        return kept
    }
}

// ---------------------------------------------------------------------------
// HaramBlur policy: with no gender model on device, EVERY detected face is
// blurred regardless of the Who-to-Blur setting (v1.3). The gender path only
// applies when a gender model actually loaded.
// ---------------------------------------------------------------------------

object HaramBlurPolicy {
    fun effectiveTarget(requested: BlurTargetGender, hasGenderModel: Boolean): BlurTargetGender =
        if (hasGenderModel) requested else BlurTargetGender.BOTH_ALL_PEOPLE

    fun modeLabel(requested: BlurTargetGender, faceModelLoaded: Boolean, hasGenderModel: Boolean): String =
        when {
            !faceModelLoaded -> "Face model not loaded"
            hasGenderModel -> "Gender model"
            else -> "All-faces fallback (no gender model)"
        }
}

// ---------------------------------------------------------------------------
// Playback session cache decision (reopen freeze fix)
// ---------------------------------------------------------------------------

object PlaybackSessionLogic {
    /** True when the player already holds [requestedVideoId] — attach, don't re-extract. */
    fun shouldReuseSession(requestedVideoId: String, loadedVideoId: String?): Boolean =
        requestedVideoId.isNotBlank() && loadedVideoId != null && requestedVideoId == loadedVideoId
}

// ---------------------------------------------------------------------------
// Audio-only surface state: a single decision used by every surface
// (full player, floating window, dock) so video can never resurrect.
// ---------------------------------------------------------------------------

object AudioOnlySurface {
    enum class Kind { VIDEO, PLACEHOLDER }

    fun kindFor(audioOnly: Boolean): Kind = if (audioOnly) Kind.PLACEHOLDER else Kind.VIDEO

    fun label(audioOnly: Boolean): String = if (audioOnly) "Audio Only" else "Video + Audio"
    fun soundLabel(muted: Boolean): String = if (muted) "Muted" else "Unmuted"
}
