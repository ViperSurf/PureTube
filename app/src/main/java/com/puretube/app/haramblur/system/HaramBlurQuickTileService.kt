package com.puretube.app.haramblur.system

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.puretube.app.MainActivity

/**
 * Quick Settings tile for the HaramBlur System Shield. Tapping toggles the
 * shield request flow in MainActivity (MediaProjection consent lives there,
 * because a TileService cannot host the consent handshake itself).
 */
class HaramBlurQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = "HaramBlur Shield"
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TOGGLE_SYSTEM_SHIELD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
