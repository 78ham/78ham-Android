package com.ham78.app.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.ServerConnection
import com.ham78.app.service.TalkService
import com.ham78.app.ui.screens.ChannelScreen
import com.ham78.app.ui.screens.MessageScreen
import com.ham78.app.ui.screens.PttScreen
import com.ham78.app.ui.screens.ServerScreen
import com.ham78.app.ui.screens.SettingsScreen
import com.ham78.app.ui.screens.VoiceWaveformBars
import com.ham78.app.ui.theme.Background
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Disconnected
import com.ham78.app.ui.theme.PttTransmitting
import com.ham78.app.ui.theme.ServerOffline
import com.ham78.app.ui.theme.ServerOnline
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

sealed class BottomNavItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Ptt : BottomNavItem("PTT", Icons.Filled.Mic, Icons.Outlined.Mic)
    object Channels : BottomNavItem("频道", Icons.Filled.Groups, Icons.Outlined.Groups)
    object Servers : BottomNavItem("服务器", Icons.Filled.Storage, Icons.Outlined.Storage)
    object Settings : BottomNavItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    talkService: TalkService,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsRepository = remember { com.ham78.app.data.SettingsRepository(context) }
    val settings by settingsRepository.settings.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            BottomNavItem.Ptt,
            BottomNavItem.Channels,
            BottomNavItem.Servers,
            BottomNavItem.Settings
        )
    }

    val serverConnections by talkService.serverConnections.collectAsState()
    val activeServerId by talkService.activeServerId.collectAsState()
    val textMessages by talkService.textMessages.collectAsState()
    val isTransmitting by talkService.transmittingState.collectAsState()
    val isReceiving by talkService.receivingState.collectAsState()
    val playingVoiceClipId by talkService.playingVoiceClipId.collectAsState()

    val activeServer = remember(serverConnections, activeServerId) {
        serverConnections.find { it.serverId == activeServerId }
    }

    val isConnected = remember(activeServer) {
        activeServer?.isOnline == true
    }

    // 各服务器的频道列表缓存: serverId -> 频道列表
    var roomLists by remember {
        mutableStateOf<Map<String, List<com.ham78.app.network.ApiClient.RoomInfo>>>(emptyMap())
    }
    var loadingRoomServers by remember { mutableStateOf(setOf<String>()) }

    fun refreshRoomList(serverId: String) {
        if (serverId in loadingRoomServers) return
        loadingRoomServers = loadingRoomServers + serverId
        scope.launch {
            val list = talkService.loadRoomList(serverId)
            roomLists = roomLists + (serverId to list)
            loadingRoomServers = loadingRoomServers - serverId
        }
    }

    // 为所有已登录服务器加载频道列表，并清理已断开服务器的缓存。
    // 仅在键集合实际变化时更新状态，避免每 5 秒的连接刷新触发无谓重组
    LaunchedEffect(serverConnections) {
        val connectedIds = serverConnections.map { it.serverId }.toSet()
        val staleKeys = roomLists.keys - connectedIds
        if (staleKeys.isNotEmpty()) {
            roomLists = roomLists - staleKeys
        }
        serverConnections.filter { it.isLoggedIn }.forEach { conn ->
            if (conn.serverId !in roomLists) refreshRoomList(conn.serverId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "78HAM",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandPurple
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                                if (activeServer != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (activeServer.isOnline) ServerOnline else ServerOffline)
                                    )
                                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = activeServer.name,
                                        fontSize = 13.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (activeServer?.isOnline == true) {
                                Text(
                                    text = "${activeServer.callsign} · ${activeServer.statusText}",
                                    fontSize = 11.sp,
                                    color = TextSecondary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    actions = {
                        if (isTransmitting) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PttTransmitting.copy(alpha = 0.15f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "TX",
                                        fontSize = 11.sp,
                                        color = PttTransmitting,
                                        fontWeight = FontWeight.Bold
                                    )
                                    VoiceWaveformBars(
                                        isPlaying = true,
                                        color = PttTransmitting
                                    )
                                }
                            }
                        } else if (isReceiving) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BrandPurple.copy(alpha = 0.15f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "RX",
                                        fontSize = 11.sp,
                                        color = BrandPurple,
                                        fontWeight = FontWeight.Bold
                                    )
                                    VoiceWaveformBars(
                                        isPlaying = true,
                                        color = BrandPurple
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Surface,
                        titleContentColor = TextPrimary
                    )
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                ) {
                    PttControlBar(
                        isConnected = isConnected,
                        isTransmitting = isTransmitting,
                        onPress = { talkService.startTransmitting() },
                        onRelease = { talkService.stopTransmitting() }
                    )

                    NavigationBar(
                        containerColor = Surface,
                        tonalElevation = 0.dp
                    ) {
                        tabs.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = {
                                    Icon(
                                        if (selectedTab == index) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.title
                                    )
                                },
                                label = {
                                    Text(item.title, fontSize = 11.sp)
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = BrandPurple,
                                    selectedTextColor = BrandPurple,
                                    indicatorColor = BrandPurple.copy(alpha = 0.1f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    0 -> PttScreen(
                        activeServer = activeServer,
                        isTransmitting = isTransmitting,
                        isReceiving = isReceiving,
                        messages = textMessages,
                        onSendMessage = { text ->
                            scope.launch { talkService.sendTextMessageToActive(text) }
                        },
                        onSendLocation = {
                            scope.launch { talkService.uploadLocationToActive() }
                        },
                        onReplayVoice = { clipId ->
                            talkService.replayVoice(clipId)
                        },
                        playingVoiceClipId = playingVoiceClipId
                    )

                    1 -> ChannelScreen(
                        serverConnections = serverConnections,
                        roomLists = roomLists,
                        onJoinRoom = { serverId, roomId ->
                            scope.launch { talkService.joinRoom(serverId, roomId) }
                        },
                        onRefresh = { serverId ->
                            refreshRoomList(serverId)
                        }
                    )

                    2 -> ServerScreen(
                        serverConnections = serverConnections,
                        savedServers = settings.servers,
                        activeServerId = activeServerId,
                        onConnect = { config ->
                            scope.launch { talkService.connectToServer(config) }
                        },
                        onDisconnect = { serverId ->
                            talkService.disconnectFromServer(serverId)
                        },
                        onSwitchActive = { serverId ->
                            talkService.switchActiveServer(serverId)
                        },
                        onAddServer = { config ->
                            settingsRepository.addServer(config)
                            scope.launch { talkService.connectToServer(config) }
                        },
                        onRemoveServer = { serverId ->
                            settingsRepository.removeServer(serverId)
                        }
                    )

                    3 -> SettingsScreen()
                }
            }
        }

    }
}

/**
 * 脉冲缩放动画：仅在 PTT 发射时启动。
 * 无条件运行的无限动画会让整个 MainApp 每帧重组（60fps 耗电大户），
 * 放到最小重组作用域并按需创建，空闲时零动画开销。
 */
@Composable
private fun rememberPulseScale(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "pttPulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pttPulseScale"
    )
    return scale
}

@Composable
private fun PttControlBar(
    isConnected: Boolean,
    isTransmitting: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val pulseScale = rememberPulseScale(isTransmitting)
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    // 软键盘弹起时收缩高度，避免挤占消息视口与输入法
    val barHeight = if (isImeVisible) 36.dp else 52.dp
    val verticalPadding = if (isImeVisible) 4.dp else 8.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .scale(if (isTransmitting) pulseScale else 1f)
                .shadow(
                    elevation = if (isConnected) 8.dp else 2.dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = if (isTransmitting) PttTransmitting else BrandPurple,
                    spotColor = if (isTransmitting) PttTransmitting else BrandPurple
                )
                .clip(RoundedCornerShape(26.dp))
                .background(
                    when {
                        isTransmitting -> PttTransmitting
                        isConnected -> BrandPurple
                        else -> Disconnected
                    }
                )
                .pointerInput(isConnected) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false)
                            if (!isConnected) {
                                waitForUpOrCancellation()
                                continue
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPress()
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRelease()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isTransmitting) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "PTT",
                    modifier = Modifier.size(if (isImeVisible) 18.dp else 22.dp),
                    tint = TextOnPrimary
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isTransmitting -> "松开停止"
                        isConnected -> if (isImeVisible) "按住PTT" else "按住说话"
                        else -> "未连接"
                    },
                    color = TextOnPrimary,
                    fontSize = if (isImeVisible) 13.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isTransmitting) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    VoiceWaveformBars(
                        isPlaying = true,
                        color = TextOnPrimary
                    )
                }
            }
        }
    }
}
