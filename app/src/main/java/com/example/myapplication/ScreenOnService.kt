package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 常亮前台服务。
 *
 * 存在的唯一理由：进程被系统冻结/回收时 WakeLock 会失效，屏幕又开始自动熄灭。
 * 前台服务让系统知道「用户主动要求这个应用保持运行」，从而大幅降低被杀概率，
 * 常亮状态才能在你退出界面、清掉后台之后继续生效。
 */
class ScreenOnService : Service() {

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 按电源键会让屏幕 WakeLock 被系统释放；用户再次亮屏后重新获取。
            if (WakeLockController.isWanted(this@ScreenOnService) && !WakeLockController.isOn()) {
                WakeLockController.enable(this@ScreenOnService)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WakeLockController.disable(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!WakeLockController.isWanted(this)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                try {
                    startForegroundCompat()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "startForeground failed", e)
                    WakeLockController.disable(this)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val ok = WakeLockController.enable(this)
                Log.i(TAG, "service started, wakeLock=$ok isHeld=${WakeLockController.isOn()}")
                if (!ok) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // 被系统回收后自动重建，并恢复常亮（用户没点关闭就应继续生效）
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOnReceiver)
        super.onDestroy()
        // 服务被销毁时必须释放锁，否则会一直吊着屏幕。
        // 注意：用户主动关闭时 isWanted 已为 false；此处无条件释放是安全的，
        // 因为服务存在的意义就是持有这把锁。
        WakeLockController.release()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        // Q 起的 3 参重载才接受类型；更低版本只能用 2 参重载
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenOnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_tile_keep_screen_on)
            .setContentIntent(tapIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notif_stop),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            // 低重要度：不响铃、不弹横幅，只在通知栏安静地挂一条
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScreenOnService"
        private const val CHANNEL_ID = "screen_on"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.myapplication.action.STOP"

        fun start(context: Context): Boolean {
            val intent = Intent(context, ScreenOnService::class.java)
            WakeLockController.requestEnable(context)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.onFailure {
                Log.e(TAG, "start service failed", it)
                WakeLockController.disable(context)
            }.getOrDefault(false)
        }

        /**
         * 关闭常亮。
         *
         * 先释放锁并清掉「用户想要常亮」标记，再停服务：
         * 这样即使服务此刻并未运行，状态也是正确的，而且不会为了停一个
         * 没在跑的服务反而把它启动起来（startService + STOP 会走 onCreate）。
         */
        fun stop(context: Context) {
            WakeLockController.disable(context)
            runCatching { context.stopService(Intent(context, ScreenOnService::class.java)) }
        }
    }
}
