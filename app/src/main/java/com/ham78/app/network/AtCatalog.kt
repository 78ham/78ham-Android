package com.ham78.app.network

/**
 * 设备远程管理方式，和指令目录。
 * 与小程序 utils/atCatalog.js 一致：界面只展示中文名称，真正发出去的是 AT+键名。
 * ESP32 家族走新指令集；其余型号号小于 100 的硬件走老指令集，并保留寄存器配置入口。
 */

enum class AtInput { TEXT, NUMBER, SWITCH, SELECT, ACTION, READONLY }

data class AtCommand(
    val key: String,
    val label: String,
    val desc: String = "",
    val input: AtInput,
    val write: Boolean = false,
    val min: Int? = null,
    val max: Int? = null,
    val options: List<String> = emptyList(),
    val actionValue: String = "1",
    val confirm: String = ""
)

data class AtCategory(
    val id: String,
    val name: String,
    val hint: String = "",
    val commands: List<AtCommand>
)

object AtCatalog {
    private val esp32Family = setOf(22, 60, 66, 70, 80, 90, 99)
    private val esp32Signature = listOf(
        "AT+WIFI_SSID", "WIFI_SSID", "AT+ESPNOW", "ESPNOW",
        "AT+SCI", "SCI", "AT+CODEC", "CODEC", "AT+VOICE_BYTES", "VOICE_BYTES"
    )

    /** at：ESP32 指令集。at-legacy：老硬件，AT 之外还有寄存器。none：软终端，不能远程配置。 */
    fun manageKind(devModel: Int): String = when {
        devModel in esp32Family -> "at"
        devModel in 1..99 -> "at-legacy"
        else -> "none"
    }

    fun catalogFor(devModel: Int, atmap: Map<String, String>): List<AtCategory>? {
        if (atmap.isNotEmpty()) {
            val esp32 = esp32Signature.any { atmap.containsKey(it) }
            return if (esp32) esp32Categories else legacyCategories
        }
        return when (manageKind(devModel)) {
            "at" -> esp32Categories
            "at-legacy" -> legacyCategories
            else -> null
        }
    }

    /** 固件回复的键有的带 AT+ 前缀，有的不带。 */
    fun valueOf(atmap: Map<String, String>, key: String): String? =
        atmap["AT+$key"] ?: atmap[key]

    /**
     * 设备还没上报过指令时，按型号给出整本目录。
     * 上报之后只留下设备真正有的命令，目录里没有的归到「未知指令」。
     */
    fun visible(devModel: Int, atmap: Map<String, String>): List<AtCategory> {
        val catalog = catalogFor(devModel, atmap) ?: return emptyList()
        if (atmap.isEmpty()) return catalog
        val known = catalog.flatMap { it.commands }.map { it.key }.toSet()
        val shown = catalog.mapNotNull { cat ->
            val cmds = cat.commands.filter { valueOf(atmap, it.key) != null }
            if (cmds.isEmpty()) null else cat.copy(commands = cmds)
        }
        val unknown = atmap.keys
            .map { it.removePrefix("AT+") }
            .filter { it.isNotEmpty() && it != "ERR" && it !in known }
            .distinct()
            .map { cmd(it, "AT+$it", "设备上报的指令", AtInput.TEXT, true) }
        return if (unknown.isEmpty()) shown
        else shown + AtCategory("unknown", "未知指令", "设备支持但目录里还没有的指令", unknown)
    }

    private fun cmd(
        key: String,
        label: String,
        desc: String = "",
        input: AtInput,
        write: Boolean = false,
        min: Int? = null,
        max: Int? = null,
        options: List<String> = emptyList(),
        actionValue: String = "1",
        confirm: String = ""
    ) = AtCommand(key, label, desc, input, write, min, max, options, actionValue, confirm)

    private val esp32Categories = listOf(
        AtCategory("link", "连接与身份", commands = listOf(
            cmd("CALL", "呼号", "设备的电台呼号", AtInput.TEXT, true),
            cmd("SSID", "呼号 SSID", "同一呼号下区分多台设备（0-255）", AtInput.NUMBER, true, 0, 255),
            cmd("CH", "信道", "Moto 电台信道切换（0-7）", AtInput.NUMBER, true, 0, 7),
            cmd("D_IP", "服务器地址", "NRL 服务器域名或 IP", AtInput.TEXT, true),
            cmd("D_PORT", "服务器端口", "NRL 服务器端口（1-65535）", AtInput.NUMBER, true, 1, 65535),
            cmd("L_PORT", "本地端口", "设备本地端口（1-65535），一般无需修改", AtInput.NUMBER, true, 1, 65535),
            cmd("PTT_TIMEOUT", "PTT 超时", "单次发射最长时间（秒，5-3600），超时自动松开", AtInput.NUMBER, true, 5, 3600),
            cmd("REBOOT", "重启设备", "远程重启设备，约 1 秒后生效", AtInput.ACTION, true, confirm = "确定要远程重启设备吗？")
        )),
        AtCategory("audio", "语音与音频", commands = listOf(
            cmd("VOLUME", "喇叭音量", "本机播放音量（0-255）", AtInput.NUMBER, true, 0, 255),
            cmd("MIC_GAIN", "麦克风增益", "麦克风模拟增益（0-255）", AtInput.NUMBER, true, 0, 255),
            cmd("MIC_PCM_GAIN", "麦克风数字增益", "数字增益倍数（0.1-5.0）", AtInput.TEXT, true),
            cmd("CODEC", "语音编码", "G.711 兼容性好，Opus 音质更高", AtInput.SELECT, true, options = listOf("G711", "OPUS")),
            cmd("TAIL_SUPPRESS", "尾音抑制", "消除语音结束后的拖尾（毫秒，0-5000，0=关闭）", AtInput.NUMBER, true, 0, 5000),
            cmd("VOICE_BYTES", "语音包大小", "每个语音包字节数（160-500），一般无需修改", AtInput.NUMBER, true, 160, 500),
            cmd("LEN_P", "语音报文长度", "NRL 语音报文长度（字节）", AtInput.TEXT, true),
            cmd("AEC", "回声消除", "麦克风回声消除，重启后生效", AtInput.SWITCH, true),
            cmd("HP_DRIVE", "耳机输出驱动", "驱动耳机输出口", AtInput.SWITCH, true)
        )),
        AtCategory("wifi", "WiFi 网络", "远程仅支持查询。修改 WiFi 需要和设备在同一局域网。", listOf(
            cmd("WIFI_SSID", "WiFi 名称", input = AtInput.READONLY),
            cmd("WIFI_PASS", "WiFi 密码", "设备端打码显示，远程不可修改", AtInput.READONLY),
            cmd("WIFI_IP", "IP 地址", input = AtInput.READONLY),
            cmd("WIFI_MASK", "子网掩码", input = AtInput.READONLY),
            cmd("WIFI_GW", "网关", input = AtInput.READONLY),
            cmd("WIFI_DNS", "DNS", input = AtInput.READONLY),
            cmd("WIFI_DHCP", "DHCP", input = AtInput.READONLY)
        )),
        AtCategory("aprs", "APRS", commands = listOf(
            cmd("APRS", "APRS 总开关", input = AtInput.SWITCH, write = true),
            cmd("APRS_NET", "APRS-IS 网络收发", input = AtInput.SWITCH, write = true),
            cmd("APRS_TX", "射频发射（AFSK）", input = AtInput.SWITCH, write = true),
            cmd("APRS_RX", "射频接收（AFSK）", input = AtInput.SWITCH, write = true),
            cmd("APRS_AUTO", "智能信标间隔", "随移动速度自动加快信标", AtInput.SWITCH, true),
            cmd("APRS_SERVER", "APRS-IS 服务器", "可带端口，如 asia.aprs2.net:14580", AtInput.TEXT, true),
            cmd("APRS_SSID", "APRS SSID", "0-15", AtInput.NUMBER, true, 0, 15),
            cmd("APRS_SYMBOL", "APRS 符号", "两个字符，如 /I", AtInput.TEXT, true),
            cmd("APRS_INTERVAL", "信标间隔（秒）", input = AtInput.NUMBER, write = true, min = 10, max = 65535),
            cmd("APRS_PATH", "射频路径", "如 WIDE1-1", AtInput.TEXT, true),
            cmd("APRS_POS", "默认位置", "纬度,经度，无 GPS 时使用", AtInput.TEXT, true),
            cmd("APRS_COMMENT", "信标注释", input = AtInput.TEXT, write = true),
            cmd("APRS_BEACON", "立即发送信标", "马上发一次位置信标", AtInput.ACTION, true)
        )),
        AtCategory("signaling", "信令", commands = listOf(
            cmd("CTCSS_RX_MIC", "亚音解码（电台）", input = AtInput.SWITCH, write = true),
            cmd("CTCSS_RX_NRL", "亚音解码（网络）", input = AtInput.SWITCH, write = true),
            cmd("MDC", "MDC1200 ID", "格式：操作码,参数,ID（十六进制），如 01,00,1234", AtInput.TEXT, true),
            cmd("MDC_RX_MIC", "MDC 解码（电台）", input = AtInput.SWITCH, write = true),
            cmd("MDC_RX_NRL", "MDC 解码（网络）", input = AtInput.SWITCH, write = true),
            cmd("MDC_TX_NRL", "MDC 发送（本地 PTT 后）", input = AtInput.SWITCH, write = true),
            cmd("MDC_TX_SPK", "MDC 发送（网络语音后）", input = AtInput.SWITCH, write = true),
            cmd("DTMF", "DTMF ID", "如 123#", AtInput.TEXT, true),
            cmd("DTMF_RX_MIC", "DTMF 解码（电台）", input = AtInput.SWITCH, write = true),
            cmd("DTMF_RX_NRL", "DTMF 解码（网络）", input = AtInput.SWITCH, write = true),
            cmd("DTMF_TX_NRL", "DTMF 发送（本地 PTT 后）", input = AtInput.SWITCH, write = true),
            cmd("DTMF_TX_SPK", "DTMF 发送（网络语音后）", input = AtInput.SWITCH, write = true)
        )),
        AtCategory("serial", "串口", "UART0 保留给系统日志和串口 AT 指令", listOf(
            cmd("SCI", "SCI 透传串口", "格式：波特率,数据位,校验,停止位，如 9600,8,N,1", AtInput.TEXT, true),
            cmd("UART1_ENABLE", "UART1（SCI）启用", input = AtInput.SWITCH, write = true),
            cmd("UART1_IO", "UART1 引脚", "格式：RX,TX（0-127）", AtInput.TEXT, true),
            cmd("UART2_ENABLE", "UART2（GPS）启用", input = AtInput.SWITCH, write = true),
            cmd("UART2_IO", "UART2 引脚", "格式：RX,TX（0-127）", AtInput.TEXT, true),
            cmd("UART2", "UART2 参数", "格式：波特率,数据位,校验,停止位", AtInput.TEXT, true)
        )),
        AtCategory("espnow", "ESP-NOW 对讲", "设备间近距离离网语音直连", listOf(
            cmd("ESPNOW", "ESP-NOW 对讲", input = AtInput.SWITCH, write = true),
            cmd("ESPNOW_RX", "ESP-NOW 接收", "关闭发射时也能听到对讲", AtInput.SWITCH, true),
            cmd("ESPNOW_CODEC", "ESP-NOW 编码", input = AtInput.SELECT, write = true, options = listOf("G711", "OPUS")),
            cmd("PTT_MODE", "PTT 模式", "NRL=网络发射，ESPNOW=离网直连", AtInput.SELECT, true, options = listOf("NRL", "ESPNOW"))
        )),
        AtCategory("battery", "电池", "仅部分带屏机型支持", listOf(
            cmd("BATT", "电池电压（mV）", "校准后的读数", AtInput.READONLY),
            cmd("BATT_RAW", "电池电压原始值（mV）", input = AtInput.READONLY),
            cmd("BATT_CAL", "校准系数", "500-2000，1000=不修正", AtInput.NUMBER, true, 500, 2000)
        )),
        AtCategory("system", "系统诊断", "面向开发者。OTA 远程只读。", listOf(
            cmd("OTAURL", "OTA 升级服务器", input = AtInput.READONLY),
            cmd("OTACHECK", "OTA 状态", input = AtInput.READONLY),
            cmd("TOP", "任务 CPU 占用", "各任务 CPU 百分比", AtInput.ACTION, true),
            cmd("MEMBENCH", "内存基准测试", input = AtInput.ACTION, write = true)
        ))
    )

    private val legacyCategories = listOf(
        AtCategory("link", "身份与网络", commands = listOf(
            cmd("CALL", "呼号", "最多 6 位", AtInput.TEXT, true),
            cmd("SSID", "呼号 SSID", "必须 3 位数，000-255", AtInput.TEXT, true),
            cmd("DHCP", "DHCP", "自动获取 IP", AtInput.SELECT, true, options = listOf("ON", "OFF")),
            cmd("IP", "本机 IP", "每段必须 3 位数，如 192.168.001.100", AtInput.TEXT, true),
            cmd("MASK", "子网掩码", "每段必须 3 位数", AtInput.TEXT, true),
            cmd("GATEWAY", "网关", "每段必须 3 位数", AtInput.TEXT, true),
            cmd("DNS", "DNS 服务器", "每段必须 3 位数", AtInput.TEXT, true),
            cmd("D_IP", "服务器地址", "IP 每段必须 3 位数", AtInput.TEXT, true),
            cmd("D_PORT", "服务器端口", "必须 5 位数", AtInput.TEXT, true),
            cmd("PHY", "MAC 地址", "固定，无法修改", AtInput.READONLY),
            cmd("REBOOT", "重启设备", "远程重启设备", AtInput.ACTION, true, confirm = "确定要远程重启设备吗？")
        )),
        AtCategory("radio", "射频与 PTT", commands = listOf(
            cmd("CH", "电台信道", "Moto 电台信道切换", AtInput.TEXT, true),
            cmd("PTT_EN", "PTT 使能", "允许/禁止发射", AtInput.SELECT, true, options = listOf("ENABLE", "DISABLE")),
            cmd("PTT_IO", "PTT 电平", "发射有效电平", AtInput.SELECT, true, options = listOf("H", "L")),
            cmd("PTT_RES", "PTT 下拉电阻", input = AtInput.SELECT, write = true, options = listOf("ENABLE", "DISABLE")),
            cmd("PW", "发射功率", "高/低功率", AtInput.SELECT, true, options = listOf("H", "L")),
            cmd("DCD", "DCD 模式", "手动/静噪/声控/禁用", AtInput.SELECT, true, options = listOf("MANUAL", "SQL_LO", "SQL_HI", "VOX", "DISABLE")),
            cmd("DUPLEX", "双工模式", input = AtInput.SELECT, write = true, options = listOf("ON", "OFF")),
            cmd("FILTER", "过滤器", "对方设备编号 000-255", AtInput.TEXT, true),
            cmd("1W_FREQ", "1W 模块射频参数", input = AtInput.TEXT, write = true),
            cmd("1W_MIC", "1W 模块麦克风增益", input = AtInput.TEXT, write = true),
            cmd("1W_VOL", "1W 模块音量", input = AtInput.TEXT, write = true)
        )),
        AtCategory("audio", "音频与尾音", commands = listOf(
            cmd("VOLUME", "音量", "0-100", AtInput.NUMBER, true, 0, 100),
            cmd("ADD_D", "添加尾音", "必须 5 位数（00000-65000），单位 5ms", AtInput.TEXT, true),
            cmd("DEL_D", "消除中继尾音", "必须 5 位数，单位 5ms", AtInput.TEXT, true),
            cmd("SQL_TIME", "接收超时", "000-255，单位秒", AtInput.NUMBER, true, 0, 255),
            cmd("VOX_D", "VOX 延迟断开", "必须 5 位数，单位 5ms", AtInput.TEXT, true),
            cmd("LEN_P", "语音报文长度", input = AtInput.TEXT, write = true)
        )),
        AtCategory("aprs", "APRS", commands = listOf(
            cmd("APRS", "APRS 开关", input = AtInput.SELECT, write = true, options = listOf("ON", "OFF")),
            cmd("A_IP", "APRS 服务器", input = AtInput.TEXT, write = true),
            cmd("AMSG", "APRS 消息内容", "最长 64 字节", AtInput.TEXT, true),
            cmd("WDJD", "APRS 坐标", "格式 3118.55N/12018.00E", AtInput.TEXT, true)
        )),
        AtCategory("display", "显示与电源", "仅部分带屏机型支持", listOf(
            cmd("LCD_TIME", "屏幕熄灭时间", "000-255，单位秒", AtInput.NUMBER, true, 0, 255),
            cmd("LOGO", "OLED 开机 LOGO", input = AtInput.TEXT, write = true),
            cmd("BAT", "电池电量修正", "+255 或 -255", AtInput.TEXT, true)
        )),
        AtCategory("player", "播放控制", "仅支持语音/音乐播放的机型", listOf(
            cmd("PLAY", "播放", input = AtInput.ACTION, write = true),
            cmd("PAUSE", "暂停", input = AtInput.ACTION, write = true),
            cmd("STOP", "停止", input = AtInput.ACTION, write = true),
            cmd("PREW", "上一首", input = AtInput.ACTION, write = true),
            cmd("NEXT", "下一首", input = AtInput.ACTION, write = true),
            cmd("REPEAT", "重复", input = AtInput.ACTION, write = true),
            cmd("PLAY_ID", "播放 ID", "按 ID 播放指定内容", AtInput.TEXT, true)
        )),
        AtCategory("system", "系统诊断", "面向开发者", listOf(
            cmd("LOOP", "音频脱网环路测试", input = AtInput.ACTION, write = true),
            cmd("D_ID", "目标设备 CPU 序列号", "8 位", AtInput.TEXT, true),
            cmd("D_SN", "目标序列号", "6 位", AtInput.TEXT, true),
            cmd("S_ID", "源设备 CPU 序列号", "8 位", AtInput.TEXT, true),
            cmd("S_SN", "源序列号", "6 位", AtInput.TEXT, true),
            cmd("S_PORT", "源端口", "必须 5 位数", AtInput.TEXT, true)
        ))
    )
}
