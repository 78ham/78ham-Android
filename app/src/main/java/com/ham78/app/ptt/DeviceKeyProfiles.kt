package com.ham78.app.ptt

import android.os.Build

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
        val useBroadcastPtt: Boolean = false,          // 是否监听 PTT.down/up 广播
        val broadcastActions: List<String> = emptyList() // 额外广播 action
    )

    val profiles = listOf(
        // 对讲 D12
        DeviceProfile(
            name = "对讲 D12",
            match = { Build.MODEL.contains("D12", ignoreCase = true) ||
                     Build.MODEL.contains("Interphone D12", ignoreCase = true) },
            pttKeyCode = 113,  // KEY_MUTE, 通过 sendevent /dev/input/event2 1 113 1/0
            extraKeyCodes = listOf(113, 0x107, 0x108, 0x109, 0x10A, 0x10B)
        ),
        // MTK 平台通用（广播方式）
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
        // 通用 0x106 PTT
        DeviceProfile(
            name = "通用 PTT (0x106)",
            match = { true },
            pttKeyCode = 0x106
        )
    )

    /**
     * 检测当前设备匹配的按键方案
     */
    fun detect(): DeviceProfile {
        val profile = profiles.first { it.match() }
        android.util.Log.i("DeviceKeyProfiles",
            "Device: model=${Build.MODEL}, manufacturer=${Build.MANUFACTURER}, " +
            "hardware=${Build.HARDWARE} -> profile=${profile.name}")
        return profile
    }
}
