package com.puretube.app

import com.puretube.app.data.local.datastore.BlurScope
import com.puretube.app.domain.BlurScopeMath
import com.puretube.app.domain.FloatingWindowMath
import com.puretube.app.domain.PipActionIcons
import com.puretube.app.domain.PlayerGesturePolicy
import com.puretube.app.domain.TileMath
import com.puretube.app.haramblur.engine.BlurBoxMath
import com.puretube.app.haramblur.engine.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V15LogicTest {

    // ---- Person-region expansion (FACE_AND_BODY) ----

    @Test
    fun personRegionWidens1Point6AndExtends2Point5Down() {
        // Face-only box: w=0.2, h=0.2, top=0.3.
        val face = NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f)
        val person = BlurBoxMath.expandPersonRegion(face)
        // Width 1.6x -> 0.32 total, 0.06 added per side.
        assertEquals(0.34f, person.left, 0.001f)
        assertEquals(0.66f, person.right, 0.001f)
        assertEquals(0.32f, person.width, 0.001f)
        // Bottom = top + 2.5 * h = 0.3 + 0.5 = 0.8; top unchanged.
        assertEquals(0.3f, person.top, 0.001f)
        assertEquals(0.8f, person.bottom, 0.001f)
    }

    @Test
    fun personRegionClampsToFrame() {
        val face = NormalizedRect(0.02f, 0.6f, 0.2f, 0.95f) // near left + bottom edges
        val person = BlurBoxMath.expandPersonRegion(face)
        assertEquals(0f, person.left, 0.001f)
        assertEquals(1f, person.bottom, 0.001f)
        assertTrue(person.right <= 1f)
    }

    @Test
    fun blurScopeMathFaceOnlyIsIdentity() {
        val face = NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f)
        assertEquals(face, BlurScopeMath.expand(face, BlurScope.FACE_ONLY))
        assertEquals(
            BlurBoxMath.expandPersonRegion(face),
            BlurScopeMath.expand(face, BlurScope.FACE_AND_BODY)
        )
    }

    // ---- Tiled detection mapping ----

    @Test
    fun tilesAreFourOverlappingQuadrants() {
        val tiles = TileMath.tiles()
        assertEquals(4, tiles.size)
        assertEquals(NormalizedRect(0f, 0f, 0.6f, 0.6f), tiles[0])
        assertEquals(NormalizedRect(0.4f, 0f, 1f, 0.6f), tiles[1])
        assertEquals(NormalizedRect(0f, 0.4f, 0.6f, 1f), tiles[2])
        assertEquals(NormalizedRect(0.4f, 0.4f, 1f, 1f), tiles[3])
    }

    @Test
    fun tileLocalBoxMapsBackToFullFrame() {
        // A box in the bottom-right tile's local coords.
        val tile = TileMath.tiles()[3] // (0.4, 0.4) - (1, 1)
        val local = NormalizedRect(0.5f, 0.5f, 0.75f, 0.75f)
        val full = TileMath.mapToFullFrame(tile, local)
        assertEquals(0.4f + 0.5f * 0.6f, full.left, 0.001f) // 0.7
        assertEquals(0.4f + 0.5f * 0.6f, full.top, 0.001f)
        assertEquals(0.4f + 0.75f * 0.6f, full.right, 0.001f) // 0.85
        assertEquals(0.4f + 0.75f * 0.6f, full.bottom, 0.001f)
    }

    @Test
    fun tileMappingKeepsFullTileBoxEqualToTile() {
        val tile = TileMath.tiles()[1]
        val full = TileMath.mapToFullFrame(tile, NormalizedRect(0f, 0f, 1f, 1f))
        assertEquals(tile, full)
    }

    // ---- PiP action icon selection ----

    @Test
    fun pipMuteIconSelection() {
        assertEquals(PipActionIcons.MuteIcon.MUTED, PipActionIcons.muteIconFor(true))
        assertEquals(PipActionIcons.MuteIcon.UNMUTED, PipActionIcons.muteIconFor(false))
    }

    // ---- Floating window sizing ----

    @Test
    fun floatingWidthClampsToMinAndMax() {
        assertEquals(
            FloatingWindowMath.MIN_WIDTH_DP,
            FloatingWindowMath.clampWidthDp(40f, 400f), 0.01f
        )
        assertEquals(
            FloatingWindowMath.MAX_WIDTH_DP,
            FloatingWindowMath.clampWidthDp(900f, 1000f), 0.01f
        )
        // Screen-relative cap: 92% of a 360dp-wide screen = 331.2.
        assertEquals(331.2f, FloatingWindowMath.clampWidthDp(500f, 360f), 0.01f)
        assertEquals(200f, FloatingWindowMath.clampWidthDp(200f, 400f), 0.01f)
    }

    @Test
    fun floatingResizeAppliesZoomAndDragDelta() {
        assertEquals(300f, FloatingWindowMath.resizedWidthDp(200f, zoom = 1.5f, screenWidthDp = 1000f), 0.01f)
        assertEquals(240f, FloatingWindowMath.resizedWidthDp(200f, dragDeltaDp = 40f, screenWidthDp = 1000f), 0.01f)
        // Pinch-out past the cap clamps.
        assertEquals(
            FloatingWindowMath.MAX_WIDTH_DP,
            FloatingWindowMath.resizedWidthDp(400f, zoom = 2f, screenWidthDp = 1000f), 0.01f
        )
    }

    // ---- Gesture policy ----

    @Test
    fun gesturePolicyOffMeansEveryDragMinimizesAndNoZones() {
        assertFalse(PlayerGesturePolicy.brightnessVolumeEnabled(false))
        assertTrue(PlayerGesturePolicy.isMinimizeDragStart(0.1f, false))
        assertTrue(PlayerGesturePolicy.isMinimizeDragStart(0.5f, false))
        assertTrue(PlayerGesturePolicy.isMinimizeDragStart(0.9f, false))
        assertEquals(0, PlayerGesturePolicy.swipeZone(0.1f, false))
        assertEquals(0, PlayerGesturePolicy.swipeZone(0.9f, false))
    }

    @Test
    fun gesturePolicyOnRestoresZonesAndCentreMinimize() {
        assertTrue(PlayerGesturePolicy.brightnessVolumeEnabled(true))
        assertEquals(-1, PlayerGesturePolicy.swipeZone(0.1f, true))
        assertEquals(1, PlayerGesturePolicy.swipeZone(0.9f, true))
        assertEquals(0, PlayerGesturePolicy.swipeZone(0.5f, true))
        assertFalse(PlayerGesturePolicy.isMinimizeDragStart(0.1f, true))
        assertFalse(PlayerGesturePolicy.isMinimizeDragStart(0.9f, true))
        assertTrue(PlayerGesturePolicy.isMinimizeDragStart(0.5f, true))
    }
}
