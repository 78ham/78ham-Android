package com.ham78.app.network

/**
 * 设备型号编号 → 名字。
 * 编号表与小程序 pages/voice/devmodels.js 一致，这样同一台设备在两边显示同一个名字。
 * 0 表示包里没带型号（旧消息也是 0），界面上就不显示设备。
 */
object DevModel {
    fun name(id: Int): String = when (id) {
        0 -> ""
        1 -> "NRL-2100"
        2 -> "NRL-2200"
        3 -> "NRL-2300"
        7 -> "树莓派"
        8 -> "NRL-2600"
        9 -> "NRL-3188"
        10 -> "NRL-7100"
        11 -> "NRL-FT891"
        12 -> "NRL-TS480"
        13 -> "NRL-IC2720"
        14 -> "NRL-IC2730"
        15 -> "NRL-FT7900"
        16 -> "NRL-V71/D710"
        17 -> "NRL-3100"
        18 -> "NRL-8100-HF"
        19 -> "NRL-DR-635"
        20 -> "FTM-300D"
        21 -> "FTM-400D"
        22 -> "NRL-ESP32"
        23 -> "MMDVM"
        24 -> "W801S"
        25 -> "4G便携"
        26 -> "董哥定制便携"
        28 -> "即时通9000"
        29 -> "NRL-TK90"
        30 -> "NRL-M802"
        31 -> "NRL-ICOM-F8101"
        32 -> "旋转器RC-3040S"
        33 -> "DTRC APP"
        35 -> "NRL-3188P"
        36 -> "NRL-2700-HF-HOST"
        37 -> "NRL-2700-HF-PANEL"
        50 -> "海能达中继"
        55 -> "UVE5"
        60 -> "BA4QAO-ESP32"
        66 -> "BD4VKI-ESP32"
        68 -> "BG4SF-Zeenow"
        70 -> "BI4UMD-ESP32"
        80 -> "BG4QG-ESP32"
        86 -> "BG4SF-Cottell"
        88 -> "BG4SF-Leapower"
        90 -> "BH6BBH-ESP32"
        99 -> "BH4TIH-ESP32"
        100 -> "NRL-微信小程序"
        101 -> "NRL-73HAM安卓"
        102 -> "NR工具集-IOS"
        103 -> "NRL-Win"
        105 -> "NRL-浏览器"
        106 -> "NRL-救援-ios"
        107 -> "NRL-互联安卓"
        108 -> "NRL-Win-BD4VKI"
        109 -> "NRL-win-BG5FOX"
        110 -> "NRL-Pulse"
        111 -> "NRL-BH6BBH"
        119 -> "NRL-73HAM Pro 安卓"
        200 -> "NRL-Server"
        201 -> "NRL-会议"
        202 -> "NRL-BM"
        221 -> "BG4SF-FormatConversion-1"
        222 -> "BG4SF-FormatConversion-2"
        250 -> "NRL-保姆"
        255 -> "NRL-全网互连"
        else -> "设备 $id"
    }

    /** 呼号-ssid，有型号时再接上设备名 */
    fun who(callsign: String, ssid: Int, devModel: Int): String {
        val device = name(devModel)
        val id = "$callsign-$ssid"
        return if (device.isEmpty()) id else "$id · $device"
    }
}
