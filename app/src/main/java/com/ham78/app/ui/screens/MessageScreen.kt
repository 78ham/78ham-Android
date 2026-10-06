package com.ham78.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.MessageStore
import com.ham78.app.network.ServerConnection
import com.ham78.app.ui.theme.BrandCyan
import com.ham78.app.ui.theme.BrandPink
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.ServerOffline
import com.ham78.app.ui.theme.ServerOnline
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary

@Composable
fun MessageScreen(
    messages: List<MessageStore.TextMessage>,
    activeServer: ServerConnection?,
    isConnected: Boolean,
    onSendMessage: (String) -> Unit,
    onSendLocation: () -> Unit,
    onReplayVoice: (String) -> Unit = {},
    playingVoiceClipId: String? = null
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "消息",
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
                                color = if (activeServer.isOnline) ServerOnline else ServerOffline
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

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
                            text = "还没有消息",
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "按住PTT说话，或输入文字发送",
                            fontSize = 12.sp,
                            color = TextSecondary.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
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
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }

            if (activeServer != null && isConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                .clip(CircleShape)
                                .background(
                                    if (inputText.isNotBlank()) BrandPurple
                                    else BrandPurple.copy(alpha = 0.3f)
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = TextOnPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageStore.TextMessage,
    onReplayVoice: (String) -> Unit = {},
    playingVoiceClipId: String? = null
) {
    val isPlayableVoice = message.type == MessageStore.MessageType.VOICE &&
        message.voiceClipId.isNotEmpty()
    val isPlayingThisVoice = isPlayableVoice &&
        playingVoiceClipId != null &&
        playingVoiceClipId == message.voiceClipId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (message.isSelf) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isSelf) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when (message.type) {
                            MessageStore.MessageType.VOICE -> BrandPink.copy(alpha = 0.3f)
                            MessageStore.MessageType.LOCATION -> BrandCyan.copy(alpha = 0.3f)
                            else -> BrandPurple.copy(alpha = 0.3f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.callsign.firstOrNull()?.toString() ?: "?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = if (message.type == MessageStore.MessageType.LOCATION) 300.dp else 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (message.isSelf) 14.dp else 4.dp,
                        topEnd = if (message.isSelf) 4.dp else 14.dp,
                        bottomStart = 14.dp,
                        bottomEnd = 14.dp
                    )
                )
                .background(
                    if (message.isSelf) BrandPurple.copy(alpha = 0.85f)
                    else SurfaceCard
                )
                .then(
                    if (isPlayableVoice) Modifier.clickable { onReplayVoice(message.voiceClipId) }
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!message.isSelf) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${message.callsign}-${message.ssid}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandPurple
                    )
                    if (message.serverName.isNotEmpty()) {
                        Text(
                            text = message.serverName,
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            when (message.type) {
                MessageStore.MessageType.VOICE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPlayingThisVoice) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlayingThisVoice) "暂停" else "回放语音",
                            tint = if (message.isSelf) TextOnPrimary else BrandPurple,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = message.content,
                            color = if (message.isSelf) TextOnPrimary else TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        VoiceWaveformBars(
                            isPlaying = isPlayingThisVoice,
                            color = if (message.isSelf) TextOnPrimary else BrandPurple
                        )
                    }
                }
                MessageStore.MessageType.LOCATION -> {
                    LocationBubbleContent(
                        content = message.content,
                        isSelf = message.isSelf
                    )
                }
                else -> {
                    Text(
                        text = message.content,
                        color = if (message.isSelf) TextOnPrimary else TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }

            Text(
                text = message.timestamp,
                fontSize = 10.sp,
                color = if (message.isSelf) TextOnPrimary.copy(alpha = 0.6f) else TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

/**
 * 语音跳动声波波形小组件
 */
@Composable
fun VoiceWaveformBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "voiceWave")
    val h1 by transition.animateFloat(
        initialValue = 4f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )
    val h2 by transition.animateFloat(
        initialValue = 12f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(340, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )
    val h3 by transition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(240, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h3"
    )

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bars = if (isPlaying) listOf(h1, h2, h3) else listOf(5f, 9f, 6f)
        bars.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color.copy(alpha = if (isPlaying) 0.9f else 0.4f))
            )
        }
    }
}

/**
 * 位置卡片气泡内容：坐标高亮展示 + 快速复制 + 唤起外部地图
 */
@Composable
fun LocationBubbleContent(
    content: String,
    isSelf: Boolean
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // 正则提取浮点数经纬度
    val regex = Regex("(-?\\d+(?:\\.\\d+)?)[,\\s，]+(-?\\d+(?:\\.\\d+)?)")
    val match = regex.find(content)
    val lat = match?.groupValues?.getOrNull(1)
    val lng = match?.groupValues?.getOrNull(2)

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = null,
                tint = if (isSelf) TextOnPrimary else BrandCyan,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "位置分享",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelf) TextOnPrimary else BrandCyan
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isSelf) Color.Black.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (lat != null && lng != null) {
                    Text(
                        text = "纬度: $lat",
                        fontSize = 12.sp,
                        color = if (isSelf) TextOnPrimary else TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "经度: $lng",
                        fontSize = 12.sp,
                        color = if (isSelf) TextOnPrimary else TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = content,
                        fontSize = 13.sp,
                        color = if (isSelf) TextOnPrimary else TextPrimary
                    )
                }
            }
        }

        if (lat != null && lng != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 复制坐标
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = (if (isSelf) TextOnPrimary else BrandPurple).copy(alpha = 0.15f),
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString("$lat, $lng"))
                        Toast.makeText(context, "坐标已复制: $lat, $lng", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制",
                            tint = if (isSelf) TextOnPrimary else BrandPurple,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "复制",
                            fontSize = 11.sp,
                            color = if (isSelf) TextOnPrimary else BrandPurple,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 打开外部地图
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = (if (isSelf) TextOnPrimary else BrandCyan).copy(alpha = 0.15f),
                    modifier = Modifier.clickable {
                        try {
                            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(业余电台位置)")
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(mapIntent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "未找到可用地图应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Filled.Map,
                            contentDescription = "地图",
                            tint = if (isSelf) TextOnPrimary else BrandCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "导航",
                            fontSize = 11.sp,
                            color = if (isSelf) TextOnPrimary else BrandCyan,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
