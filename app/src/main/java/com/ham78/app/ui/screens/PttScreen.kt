package com.ham78.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.MessageStore
import com.ham78.app.network.ServerConnection
import com.ham78.app.ui.theme.BrandCyan
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.PttTransmitting
import com.ham78.app.ui.theme.ServerOffline
import com.ham78.app.ui.theme.ServerOnline
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary

/**
 * PTT 主页面：当前选中服务器与频道状态 + 实时消息列表 + 文本输入栏。
 * 对讲按键使用全局的底部 PTT 控制条（PttControlBar）。
 * 页面与语音同时在线：同一时间只加入一个房间；
 * 在频道页选择其他服务器时，会自动切换选中服务器并退出原房间。
 */
@Composable
fun PttScreen(
    activeServer: ServerConnection?,
    isTransmitting: Boolean,
    isReceiving: Boolean,
    messages: List<MessageStore.TextMessage>,
    onSendMessage: (String) -> Unit,
    onSendLocation: () -> Unit,
    onReplayVoice: (String) -> Unit = {},
    playingVoiceClipId: String? = null
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            val lastMsg = messages.last()
            val isAtBottom = !listState.canScrollForward ||
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.size - 3
            if (lastMsg.isSelf || isAtBottom) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 标题 + 选中服务器状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PTT 对讲",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (activeServer != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (activeServer.isOnline) ServerOnline.copy(alpha = 0.15f)
                        else ServerOffline.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = activeServer.name,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = if (activeServer.isOnline) ServerOnline else ServerOffline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 选中服务器的频道状态卡片
            activeServer?.let { conn ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前频道",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = if (conn.currentRoomId > 0)
                                    conn.currentGroupName.ifEmpty { "频道 ${conn.currentRoomId}" }
                                else "未加入频道",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${conn.name} · 呼号 ${conn.callsign.ifEmpty { "--" }}",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        when {
                            isTransmitting -> StatusBadge("TX", PttTransmitting, showWave = true)
                            isReceiving -> StatusBadge("RX", BrandPurple, showWave = true)
                            else -> Text(
                                text = "待机",
                                fontSize = 12.sp,
                                color = TextSecondary.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 消息列表
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = TextSecondary.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "收到的语音和文字会显示在这里",
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "按住底部 PTT 说话，或输入文字发送",
                            fontSize = 12.sp,
                            color = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onReplayVoice = onReplayVoice,
                            playingVoiceClipId = playingVoiceClipId
                        )
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }
            }

            // 底部输入栏
            if (activeServer != null && activeServer.isOnline) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onSendLocation,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = "发送位置",
                                tint = BrandCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text("输入消息...", color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = BrandPurple
                            ),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                        )

                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onSendMessage(inputText.trim())
                                    inputText = ""
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .padding(2.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (inputText.isNotBlank()) BrandPurple else TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color, showWave: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = color,
                fontWeight = FontWeight.Bold
            )
            if (showWave) {
                VoiceWaveformBars(
                    isPlaying = true,
                    color = color
                )
            }
        }
    }
}
