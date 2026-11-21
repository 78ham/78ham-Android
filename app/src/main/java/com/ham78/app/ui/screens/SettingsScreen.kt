package com.ham78.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.data.AudioCodec
import com.ham78.app.data.BroadcastSchedule
import com.ham78.app.data.RogerBeepStyle
import com.ham78.app.data.ScheduleMode
import com.ham78.app.data.SettingsRepository
import com.ham78.app.data.UserSettings
import com.ham78.app.ui.UserAvatar
import com.ham78.app.ui.theme.*
import android.view.KeyEvent
import android.widget.Toast

/** 从包信息读取版本名：单一来源（build.gradle 的 versionName），界面不再手写字符串 */
private fun appVersionName(context: android.content.Context): String = try {
    "版本 ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"
} catch (_: Exception) {
    ""
}

/**
 * 设置界面（内嵌在主页中）
 * @param onLogout 退出登录：清凭据 → 停服务 → 回登录页（由 MainApp 传入并已实现跳转）
 */
@Composable
fun SettingsScreen(
    onLogout: (() -> Unit)? = null,
    avatarUrl: String = "",
    localAvatar: java.io.File? = null,
    onChangeAvatar: (() -> Unit)? = null,
    onOpenDevices: (() -> Unit)? = null,
    onOpenGroup: (() -> Unit)? = null,
    onOpenMonitor: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val appVersionName = remember { appVersionName(context) }
    val settings by settingsRepository.settings.collectAsState()
    val scrollState = rememberScrollState()

    var ssid by remember(settings) { mutableStateOf(settings.ssid.toString()) }
    var codec by remember(settings) { mutableStateOf(settings.codec) }
    var volume by remember(settings) { mutableStateOf(settings.volume) }
    var gain by remember(settings) { mutableStateOf(settings.gain) }
    var screenOffPtt by remember(settings) { mutableStateOf(settings.screenOffPtt) }
    var autoConnect by remember(settings) { mutableStateOf(settings.autoConnect) }
    var pttKeyCode by remember(settings) { mutableStateOf(settings.pttKeyCode.toString()) }
    var rogerBeep by remember(settings) { mutableStateOf(settings.rogerBeep) }

    // 定时播报任务列表（增删改直接落库，不走底部"保存设置"）
    val schedules = settings.broadcastSchedules
    var showScheduleEditor by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<BroadcastSchedule?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        if (onChangeAvatar != null || onOpenDevices != null) {
            SettingsSection(title = "账号") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = onChangeAvatar != null) { onChangeAvatar?.invoke() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(url = avatarUrl, localFile = localAvatar, size = 48.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(settings.callsign.ifEmpty { "未登录" }, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text("更换头像。没有头像时用默认头像", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                if (onOpenDevices != null) {
                    SettingsLink("我的设备", "查看设备、下发 AT 指令、改设备设置", onOpenDevices)
                }
                if (onOpenGroup != null) {
                    SettingsLink("群组详情", "当前频道里的全部设备", onOpenGroup)
                }
                if (onOpenMonitor != null) {
                    SettingsLink("实时监听", "看各房间谁在说话，并可单独收听", onOpenMonitor)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 设备信息
        SettingsSection(title = "设备信息") {
            if (settings.callsign.isNotEmpty() && settings.dmrId != 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("呼号", fontSize = 12.sp, color = TextSecondary)
                        Text(
                            settings.callsign,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandPurple
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("DMR ID", fontSize = 12.sp, color = TextSecondary)
                        Text(
                            settings.dmrId.toString(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandPurple
                        )
                    }
                }
            } else {
                Text(
                    "登录后自动获取呼号和 DMR ID",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }

            // SSID
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it.filter { c -> c.isDigit() } },
                label = { Text("SSID (默认 179)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("179") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 音频设置
        SettingsSection(title = "音频设置") {
            Text("编码格式", fontSize = 14.sp, color = TextSecondary)
            Row {
                RadioButton(
                    selected = codec == AudioCodec.G711,
                    onClick = { codec = AudioCodec.G711 }
                )
                Text("G711", modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(
                    selected = codec == AudioCodec.OPUS,
                    onClick = { codec = AudioCodec.OPUS }
                )
                Text("Opus", modifier = Modifier.align(Alignment.CenterVertically))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("音量: $volume%", fontSize = 14.sp, color = TextSecondary)
            Slider(
                value = volume.toFloat(),
                onValueChange = { volume = it.toInt() },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = BrandPurple,
                    activeTrackColor = BrandPurple
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text("增益: ${"%.1f".format(gain)}x", fontSize = 14.sp, color = TextSecondary)
            Slider(
                value = gain,
                onValueChange = { gain = it },
                valueRange = 0.5f..3.0f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = BrandPurple,
                    activeTrackColor = BrandPurple
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 对讲设置
        SettingsSection(title = "对讲设置") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("息屏启麦")
                Switch(
                    checked = screenOffPtt,
                    onCheckedChange = { screenOffPtt = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = BrandPurple)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("自动连接")
                Switch(
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = BrandPurple)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("PTT 按键码", fontSize = 14.sp, color = TextSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = pttKeyCode,
                    onValueChange = { pttKeyCode = it.filter { c -> c.isDigit() } },
                    label = { Text("按键码") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Text(
                "常用: 24(音量+) 25(音量-) 262(专用PTT)",
                fontSize = 12.sp,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 尾音设置：松开 PTT 后自动追加的结束提示音
        SettingsSection(title = "尾音 (Roger Beep)") {
            Text("松开 PTT 后自动发送", fontSize = 13.sp, color = TextSecondary)
            RogerBeepStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = rogerBeep == style,
                        onClick = { rogerBeep = style }
                    )
                    Text(style.label, modifier = Modifier.align(Alignment.CenterVertically), fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 定时播报任务：到点自动 TTS 播报（增删改即时生效，无需点保存）
        SettingsSection(title = "定时播报") {
            if (schedules.isEmpty()) {
                Text("还没有任务。到点自动用语音播报设定文字。", fontSize = 13.sp, color = TextSecondary)
            } else {
                schedules.forEach { schedule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(schedule.name.ifEmpty { "未命名" }, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                text = scheduleDescription(schedule),
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = schedule.content.take(20),
                                fontSize = 12.sp,
                                color = TextSecondary.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        // 启用开关
                        Switch(
                            checked = schedule.enabled,
                            onCheckedChange = { enabled ->
                                settingsRepository.updateSchedule(schedule.copy(enabled = enabled))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = BrandPurple)
                        )
                        // 编辑
                        IconButton(onClick = {
                            editingSchedule = schedule
                            showScheduleEditor = true
                        }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = TextSecondary)
                        }
                        // 删除
                        IconButton(onClick = {
                            settingsRepository.removeSchedule(schedule.id)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Error)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    editingSchedule = null   // null = 新建
                    showScheduleEditor = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("添加定时播报")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 关于
        SettingsSection(title = "关于") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = BrandPurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("78HAM 业余无线电对讲", fontWeight = FontWeight.Medium)
                    Text(appVersionName, fontSize = 12.sp, color = TextSecondary)
                }
            }

            // 退出登录：只在已登录（有账号）时显示
            if (onLogout != null && settings.username.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                ) {
                    Text("退出登录")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 保存按钮
        Button(
            onClick = {
                val newSettings = UserSettings(
                    username = settings.username,
                    password = settings.password,
                    serverAddress = settings.serverAddress,
                    serverPort = settings.serverPort,
                    dmrId = settings.dmrId,
                    callsign = settings.callsign,
                    ssid = ssid.toIntOrNull() ?: 179,
                    codec = codec,
                    volume = volume,
                    gain = gain,
                    screenOffPtt = screenOffPtt,
                    pttKeyCode = pttKeyCode.toIntOrNull() ?: KeyEvent.KEYCODE_VOLUME_UP,
                    autoConnect = autoConnect,
                    servers = settings.servers,
                    rogerBeep = rogerBeep,
                    broadcastSchedules = settings.broadcastSchedules
                )
                settingsRepository.saveSettings(newSettings)
                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandPurple,
                contentColor = TextOnPrimary
            )
        ) {
            Text("保存设置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // 定时播报编辑对话框（新建/编辑共用）
    if (showScheduleEditor) {
        ScheduleEditorDialog(
            existing = editingSchedule,
            onSave = { schedule ->
                settingsRepository.addSchedule(schedule)
                showScheduleEditor = false
            },
            onDismiss = { showScheduleEditor = false }
        )
    }
}

/** 定时播报列表里的一行描述：模式 + 时间 */
private fun scheduleDescription(schedule: BroadcastSchedule): String = when (schedule.mode) {
    ScheduleMode.EVERY_MINUTE -> "每分钟播报"
    ScheduleMode.EVERY_5_MIN -> "每 5 分钟播报"
    ScheduleMode.EVERY_15_MIN -> "每 15 分钟播报"
    ScheduleMode.DAILY -> "每天 %02d:%02d".format(schedule.hour, schedule.minute)
}

/**
 * 定时播报编辑对话框：名称、模式、时间（仅 DAILY）、播报文字。
 * existing = null 时是新建，给默认值。
 */
@Composable
private fun ScheduleEditorDialog(
    existing: BroadcastSchedule?,
    onSave: (BroadcastSchedule) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mode by remember { mutableStateOf(existing?.mode ?: ScheduleMode.DAILY) }
    var hour by remember { mutableStateOf((existing?.hour ?: 12).toString()) }
    var minute by remember { mutableStateOf((existing?.minute ?: 0).toString()) }
    var content by remember { mutableStateOf(existing?.content ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = { Text(if (existing == null) "添加定时播报" else "编辑定时播报", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("任务名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = scheduleFieldColors()
                )

                // 播报模式选择
                Text("播报频率", fontSize = 13.sp, color = TextSecondary)
                Column {
                    ScheduleMode.entries.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mode == m,
                                onClick = { mode = m }
                            )
                            Text(m.label, fontSize = 14.sp)
                        }
                    }
                }

                // DAILY 模式才显示时间输入
                if (mode == ScheduleMode.DAILY) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour,
                            onValueChange = { hour = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("时 (0-23)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = scheduleFieldColors()
                        )
                        OutlinedTextField(
                            value = minute,
                            onValueChange = { minute = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("分 (0-59)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = scheduleFieldColors()
                        )
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("播报文字") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = scheduleFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(BroadcastSchedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        mode = mode,
                        hour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 12,
                        minute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                        content = content.trim(),
                        enabled = existing?.enabled ?: true
                    ))
                },
                enabled = content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 定时播报编辑器的输入框配色（与全 app 统一） */
@Composable
private fun scheduleFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandPurple.copy(alpha = 0.5f),
    unfocusedBorderColor = Divider,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = BrandPurple
)

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.Medium)
        Text(subtitle, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = BrandPurple,
            fontWeight = FontWeight.Bold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}
