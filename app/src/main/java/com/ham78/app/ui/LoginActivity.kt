package com.ham78.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ham78.app.data.ServerConfig
import com.ham78.app.data.SettingsRepository
import com.ham78.app.network.ApiClient
import com.ham78.app.ui.screens.OidcWebScreen
import com.ham78.app.ui.screens.RegisterScreen
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Divider
import com.ham78.app.ui.theme.Error
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun oidcLoginUrl(host: String): String {
    val base = when {
        host.startsWith("http://", ignoreCase = true) -> host
        host.startsWith("https://", ignoreCase = true) -> host
        else -> "https://$host"
    }.trimEnd('/')
    return "$base/user/oidc/login"
}

class LoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置读取（加密 prefs 解密）是异步的，这里不再同步读盘判断"是否已配置过账号"，
        // 改由 LoginScreen 内部的 LaunchedEffect 等待 ready 后跳转（见下方）。

        val needsPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 100
            )
        }

        setContent {
            LoginScreen(
                onLoginSuccess = {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                },
                onSkipLogin = {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onSkipLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var serverAddress by remember { mutableStateOf("js.nrlptt.com") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    // 明文 http 二次确认：null=不显示，非 null=待确认的地址
    var pendingPlainHttp by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    var oidcUrl by remember { mutableStateOf<String?>(null) }
    var oidcButton by remember { mutableStateOf("") }

    // 已配置账号（自动连接）时直接进主界面：等磁盘设置读完再判断，
    // 否则会拿到"还没读盘"的默认值而漏判。未配置则停留在本页。
    LaunchedEffect(Unit) {
        val repo = SettingsRepository(context)
        repo.ready.first { it }
        val settings = repo.loadSettings()
        if (settings.username.isNotEmpty() && settings.password.isNotEmpty() && settings.autoConnect) {
            onSkipLogin()
        }
    }

    LaunchedEffect(serverAddress) {
        delay(400)
        val config = ApiClient.getOidcConfig(serverAddress).getOrNull()
        oidcButton = if (config?.enabled == true) config.buttonName.ifBlank { "OIDC 登录" } else ""
    }

    // --- 登录并保存服务器配置 ---
    fun doLogin() {
        scope.launch {
            try {
                val result = ApiClient.login(
                    serverHost = serverAddress,
                    username = username,
                    password = password
                )

                result.fold(
                    onSuccess = { userInfo ->
                        val settingsRepo = SettingsRepository(context)
                        val currentSettings = settingsRepo.loadSettings()
                        val serverConfig = ServerConfig(
                            id = "${serverAddress}:60050",
                            name = serverAddress,
                            host = serverAddress,
                            port = 60050,
                            username = username,
                            password = password,
                            autoConnect = true
                        )
                        val servers = currentSettings.servers.toMutableList()
                        servers.removeAll { it.id == serverConfig.id }
                        servers.add(serverConfig)

                        settingsRepo.saveSettings(
                            currentSettings.copy(
                                username = username,
                                password = password,
                                serverAddress = serverAddress,
                                callsign = userInfo.callsign,
                                dmrId = userInfo.dmrId,
                                autoConnect = true,
                                servers = servers
                            )
                        )

                        onLoginSuccess()
                    },
                    onFailure = { e ->
                        errorMessage = "登录失败: ${e.message}"
                    }
                )
            } catch (e: Exception) {
                errorMessage = "错误: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun completeOidc(token: String) {
        scope.launch {
            isLoading = true
            errorMessage = ""
            ApiClient.setTokenForServer(serverAddress, token)
            val user = ApiClient.getUserInfo(serverAddress).getOrNull()
            if (user == null || user.callsign.isEmpty()) {
                errorMessage = "OIDC 登录失败"
                isLoading = false
                return@launch
            }
            val settingsRepo = SettingsRepository(context)
            val current = settingsRepo.loadSettings()
            val serverConfig = ServerConfig(
                id = "${serverAddress}:60050",
                name = serverAddress,
                host = serverAddress,
                port = 60050,
                username = user.username.ifEmpty { user.callsign },
                password = "",
                autoConnect = true,
                authToken = token
            )
            val servers = current.servers.toMutableList()
            servers.removeAll { it.id == serverConfig.id }
            servers.add(serverConfig)
            settingsRepo.saveSettings(
                current.copy(
                    username = serverConfig.username,
                    password = "",
                    serverAddress = serverAddress,
                    callsign = user.callsign,
                    dmrId = user.dmrId,
                    autoConnect = true,
                    servers = servers
                )
            )
            onLoginSuccess()
        }
    }

    val pageUrl = oidcUrl
    if (pageUrl != null) {
        OidcWebScreen(
            url = pageUrl,
            onToken = { token ->
                oidcUrl = null
                completeOidc(token)
            },
            onError = { err ->
                oidcUrl = null
                errorMessage = err.ifBlank { "OIDC 登录失败" }
            },
            onCancel = { oidcUrl = null }
        )
        return
    }
    if (showRegister) {
        RegisterScreen(
            serverHost = serverAddress,
            onBack = { showRegister = false },
            onSubmitted = { message ->
                showRegister = false
                errorMessage = message
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D1117),
                        Color(0xFF161B22),
                        Color(0xFF1C2333)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            Text(
                text = "78HAM",
                color = BrandPurple,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "业余无线电对讲 · 多服务器版",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "连接服务器",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it },
                        label = { Text("服务器地址", color = TextSecondary) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.CloudDone,
                                contentDescription = null,
                                tint = BrandPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        placeholder = { Text("js.nrlptt.com", color = TextSecondary.copy(alpha = 0.4f)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPurple,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = BrandPurple,
                            focusedContainerColor = Surface.copy(alpha = 0.5f),
                            unfocusedContainerColor = Surface.copy(alpha = 0.3f)
                        )
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名", color = TextSecondary) },
                        placeholder = { Text("nrl 小程序注册的用户名", color = TextSecondary.copy(alpha = 0.4f), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPurple,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = BrandPurple,
                            focusedContainerColor = Surface.copy(alpha = 0.5f),
                            unfocusedContainerColor = Surface.copy(alpha = 0.3f)
                        )
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPurple,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = BrandPurple,
                            focusedContainerColor = Surface.copy(alpha = 0.5f),
                            unfocusedContainerColor = Surface.copy(alpha = 0.3f)
                        )
                    )

                    AnimatedVisibility(visible = errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = Error,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            if (username.isEmpty() || password.isEmpty()) {
                                errorMessage = "请输入用户名和密码"
                                return@Button
                            }
                            // 明文 http 会让登录密码和 token 明文过网，必须用户确认才放行
                            if (serverAddress.trim().startsWith("http://", ignoreCase = true)) {
                                pendingPlainHttp = serverAddress.trim()
                                return@Button
                            }
                            isLoading = true
                            errorMessage = ""
                            doLogin()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading && username.isNotEmpty() && password.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandPurple,
                            contentColor = TextOnPrimary,
                            disabledContainerColor = BrandPurple.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = TextOnPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            if (isLoading) "连接中..." else "登录并连接",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (oidcButton.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { oidcUrl = oidcLoginUrl(serverAddress.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Text(oidcButton, color = BrandPurple)
                        }
                    }
                    TextButton(
                        onClick = { showRegister = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("注册账号", color = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(onClick = onSkipLogin) {
                Text("跳过登录，稍后配置", color = TextSecondary, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                // 版本号取自包信息（build.gradle versionName），SSID 提示与协议默认值一致
                text = "版本 ${remember {
                    try { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                    catch (_: Exception) { "" }
                }} · SSID 默认 179",
                fontSize = 11.sp,
                color = TextSecondary.copy(alpha = 0.4f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 明文 http 确认框：用户在服务器地址里显式输入了 http:// 才出现
    pendingPlainHttp?.let { plainHost ->
        AlertDialog(
            onDismissRequest = { pendingPlainHttp = null },
            title = { Text("使用不加密连接？", fontWeight = FontWeight.Bold) },
            text = {
                Text("地址 \"$plainHost\" 使用 http:// 明文连接：登录密码和通信 token 将不经加密直接在网络上传输，可能被截获。仅建议在内网或自建服务器时使用。")
            },
            confirmButton = {
                Button(onClick = {
                    pendingPlainHttp = null
                    isLoading = true
                    errorMessage = ""
                    doLogin()
                }) {
                    Text("仍要使用 http")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPlainHttp = null }) {
                    Text("改用 https（去掉 http:// 前缀）")
                }
            }
        )
    }
}
