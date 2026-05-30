package com.ham78.app.ptt

import android.os.Build
import android.util.Log

/**
 * 设备按键方案配置
 * 根据 Build.MODEL / Build.MANUFACTURER 自动匹配
 */
object DeviceKeyProfiles {

    data class DeviceProfile(
        val name: String,
        val match: () -> Boolean,
        val pttKeyCode: Int,
        val extraKeyCodes: List<Int> = emptyList(),
        val useBroadcastPtt: Boolean = false,
        val broadcastActions: List<String> = emptyList()
    )

    val profiles = listOf(
        DeviceProfile(
            name = "和对讲 D12",
            match = { Build.MODEL.contains("D12", ignoreCase = true) ||
                      Build.MODEL.contains("Interphone D12", ignoreCase = true) },
            pttKeyCode = 113,
            extraKeyCodes = listOf(113, 0x107, 0x108, 0x109, 0x10A, 0x10B)
        ),
        DeviceProfile(
            name = "MTK 平台 (PTT广播)",
            match = { Build.HARDWARE.contains("mt", ignoreCase = true) &&
                      !Build.MODEL.contains("D12", ignoreCase = true) },
            pttKeyCode = 0x106,
            useBroadcastPtt = true,
            broadcastActions = listOf(
                "android.intent.action.PTT.down",
                "android.intent.action.PTT.up"
            )
        ),
        DeviceProfile(
            name = "通用 PTT (0x106)",
            match = { true },
            pttKeyCode = 0x106
        )
    )

    fun detect(): DeviceProfile {
        val profile = profiles.first { it.match() }
        Log.i("DeviceKeyProfiles",
            "Device: model=${Build.MODEL}, manufacturer=${Build.MANUFACTURER}, " +
            "hardware=${Build.HARDWARE} -> profile=${profile.name}")
        return profile
    }
}
