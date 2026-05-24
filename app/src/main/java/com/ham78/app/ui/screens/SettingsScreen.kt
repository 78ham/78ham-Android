package com.ham78.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.ham78.app.data.SettingsRepository
import com.ham78.app.data.UserSettings
import com.ham78.app.ui.theme.*
import android.view.KeyEvent
import android.widget.Toast

/**
 * 设置界面（内嵌在主页中）
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings.collectAsState()
    val scrollState = rememberScrollState()

    var ssid by remember { mutableStateOf(settings.ssid.toString()) }
    var codec by remember { mutableStateOf(settings.codec) }
    var volume by remember { mutableStateOf(settings.volume) }
    var screenOffPtt by remember { mutableStateOf(settings.screenOffPtt) }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }
    var pttKeyCode by remember { mutableStateOf(settings.pttKeyCode.toString()) }

    LaunchedEffect(settings) {
        ssid = settings.ssid.toString()
        codec = settings.codec
        volume = settings.volume
        screenOffPtt = settings.screenOffPtt
        autoConnect = settings.autoConnect
        pttKeyCode = settings.pttKeyCode.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

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
                    Text("版本 2.0.0", fontSize = 12.sp, color = TextSecondary)
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
                    screenOffPtt = screenOffPtt,
                    pttKeyCode = pttKeyCode.toIntOrNull() ?: KeyEvent.KEYCODE_VOLUME_UP,
                    autoConnect = autoConnect,
                    servers = settings.servers
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

        Spacer(modifier = Modifier.height(100.dp))
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
