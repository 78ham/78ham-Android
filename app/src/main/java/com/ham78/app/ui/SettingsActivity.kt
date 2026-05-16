package com.ham78.app.ui

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.data.AudioCodec
import com.ham78.app.data.SettingsRepository
import com.ham78.app.data.UserSettings
import com.ham78.app.ui.theme.*

class SettingsActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "SettingsActivity"
    }
    
    private var isListeningForPtt = false
    private var onPttKeyDetected: ((Int) -> Unit)? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            SettingsScreen(
                onBack = { finish() },
                onStartPttDetection = { callback ->
                    isListeningForPtt = true
                    onPttKeyDetected = callback
                    Toast.makeText(this, "请按下PTT按键...", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isListeningForPtt && event != null) {
            // 排除一些系统按键
            if (keyCode !in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, 
                                   KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                                   KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MENU)) {
                Log.d(TAG, "PTT key detected: $keyCode")
                onPttKeyDetected?.invoke(keyCode)
                isListeningForPtt = false
                onPttKeyDetected = null
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onStartPttDetection: ((Int) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings.collectAsState()
    
    val scrollState = rememberScrollState()
    
    // 本地编辑状态 - 只有这些是需要用户输入的
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }
    var serverAddress by remember { mutableStateOf(settings.serverAddress) }
    var serverPort by remember { mutableStateOf(settings.serverPort.toString()) }
    var ssid by remember { mutableStateOf(settings.ssid.toString()) }
    var codec by remember { mutableStateOf(settings.codec) }
    var volume by remember { mutableStateOf(settings.volume) }
    var screenOffPtt by remember { mutableStateOf(settings.screenOffPtt) }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }
    var pttKeyCode by remember { mutableStateOf(settings.pttKeyCode.toString()) }

    // DMR ID 和呼号是从服务器获取的，这里只读显示
    val dmrId = settings.dmrId
    val callsign = settings.callsign

    // 监听设置变化
    LaunchedEffect(settings) {
        username = settings.username
        password = settings.password
        serverAddress = settings.serverAddress
        serverPort = settings.serverPort.toString()
        ssid = settings.ssid.toString()
        codec = settings.codec
        volume = settings.volume
        screenOffPtt = settings.screenOffPtt
        autoConnect = settings.autoConnect
        pttKeyCode = settings.pttKeyCode.toString()
    }
    
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 服务器设置（最重要，放最上面）
            SettingsSection(title = "服务器设置") {
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it },
                    label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("nrlptt.com") }
                )
                
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            
            // 账户登录信息
            SettingsSection(title = "账户登录") {
                Text(
                    text = "使用 nrl 小程序注册的用户名和密码登录",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            
            // 设备信息
            SettingsSection(title = "设备信息") {
                if (dmrId != 0 && callsign.isNotEmpty()) {
                    // 已获取到信息
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "呼号",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = callsign,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "DMR ID",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = dmrId.toString(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    // 未获取到信息
                    Text(
                        text = "登录后将自动获取您的呼号和 DMR ID",
                        fontSize = 14.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                // SSID（呼号后缀）可编辑
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it.filter { c -> c.isDigit() } },
                    label = { Text("SSID 后缀") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("100") }
                )
                Text(
                    text = "协议帧中的 SSID 字段（offset 30），即呼号后缀数字。修改后需重新连接生效。",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            
            // 音频设置
            SettingsSection(title = "音频设置") {
                // 编码格式
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
                
                // 音量
                Text("音量: $volume%", fontSize = 14.sp, color = TextSecondary)
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { volume = it.toInt() },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
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
                        onCheckedChange = { screenOffPtt = it }
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("开机自动连接")
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = { autoConnect = it }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // PTT 按键码设置
                Text(
                    text = "PTT按键码",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pttKeyCode,
                        onValueChange = { pttKeyCode = it.filter { c -> c.isDigit() } },
                        label = { Text("当前按键码") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            onStartPttDetection { detectedKeyCode ->
                                pttKeyCode = detectedKeyCode.toString()
                                Toast.makeText(context, "检测到按键码: $detectedKeyCode", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("自动识别")
                    }
                }
                
                Text(
                    text = "常用: 24(音量+) 25(音量-) 262(专用PTT)",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            
            // 保存按钮
            Button(
                onClick = {
                    val newSettings = UserSettings(
                        username = username,
                        password = password,
                        serverAddress = serverAddress,
                        serverPort = serverPort.toIntOrNull() ?: 60050,
                        dmrId = dmrId, // 保持原有值
                        callsign = callsign, // 保持原有值
                        ssid = ssid.toIntOrNull() ?: 78,
                        codec = codec,
                        volume = volume,
                        screenOffPtt = screenOffPtt,
                        pttKeyCode = pttKeyCode.toIntOrNull() ?: android.view.KeyEvent.KEYCODE_VOLUME_UP,
                        autoConnect = autoConnect
                    )

                    settingsRepository.saveSettings(newSettings)

                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandPurple,
                    contentColor = TextOnPrimary
                )
            ) {
                Text("保存设置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}
