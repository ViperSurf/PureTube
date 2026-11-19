package com.puretube.app

import com.puretube.app.data.local.datastore.AppPreferencesState
import com.puretube.app.data.local.datastore.BlurVisualStyle
import com.puretube.app.data.local.datastore.HistoryGroupMode
import com.puretube.app.data.local.datastore.ThumbnailScale
import com.puretube.app.domain.SectionResets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionResetsTest {

    @Test
    fun sectionsRenderInNumericOrder() {
        // v2.1: section 8 (Captions) added after Diagnostics.
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8"),
            SectionResets.sectionsInOrder.map { it.substringBefore('.') }
        )
    }

    @Test
    fun resettingSection1RestoresOnlySection1() {
        val custom = AppPreferencesState(
            blurVisualStyle = BlurVisualStyle.SOLID_DIM_CURTAIN,
            detectionSensitivity = 0.4f,
            autoplayEnabled = false
        )
        val reset = SectionResets.reset(custom, SectionResets.SECTION_1)
        val d = AppPreferencesState()
        assertEquals(d.blurVisualStyle, reset.blurVisualStyle)
        assertEquals(d.detectionSensitivity, reset.detectionSensitivity)
        // Section 2 untouched:
        assertEquals(false, reset.autoplayEnabled)
    }

    @Test
    fun resettingSection3RestoresLayoutScaleAndColumns() {
        val custom = AppPreferencesState(
            feedThumbnailScale = ThumbnailScale.LARGE,
            gridColumns = 4
        )
        val reset = SectionResets.reset(custom, SectionResets.SECTION_3)
        assertEquals(ThumbnailScale.NORMAL, reset.feedThumbnailScale)
        assertEquals(2, reset.gridColumns)
    }

    @Test
    fun resettingSection5RestoresHistoryMode() {
        val custom = AppPreferencesState(historyGroupMode = HistoryGroupMode.CONTINUOUS)
        val reset = SectionResets.reset(custom, SectionResets.SECTION_5)
        assertEquals(HistoryGroupMode.GROUPED_BY_DAY, reset.historyGroupMode)
        assertTrue(reset.watchHistoryEnabled)
    }

    @Test
    fun resettingDiagnosticsSectionChangesNothing() {
        val custom = AppPreferencesState(autoplayEnabled = false)
        assertEquals(custom, SectionResets.reset(custom, SectionResets.SECTION_7))
    }
}
