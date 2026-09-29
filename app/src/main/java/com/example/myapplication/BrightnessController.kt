package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 按用户需求关闭自动亮度。
 *
 * 用户开启常亮时关闭自动亮度。关闭常亮时不会重新打开自动亮度。
 *
 * 需要 WRITE_SETTINGS（「修改系统设置」）权限。部分 ROM（实测 ColorOS）会
 * 定期回收长期未使用应用的该权限，所以这里每次写入都做回读校验：
 * 写入后值没变就说明权限失效，此时返回 false，由界面提示用户重新授权，
 * 而不是假装成功。
 */
object BrightnessController {

    private const val TAG = "BrightnessController"

    /** 是否具备修改系统设置的权限。 */
    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /**
     * 关闭自动亮度，锁定当前亮度。
     * @return true 表示确实生效（已回读确认）；未授权或写入被拦截时返回 false。
     */
    fun lock(context: Context): Boolean {
        if (currentMode(context) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return true
        if (!canWrite(context)) {
            Log.i(TAG, "无 WRITE_SETTINGS 权限，跳过亮度锁定")
            return false
        }
        val resolver = context.contentResolver
        return try {
            val originalMode = currentMode(context)
            val originalBrightness = currentBrightness(context)

            // 先把自动亮度关掉（这是变暗的主因），再固定亮度值
            val modeOk = Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val brightnessOk = Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                originalBrightness
            )

            // 回读校验：部分 ROM 的 putInt 会返回 true 但实际不写入
            val modeNow = currentMode(context)
            val applied = modeNow == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Log.i(
                TAG,
                "lock modeOk=$modeOk brightnessOk=$brightnessOk 原mode=$originalMode 原亮度=$originalBrightness 回读mode=$modeNow 生效=$applied"
            )
            applied
        } catch (e: SecurityException) {
            Log.e(TAG, "锁定亮度失败：权限不足", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "锁定亮度失败", e)
            false
        }
    }

    fun currentMode(context: Context): Int = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
    }.getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

    fun currentBrightness(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    }.getOrDefault(128)

    /** 跳到本应用的「修改系统设置」授权页。 */
    fun openSettingsPage(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "打开授权页失败", it) }
    }
}
