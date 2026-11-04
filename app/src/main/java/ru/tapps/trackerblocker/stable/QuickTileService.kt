package ru.tapps.trackerblocker.stable

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка в шторке быстрых настроек: включает и выключает защиту одним тапом.
 *
 * Если согласие на VPN ещё не выдано, открываем приложение — системный диалог
 * можно показать только из Activity.
 */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when {
            BlockVpnService.isWaitingForOtherVpn -> {
                sendServiceAction(BlockVpnService.ACTION_RESUME_NOW)
            }
            BlockVpnService.isRunning -> {
                if (AppSettings.isPaused()) {
                    sendServiceAction(BlockVpnService.ACTION_RESUME)
                } else {
                    sendServiceAction(BlockVpnService.ACTION_STOP)
                }
            }
            VpnService.prepare(this) != null -> openApp()
            else -> {
                AppSettings.setProtectionEnabled(true)
                val intent = Intent(this, BlockVpnService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e: Exception) {
                    openApp()
                }
            }
        }
        updateTile()
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, BlockVpnService::class.java).setAction(action)
        try {
            startService(intent)
        } catch (e: Exception) {
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            // Плитка могла исчезнуть с экрана — молча игнорируем.
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = BlockVpnService.isRunning
        val waiting = BlockVpnService.isWaitingForOtherVpn
        val paused = AppSettings.isPaused()

        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    waiting -> R.string.tile_subtitle_waiting
                    running && paused -> R.string.tile_subtitle_paused
                    running -> R.string.tile_subtitle_on
                    else -> R.string.tile_subtitle_off
                }
            )
        }
        tile.updateTile()
    }

    companion object {
        /** Просит систему обновить вид плитки после смены состояния защиты. */
        fun notifyStateChanged(context: Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, QuickTileService::class.java)
                )
            } catch (e: Exception) {
                // Плитка не добавлена — обновлять нечего.
            }
        }
    }
}
