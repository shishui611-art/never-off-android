package com.example.myapplication

/**
 * 时长格式化：30000 -> "30 秒"，120000 -> "2 分钟"，超大值 -> "永不"。
 * 仅用于界面展示，与常亮逻辑无关。
 */
internal fun formatTimeout(ms: Int): String = when {
    ms <= 0 -> "永不"
    ms < 60_000 -> "${ms / 1000} 秒"
    ms % 60_000 == 0 -> "${ms / 60_000} 分钟"
    else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
}
