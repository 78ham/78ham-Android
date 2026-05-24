package com.ham78.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.data.ServerConfig
import com.ham78.app.network.ConnectionState
import com.ham78.app.network.ServerConnection
import com.ham78.app.ui.theme.*

/**
 * 服务器管理界面
 * 显示所有服务器配置，支持添加、删除、连接、切换
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    serverConnections: List<ServerConnection>,
    savedServers: List<ServerConfig>,
    activeServerId: String,
    onConnect: (ServerConfig) -> Unit,
    onDisconnect: (String) -> Unit,
    onSwitchActive: (String) -> Unit,
    onAddServer: (ServerConfig) -> Unit,
    onRemoveServer: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = BrandPurple,
                contentColor = TextOnPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加服务器")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 标题
            Text(
                text = "服务器管理",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = "已连接 ${serverConnections.count { it.isOnline }} / ${serverConnections.size} 台服务器",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            if (serverConnections.isEmpty() && savedServers.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextSecondary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "还没有服务器",
                            fontSize = 16.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "点击 + 添加服务器",
                            fontSize = 13.sp,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 显示已连接的服务器
                    items(serverConnections) { conn ->
                        ServerConnectionCard(
                            connection = conn,
                            isActive = conn.serverId == activeServerId,
                            onSwitch = { onSwitchActive(conn.serverId) },
                            onDisconnect = { onDisconnect(conn.serverId) }
                        )
                    }

                    // 显示未连接的已保存服务器
                    val unconnected = savedServers.filter { config ->
                        serverConnections.none { it.serverId == config.id || it.serverHost == config.host }
                    }
                    items(unconnected) { config ->
                        ServerConfigCard(
                            config = config,
                            onConnect = { onConnect(config) },
                            onRemove = { onRemoveServer(config.id) }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }

        // 添加服务器对话框
        if (showAddDialog) {
            AddServerDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { config ->
                    onAddServer(config)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun ServerConnectionCard(
    connection: ServerConnection,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) SurfaceCard.copy(alpha = 0.9f) else SurfaceCard
        ),
        border = if (isActive) androidx.compose.foundation.BorderStroke(1.5.dp, BrandPurple.copy(alpha = 0.5f)) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSwitch() }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态指示器
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (connection.connectionState) {
                                    ConnectionState.CONNECTED -> ServerOnline
                                    ConnectionState.CONNECTING -> ServerConnecting
                                    ConnectionState.DISCONNECTED -> ServerOffline
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = connection.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${connection.serverHost}:${connection.serverPort}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandPurple.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "活跃",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                color = BrandPurple,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    IconButton(onClick = onDisconnect, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = "断开",
                            tint = Error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (connection.isOnline) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ServerInfoChip("呼号", connection.callsign.ifEmpty { "--" })
                    ServerInfoChip("频道", if (connection.currentRoomId > 0) connection.currentGroupName.ifEmpty { "${connection.currentRoomId}" } else "--")
                    ServerInfoChip("在线", "${connection.onlineCount}")
                }
            }
        }
    }
}

@Composable
fun ServerConfigCard(
    config: ServerConfig,
    onConnect: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(ServerOffline)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = config.name.ifEmpty { config.host },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    Text(
                        text = "${config.host}:${config.port}",
                        fontSize = 12.sp,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onConnect, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = "连接",
                        tint = Connected,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = Error.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ServerInfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (ServerConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("js.nrlptt.com") }
    var port by remember { mutableStateOf("60050") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务器", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("我的服务器") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(
                        ServerConfig(
                            id = "${host}:${port}",
                            name = name.ifEmpty { host },
                            host = host,
                            port = port.toIntOrNull() ?: 60050,
                            username = username,
                            password = password
                        )
                    )
                },
                enabled = host.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
