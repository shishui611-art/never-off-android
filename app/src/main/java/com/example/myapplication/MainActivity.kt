package com.example.myapplication

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                HomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var keepOn by remember { mutableStateOf(WakeLockController.isOn()) }
    var systemTimeout by remember { mutableStateOf(readSystemTimeout(context)) }
    var canWriteSettings by remember { mutableStateOf(BrightnessController.canWrite(context)) }
    var manualBrightness by remember {
        mutableStateOf(
            BrightnessController.currentMode(context) == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
    }

    // 本应用处于前台时，使用系统推荐的窗口标志保持屏幕常亮。
    LaunchedEffect(keepOn) {
        (context as? ComponentActivity)?.window?.let { window ->
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 常亮锁由前台服务异步建立；界面轮询锁状态与系统亮度模式。
    LaunchedEffect(Unit) {
        while (true) {
            keepOn = WakeLockController.isOn()
            systemTimeout = readSystemTimeout(context)
            canWriteSettings = BrightnessController.canWrite(context)
            manualBrightness = BrightnessController.currentMode(context) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            delay(1_000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keepOn = WakeLockController.isOn()
                systemTimeout = readSystemTimeout(context)
                canWriteSettings = BrightnessController.canWrite(context)
                if (keepOn && canWriteSettings) BrightnessController.lock(context)
                manualBrightness = BrightnessController.currentMode(context) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (keepOn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            if (keepOn) R.string.status_keep_on else R.string.status_normal
                        ),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = context.getString(
                            R.string.status_current_timeout,
                            formatTimeout(systemTimeout)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(
                            if (manualBrightness) R.string.status_brightness_locked
                            else R.string.status_brightness_not_locked
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Switch(
                        checked = keepOn,
                        onCheckedChange = { wanted ->
                            if (wanted) {
                                if (!ScreenOnService.start(context)) {
                                    Toast.makeText(context, R.string.toast_keep_on_failed, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                ScreenOnService.stop(context)
                                keepOn = false
                            }
                        }
                    )
                    Text(
                        text = stringResource(R.string.switch_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // 自动亮度尚未关闭且缺少修改系统设置权限时才提示授权。
            if (!manualBrightness && !canWriteSettings) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.brightness_permission_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.brightness_permission_body),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { BrightnessController.openSettingsPage(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_grant))
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.howto_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.howto_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Button(
                    onClick = { requestAddTile(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_add_tile))
                }
            }
        }
    }
}

/** 读取系统当前息屏时间，仅用于展示。 */
private fun readSystemTimeout(context: Context): Int = runCatching {
    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
}.getOrDefault(30_000)

/** 直接请求系统弹出「添加磁贴」确认框，部分 ROM 不支持时给出提示。 */
private fun requestAddTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.toast_add_tile_manual, Toast.LENGTH_LONG).show()
        return
    }
    // 该接口在 StatusBarManager 上（不是 QsTileService），且必须在应用处于前台时调用。
    val manager = context.getSystemService(Context.STATUS_BAR_SERVICE) as? StatusBarManager
    if (manager == null) {
        Toast.makeText(context, R.string.toast_add_tile_manual, Toast.LENGTH_LONG).show()
        return
    }
    try {
        manager.requestAddTileService(
            ComponentName(context, KeepScreenOnTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_tile_keep_screen_on),
            ContextCompat.getMainExecutor(context)
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    R.string.toast_add_tile_ok
                else -> R.string.toast_add_tile_manual
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    } catch (e: RuntimeException) {
        // 少数 ROM 未实现该接口
        Toast.makeText(context, R.string.toast_add_tile_manual, Toast.LENGTH_LONG).show()
    }
}
