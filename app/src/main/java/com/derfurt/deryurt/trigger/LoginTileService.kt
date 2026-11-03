package com.derfurt.deryurt.trigger

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.derfurt.deryurt.R
import com.derfurt.deryurt.ui.StatusResolver
import com.derfurt.deryurt.ui.UiState

/** Hızlı ayarlar kutucuğu "GSB giriş": dokununca elle giriş dener. */
class LoginTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        LoginWork.enqueue(applicationContext, manual = true, source = "tile")
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            setSubtitle(tile, getString(R.string.tile_running))
            tile.updateTile()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = StatusResolver.resolve(this)
        tile.label = getString(R.string.tile_label)
        when (state) {
            UiState.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, getString(R.string.tile_running))
            }
            UiState.ONLINE -> {
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, getString(R.string.tile_online))
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, getString(R.string.tile_offline))
            }
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }

    companion object {
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, LoginTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
