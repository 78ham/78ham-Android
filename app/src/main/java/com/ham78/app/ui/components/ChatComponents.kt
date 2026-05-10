package com.ham78.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 聊天消息项
 */
@Composable
fun ChatBubble(
    callsign: String,
    ssid: Int,
    content: String,
    timestamp: String,
    isSelf: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    ) {
        if (!isSelf) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2196F3)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = callsign.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelf) Color(0xFF2196F3) else Color.White
                )
                .padding(12.dp)
                .widthIn(max = 250.dp)
        ) {
            if (!isSelf) {
                Text(
                    text = "$callsign-$ssid",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
            }

            Text(
                text = content,
                color = if (isSelf) Color.White else Color.Black,
                fontSize = 14.sp
            )

            Text(
                text = timestamp,
                fontSize = 10.sp,
                color = if (isSelf) Color.White.copy(alpha = 0.7f) else Color.Gray
            )
        }
    }
}

/**
 * 消息列表组件
 */
@Composable
fun MessageList(
    messages: List<ChatMessageItem>,
    modifier: Modifier = Modifier,
    onMessageClick: (ChatMessageItem) -> Unit = {}
) {
    LazyColumn(
        modifier = modifier,
        reverseLayout = true
    ) {
        items(
            items = messages.reversed(),
            key = { it.id }
        ) { msg: ChatMessageItem ->
            ChatBubble(
                callsign = msg.callsign,
                ssid = msg.ssid,
                content = msg.content,
                timestamp = msg.timestamp,
                isSelf = msg.isSelf,
                modifier = Modifier.clickable { onMessageClick(msg) }
            )
        }
    }
}

/**
 * 消息数据结构
 */
data class ChatMessageItem(
    val id: String,
    val callsign: String,
    val ssid: Int,
    val content: String,
    val timestamp: String,
    val isSelf: Boolean = false,
    val type: MessageType = MessageType.VOICE
)

enum class MessageType {
    VOICE, TEXT, LOCATION
}

/**
 * 频道选择对话框
 */
@Composable
fun RoomPickerDialog(
    rooms: List<RoomItem>,
    currentRoomId: Int,
    onDismiss: () -> Unit,
    onRoomSelected: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择频道", fontWeight = FontWeight.Bold)
        },
        text = {
            if (rooms.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(
                        items = rooms,
                        key = { it.id }
                    ) { room: RoomItem ->
                        val isSelected: Boolean = room.id == currentRoomId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRoomSelected(room.id) }
                                .background(
                                    if (isSelected) Color(0xFFE3F2FD) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = room.name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black
                                )
                                Text(
                                    text = "ID: ${room.id}",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 频道数据
 */
data class RoomItem(
    val id: Int,
    val name: String,
    val memberCount: Int = 0
)

/**
 * PTT 按钮组件
 */
@Composable
fun PttButton(
    isTransmitting: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(80.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isTransmitting) Color.Red else Color(0xFF2196F3),
            disabledContainerColor = Color.Gray
        ),
        enabled = isEnabled,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isTransmitting) 8.dp else 4.dp,
            pressedElevation = 2.dp
        )
    ) {
        Icon(
            Icons.Filled.Call,
            contentDescription = "PTT",
            modifier = Modifier.size(40.dp),
            tint = Color.White
        )
    }
}

/**
 * 连接按钮组件
 */
@Composable
fun ConnectButton(
    isConnected: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(80.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isConnected) Color(0xFF4CAF50) else Color.Gray
        ),
        enabled = !isConnected && !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                if (isConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                contentDescription = "连接",
                modifier = Modifier.size(40.dp),
                tint = Color.White
            )
        }
    }
}

/**
 * 状态指示器栏
 */
@Composable
fun StatusBar(
    isTransmitting: Boolean,
    isReceiving: Boolean,
    lastCallsign: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        when {
            isTransmitting -> {
                StatusBadge(
                    text = "正在发射...",
                    color = Color.Red
                )
            }
            isReceiving -> {
                StatusBadge(
                    text = "接收中: $lastCallsign",
                    color = Color(0xFF2196F3)
                )
            }
            else -> {
                StatusBadge(
                    text = "待机",
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color
) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
}

/**
 * 顶部状态栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopStatusBar(
    callsign: String,
    dmrId: Int,
    isConnected: Boolean,
    currentGroup: String,
    onlineCount: Int,
    onGroupClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "$callsign (DMR:$dmrId)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF4CAF50) else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isConnected) "已连接" else "未连接",
                        fontSize = 12.sp,
                        color = if (isConnected) Color(0xFF4CAF50) else Color.Red
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onGroupClick) {
                Icon(Icons.Filled.Groups, contentDescription = "频道")
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
            IconButton(onClick = onLogoutClick) {
                Icon(Icons.Filled.ExitToApp, contentDescription = "退出")
            }
        },
        modifier = modifier
    )
}