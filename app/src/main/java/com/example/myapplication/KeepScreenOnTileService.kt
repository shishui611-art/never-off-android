package com.example.myapplication

import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * 下拉快捷设置里的「永不熄屏」磁贴：
 *  - 点一下：开启屏幕常亮
 *  - 再点一下：恢复正常息屏
 *
 * 实现要点：切换的是 WakeLock（配合前台服务保活），不是改写系统息屏时间。
 * 详见 [WakeLockController] 的说明。
 *
 * 磁贴状态以屏幕锁是否被持有为准；厂商 ROM 仍可能忽略这把锁。
 */
class KeepScreenOnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Log.i(TAG, "onStartListening held=${WakeLockController.isOn()}")
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        Log.i(
            TAG,
            "onClick held=${WakeLockController.isOn()} wanted=${WakeLockController.isWanted(this)}"
        )
        // 屏幕已锁定时系统只会执行到这里：解锁后再真正切换
        if (isLocked) {
            unlockAndRun { applyToggle() }
        } else {
            applyToggle()
        }
    }

    private fun applyToggle() {
        val nowOn = !WakeLockController.isWanted(this)
        if (nowOn) {
            if (!ScreenOnService.start(this)) {
                refreshTile()
                Toast.makeText(this, R.string.toast_keep_on_failed, Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            ScreenOnService.stop(this)
        }

        // 服务异步启动；只有确认拿到屏幕锁后才报告成功。
        Handler(Looper.getMainLooper()).postDelayed({
            refreshTile()
            if (WakeLockController.isWanted(this) != nowOn) return@postDelayed
            val success = !nowOn || WakeLockController.isOn()
            Toast.makeText(
                this,
                if (!success) R.string.toast_keep_on_failed
                else if (nowOn) R.string.toast_keep_on else R.string.toast_keep_off,
                Toast.LENGTH_SHORT
            ).show()
            Log.i(TAG, "applyToggle requested on=$nowOn held=${WakeLockController.isOn()}")
        }, 700)
    }

    /** 按持锁状态同步磁贴。 */
    private fun refreshTile() {
        val tile: Tile = qsTile ?: return
        val on = WakeLockController.isOn()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_keep_screen_on)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (on) R.string.tile_subtitle_on else R.string.tile_subtitle_off
            )
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "KeepScreenOnTile"
    }
}
