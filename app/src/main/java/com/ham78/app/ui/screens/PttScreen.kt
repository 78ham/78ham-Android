package com.ham78.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import com.ham78.app.ui.holdToTalk
import com.ham78.app.ui.rememberPulseScale
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Map
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.ham78.app.network.DevModel
import com.ham78.app.network.MessageStore
import com.ham78.app.network.ServerConnection
import com.ham78.app.service.TalkService
import com.ham78.app.ui.theme.BrandCyan
import com.ham78.app.ui.theme.BrandPink
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Disconnected
import com.ham78.app.ui.theme.PttTransmitting
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary

/**
 * 对讲页：消息记录 + 底部这一条操作。
 * 频道名和发射/接收在顶栏，这里不再重复。
 * 默认是圆形按住说话；点「文字」换成输入框，点麦克风回到按键。
 * 同一时间只加入一个房间；在频道页点其它服务器的频道会切过去并回到本页。
 */
@Composable
fun PttScreen(
    activeServer: ServerConnection?,
    isTransmitting: Boolean,
    messages: List<MessageStore.TextMessage>,
    onSendMessage: (String) -> Unit,
    onSendLocation: () -> Unit,
    onPressPtt: () -> Unit,
    onReleasePtt: () -> Unit,
    listenLines: List<String> = emptyList(),
    speakingBars: List<SpeakingBar> = emptyList(),
    onOpenListen: () -> Unit = {},
    onReplayVoice: (String) -> Unit = {},
    playingVoiceClipId: String? = null,
    talkService: TalkService? = null   // 发送工具面板需要；测试环境可为空
) {
    var inputText by remember { mutableStateOf("") }
    var voiceMode by rememberSaveable { mutableStateOf(true) }
    val online = activeServer?.isOnline == true

    // 发送工具面板的开关状态：必须声明在顶层。
    // 之前声明在底部输入栏 Row 的 lambda 里，那个 lambda 每次重组都会重新执行，
    // 面板刚打开就被重置成 false（表现为「点开就闪退／关不掉」）。
    var showTools by remember { mutableStateOf(false) }

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
            speakingBars.forEach { bar ->
                LiveVoiceBar(bar = bar)
            }
            if (listenLines.isNotEmpty()) {
                Text(
                    text = "同时收听  ${listenLines.joinToString("  ·  ")}",
                    fontSize = 12.sp,
                    color = BrandPurple,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenListen)
                        .padding(top = 8.dp, bottom = 4.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

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
                            text = if (online) "收到的语音和文字会显示在这里" else "还没有连上服务器",
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = if (online) "按住下方按钮说话，或切到文字" else "到「服务器」页连接一台",
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

            TalkComposer(
                online = online,
                voiceMode = voiceMode,
                onVoiceModeChange = { voiceMode = it },
                isTransmitting = isTransmitting,
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                onSendLocation = onSendLocation,
                onOpenTools = { showTools = true },
                onPressPtt = onPressPtt,
                onReleasePtt = onReleasePtt
            )
            if (showTools) {
                talkService?.let { svc ->
                    TransmitToolsDialog(
                        talkService = svc,
                        onDismiss = { showTools = false }
                    )
                }
            }
        }
    }
}

/**
 * 对讲页底部只有一条：要么按住说话，要么打字。两套叠在一起会把消息列表挤没。
 */
@Composable
private fun TalkComposer(
    online: Boolean,
    voiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    isTransmitting: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendLocation: () -> Unit,
    onOpenTools: () -> Unit,
    onPressPtt: () -> Unit,
    onReleasePtt: () -> Unit
) {
    if (online && !voiceMode) {
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
                    onClick = { onVoiceModeChange(true) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "切换到按住说话",
                        tint = BrandPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = onOpenTools,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Apps,
                        contentDescription = "发送工具",
                        tint = BrandPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }
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
                    onValueChange = onInputChange,
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
                    onClick = onSend,
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
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (online) {
                IconButton(onClick = onOpenTools, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.Apps,
                        contentDescription = "发送工具",
                        tint = BrandPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            RoundPttButton(
                isConnected = online,
                isTransmitting = isTransmitting,
                onPress = onPressPtt,
                onRelease = onReleasePtt
            )
            if (online) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onSendLocation, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = "发送位置",
                        tint = BrandCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        if (online) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onVoiceModeChange(false) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("文字", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun RoundPttButton(
    isConnected: Boolean,
    isTransmitting: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val pulseScale = rememberPulseScale(isTransmitting)
    val label = when {
        isTransmitting -> "松开停止"
        isConnected -> "按住说话"
        else -> "未连接"
    }
    Box(
        modifier = Modifier
            .size(104.dp)
            .scale(if (isTransmitting) pulseScale else 1f)
            .shadow(
                elevation = if (isConnected) 10.dp else 2.dp,
                shape = CircleShape,
                ambientColor = if (isTransmitting) PttTransmitting else BrandPurple,
                spotColor = if (isTransmitting) PttTransmitting else BrandPurple
            )
            .clip(CircleShape)
            .background(
                when {
                    isTransmitting -> PttTransmitting
                    isConnected -> BrandPurple
                    else -> Disconnected
                }
            )
            .holdToTalk(isConnected, haptic, onPress, onRelease),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isConnected) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = TextOnPrimary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = TextOnPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
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
                    if (message.dmrId != 0) {
                        Text(
                            text = "DMR ${message.dmrId}",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                    if (message.serverName.isNotEmpty()) {
                        Text(
                            text = message.serverName,
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.5f)
                        )
                    }
                }
                if (!message.mdcLabel.isNullOrEmpty()) {
                    Text(
                        text = message.mdcLabel,
                        fontSize = 10.sp,
                        color = BrandPurple,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val deviceName = DevModel.name(message.devModel)
                if (deviceName.isNotEmpty()) {
                    Text(
                        text = deviceName,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            when (message.type) {
                MessageStore.MessageType.VOICE -> {
                    val seconds = message.voiceDurationMs / 1000f
                    val barWidth = (96 + seconds * 16f).coerceIn(96f, 240f).dp
                    Row(
                        modifier = Modifier.width(barWidth),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPlayingThisVoice) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlayingThisVoice) "暂停" else "回放语音",
                            tint = if (message.isSelf) TextOnPrimary else BrandPurple,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        VoiceWaveformBars(
                            isPlaying = isPlayingThisVoice,
                            color = if (message.isSelf) TextOnPrimary else BrandPurple
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (seconds > 0) "${kotlin.math.ceil(seconds.toDouble()).toInt()}″" else message.content,
                            color = if (message.isSelf) TextOnPrimary else TextPrimary,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (message.opus) "Opus" else "G.711",
                            fontSize = 10.sp,
                            color = if (message.isSelf) TextOnPrimary.copy(alpha = 0.7f) else TextSecondary
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

/** 正在接收的一条语音。时长按收到的声音累计，条的宽度跟着变长。 */
data class SpeakingBar(
    val key: String,
    val title: String,
    val durationMs: Long,
    val opus: Boolean,
    val dmrId: Int = 0,
    val mdcLabel: String = ""
)

@Composable
private fun LiveVoiceBar(bar: SpeakingBar) {
    val seconds = bar.durationMs / 1000f
    val target = (96 + seconds * 16f).coerceIn(96f, 260f).dp
    val width by animateDpAsState(target, tween(200), label = "liveVoiceWidth")
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = bar.title,
            fontSize = 11.sp,
            color = BrandPurple,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val beside = listOf(
            if (bar.dmrId != 0) "DMR ${bar.dmrId}" else "",
            bar.mdcLabel
        ).filter { it.isNotEmpty() }
        if (beside.isNotEmpty()) {
            Text(
                text = beside.joinToString("  "),
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = BrandPurple.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.7f)),
            modifier = Modifier.width(width)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VoiceWaveformBars(isPlaying = true, color = BrandPurple)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${kotlin.math.ceil(seconds.toDouble()).toInt()}″",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (bar.opus) "Opus" else "G.711",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
        Text(
            text = "正在接收",
            fontSize = 10.sp,
            color = BrandPurple,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * 语音跳动声波波形小组件
 *
 * 静止态绝不创建 rememberInfiniteTransition：消息列表可能有几百条语音气泡，
 * 每条挂一个无限动画时钟会让整个列表每帧重组（UI 卡顿的主因）。
 */
@Composable
fun VoiceWaveformBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (!isPlaying) {
        // 静止波形：直接画三根固定高度的小条，零动画开销
        Row(
            modifier = modifier.height(16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(5f, 9f, 6f).forEach { h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(color.copy(alpha = 0.4f))
                )
            }
        }
        return
    }

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
        listOf(h1, h2, h3).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color.copy(alpha = 0.9f))
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
