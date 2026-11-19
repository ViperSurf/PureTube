package com.puretube.app.domain

import com.puretube.app.data.local.datastore.BlurScope
import com.puretube.app.haramblur.engine.NormalizedRect

/**
 * v1.5 pure logic: tiled-detection coordinate mapping, PiP action icon
 * selection, floating-window size clamping, and the player gesture policy.
 * All JVM-testable, no Android types.
 */

// ---------------------------------------------------------------------------
// Tiled detection (small faces)
//
// When a full-frame BlazeFace pass finds nothing, the frame is retried in
// 2x2 OVERLAPPING quadrants. Each quadrant is 60% of the frame, so a face
// straddling the centre lines still lands whole inside at least one tile,
// and inside a tile that face occupies ~1.7x more of the model's 128x128
// input than it did in the full frame — small/distant faces get detected.
// Boxes come back in tile-local [0,1] coordinates and are mapped to
// full-frame coordinates here; the caller merges + NMS-es the union.
// ---------------------------------------------------------------------------

object TileMath {
    const val TILE_FRACTION = 0.6f

    /** The four quadrant tiles as normalized rects, row-major. */
    fun tiles(): List<NormalizedRect> {
        val o = 1f - TILE_FRACTION // 0.4 origin for the far tiles
        return listOf(
            NormalizedRect(0f, 0f, TILE_FRACTION, TILE_FRACTION),
            NormalizedRect(o, 0f, 1f, TILE_FRACTION),
            NormalizedRect(0f, o, TILE_FRACTION, 1f),
            NormalizedRect(o, o, 1f, 1f)
        )
    }

    /** Maps a box detected inside [tile] (tile-local coords) to full-frame coords. */
    fun mapToFullFrame(tile: NormalizedRect, local: NormalizedRect): NormalizedRect =
        NormalizedRect(
            tile.left + local.left * tile.width,
            tile.top + local.top * tile.height,
            tile.left + local.right * tile.width,
            tile.top + local.bottom * tile.height
        )
}

/** Which blur-region expansion a [BlurScope] applies to a face-only box. */
object BlurScopeMath {
    fun expand(faceBox: NormalizedRect, scope: BlurScope): NormalizedRect =
        when (scope) {
            BlurScope.FACE_ONLY -> faceBox
            BlurScope.FACE_AND_BODY ->
                com.puretube.app.haramblur.engine.BlurBoxMath.expandPersonRegion(faceBox)
        }
}

// ---------------------------------------------------------------------------
// PiP remote-action icons: muted and unmuted must be unmistakable at the
// tiny size Android renders them, so they use two custom drawables with
// different silhouettes (speaker + X + slash vs speaker + sound waves).
// This picks WHICH one; MainActivity maps the kind to the drawable.
// ---------------------------------------------------------------------------

object PipActionIcons {
    enum class MuteIcon { MUTED, UNMUTED }

    fun muteIconFor(isMuted: Boolean): MuteIcon =
        if (isMuted) MuteIcon.MUTED else MuteIcon.UNMUTED
}

// ---------------------------------------------------------------------------
// Floating mini window sizing: the window is JUST the video (16:9), moved
// by dragging and resized dynamically (pinch / corner grip). Width is
// clamped to a sane range relative to the screen.
// ---------------------------------------------------------------------------

object FloatingWindowMath {
    const val MIN_WIDTH_DP = 120f
    const val MAX_WIDTH_DP = 440f

    /** Clamp a proposed window width (dp) to [MIN_WIDTH_DP] and at most 92% of the screen. */
    fun clampWidthDp(widthDp: Float, screenWidthDp: Float): Float {
        val maxAllowed = minOf(MAX_WIDTH_DP, screenWidthDp * 0.92f)
        val minAllowed = minOf(MIN_WIDTH_DP, maxAllowed)
        return widthDp.coerceIn(minAllowed, maxAllowed)
    }

    /** New width after a pinch zoom factor or a corner-grip drag delta (dp). */
    fun resizedWidthDp(currentDp: Float, zoom: Float = 1f, dragDeltaDp: Float = 0f, screenWidthDp: Float): Float =
        clampWidthDp(currentDp * zoom + dragDeltaDp, screenWidthDp)
}

// ---------------------------------------------------------------------------
// Player gesture policy (v1.5): brightness/volume swipe controls are
// OPT-IN (default OFF). With them OFF, a vertical drag anywhere on the
// video is the minimize drag. With them ON, left third = brightness,
// right third = volume, and only the CENTRE third starts a minimize drag
// (plus the drag handle, which always works).
// ---------------------------------------------------------------------------

object PlayerGesturePolicy {
    fun brightnessVolumeEnabled(swipeControlsPref: Boolean): Boolean = swipeControlsPref

    /**
     * True when a vertical drag starting at horizontal fraction [xFraction]
     * (0 = left edge, 1 = right edge) of the video should be treated as the
     * minimize drag rather than brightness/volume.
     */
    fun isMinimizeDragStart(xFraction: Float, swipeControlsPref: Boolean): Boolean {
        if (!swipeControlsPref) return true
        return xFraction in (1f / 3f)..(2f / 3f)
    }

    /** -1 = brightness zone, +1 = volume zone, 0 = neither (only when the pref is ON). */
    fun swipeZone(xFraction: Float, swipeControlsPref: Boolean): Int {
        if (!swipeControlsPref) return 0
        return when {
            xFraction < 1f / 3f -> -1
            xFraction > 2f / 3f -> 1
            else -> 0
        }
    }
}
