package com.example.myapplication

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 屏幕常亮的核心实现。
 *
 * 为什么用 WakeLock 而不是改写 SCREEN_OFF_TIMEOUT：
 * 部分厂商 ROM（实测 ColorOS / Android 16）会静默拦截第三方应用对
 * SCREEN_OFF_TIMEOUT 的写入 —— Settings.System.putInt 返回 true，
 * 但值根本没变。这里改用 PowerManager 的屏幕锁；某些 ROM 仍可能忽略它，
 * 所以 isHeld 只能证明锁已获取，不能证明物理屏幕始终亮着。
 *
 * 还需要前台服务配合：进程一旦被系统冻结或回收，WakeLock 会随之失效，
 * 常亮就断了。前台服务能显著降低被冻结/回收的概率。
 */
object WakeLockController {

    private const val TAG = "WakeLockController"
    private const val PREFS = "never_off"
    private const val KEY_WANTED = "screen_on_wanted"

    @Volatile
    private var lock: PowerManager.WakeLock? = null

    /**
     * 当前是否持有屏幕级 WakeLock。
     *
     * 以「是否持有 WakeLock」为准，而不是凭本地开关：
     * 进程被杀、被系统抢走锁之后，本地开关会与事实脱节，
     * 这时磁贴应该显示未开启，而不是骗用户说还开着。
     */
    fun isOn(): Boolean = lock?.isHeld == true

    /** 用户是否希望常亮。用于异步启动、进程重生和磁贴切换。 */
    fun isWanted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WANTED, false)

    fun requestEnable(context: Context) {
        prefs(context).edit().putBoolean(KEY_WANTED, true).apply()
    }

    /** 开启常亮；返回是否成功持有了锁。 */
    fun enable(context: Context): Boolean {
        // 已经持有就不重复申请
        lock?.let { if (it.isHeld) {
            prefs(context).edit().putBoolean(KEY_WANTED, true).apply()
            return true
        } }

        val pm = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            Log.e(TAG, "PowerManager unavailable")
            prefs(context).edit().putBoolean(KEY_WANTED, false).apply()
            return false
        }

        val acquired = try {
            @Suppress("DEPRECATION")
            val fresh = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "NeverOff:screen"
            )
            fresh.setReferenceCounted(false)
            // 不设超时：由用户点磁贴来结束，而不是到点自动释放
            fresh.acquire()
            lock = fresh
            Log.i(TAG, "wake lock acquired isHeld=${fresh.isHeld}")
            fresh.isHeld
        } catch (e: Exception) {
            // PARTIAL_WAKE_LOCK 只保持 CPU 运行，不能替代屏幕锁。
            Log.e(TAG, "acquire SCREEN_BRIGHT failed", e)
            false
        }

        prefs(context).edit().putBoolean(KEY_WANTED, acquired).apply()
        if (acquired) BrightnessController.lock(context)
        return acquired
    }

    /** 关闭常亮，释放锁。自动亮度保持用户当前选择，不在关闭时重新打开。 */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_WANTED, false).apply()
        release()
    }

    /** 只释放锁，不改变用户的意愿标记（用于进程退出前的清理）。 */
    fun release() {
        val current = lock ?: return
        runCatching {
            if (current.isHeld) current.release()
        }.onFailure { Log.e(TAG, "release failed", it) }
        lock = null
        Log.i(TAG, "wake lock released")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
