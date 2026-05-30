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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.MessageStore
import com.ham78.app.network.ServerConnection
import com.ham78.app.service.TalkService
import com.ham78.app.ui.screens.ChannelScreen
import com.ham78.app.ui.screens.MessageScreen
import com.ham78.app.ui.screens.ServerScreen
import com.ham78.app.ui.screens.SettingsScreen
import com.ham78.app.ui.theme.Background
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Connected
import com.ham78.app.ui.theme.Disconnected
import com.ham78.app.ui.theme.PttTransmitting
import com.ham78.app.ui.theme.ServerOffline
import com.ham78.app.ui.theme.ServerOnline
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

sealed class BottomNavItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Servers : BottomNavItem("服务器", Icons.Filled.Storage, Icons.Outlined.Storage)
    object Channels : BottomNavItem("频道", Icons.Filled.Groups, Icons.Outlined.Groups)
    object Messages : BottomNavItem("消息", Icons.Filled.Chat, Icons.Outlined.Chat)
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

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            BottomNavItem.Servers,
            BottomNavItem.Channels,
            BottomNavItem.Messages,
            BottomNavItem.Settings
        )
    }

    val serverConnections by talkService.serverConnections.collectAsState()
    val activeServerId by talkService.activeServerId.collectAsState()
    val textMessages by talkService.textMessages.collectAsState()

    var isTransmitting by remember { mutableStateOf(false) }
    var isReceiving by remember { mutableStateOf(false) }

    val activeServer = remember(serverConnections, activeServerId) {
        serverConnections.find { it.serverId == activeServerId }
    }

    val isConnected = remember(serverConnections) {
        serverConnections.any { it.isOnline }
    }

    var roomList by remember { mutableStateOf(listOf<com.ham78.app.network.ApiClient.RoomInfo>()) }

    LaunchedEffect(activeServerId) {
        if (activeServerId.isNotEmpty()) {
            launch {
                talkService.transmittingState.collect { isTransmitting = it }
            }
            launch {
                talkService.receivingState.collect { isReceiving = it }
            }
        } else {
            isTransmitting = false
            isReceiving = false
        }
    }

    LaunchedEffect(activeServer?.isLoggedIn, activeServerId) {
        if (activeServer?.isLoggedIn == true && activeServerId.isNotEmpty()) {
            roomList = talkService.loadRoomList(activeServerId)
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        )
    )

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
                                        color = TextSecondary
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
                                Text(
                                    "TX",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    color = PttTransmitting,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (isReceiving) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BrandPurple.copy(alpha = 0.15f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    "RX",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    color = BrandPurple,
                                    fontWeight = FontWeight.Bold
                                )
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
                NavigationBar(
                    containerColor = Surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        when (item) {
                                            is BottomNavItem.Servers -> {
                                                val onlineCount = serverConnections.count { it.isOnline }
                                                if (onlineCount > 0) {
                                                    Badge { Text("$onlineCount") }
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (selectedTab == index) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.title
                                    )
                                }
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
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    0 -> ServerScreen(
                        serverConnections = serverConnections,
                        savedServers = settingsRepository.loadServerList(),
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

                    1 -> ChannelScreen(
                        activeServer = activeServer,
                        roomList = roomList,
                        currentRoomId = activeServer?.currentRoomId ?: 0,
                        onJoinRoom = { roomId ->
                            if (activeServerId.isNotEmpty()) {
                                talkService.joinRoom(activeServerId, roomId)
                            }
                        },
                        onRefresh = {
                            scope.launch {
                                if (activeServerId.isNotEmpty()) {
                                    roomList = talkService.loadRoomList(activeServerId)
                                }
                            }
                        }
                    )

                    2 -> MessageScreen(
                        messages = textMessages,
                        activeServer = activeServer,
                        isConnected = isConnected,
                        onSendMessage = { text ->
                            talkService.sendTextMessageToActive(text)
                        },
                        onSendLocation = {
                            scope.launch { talkService.uploadLocationToActive() }
                        },
                        onReplayVoice = { clipId ->
                            talkService.replayVoice(clipId)
                        }
                    )

                    3 -> SettingsScreen()
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(56.dp)
                    .scale(if (isTransmitting) pulseScale else 1f)
                    .shadow(
                        elevation = if (isConnected) 8.dp else 2.dp,
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = if (isTransmitting) PttTransmitting else BrandPurple,
                        spotColor = if (isTransmitting) PttTransmitting else BrandPurple
                    )
                    .clip(RoundedCornerShape(28.dp))
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
                                if (!isConnected) continue
                                talkService.startTransmitting()
                                waitForUpOrCancellation()
                                talkService.stopTransmitting()
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
                        modifier = Modifier.size(22.dp),
                        tint = TextOnPrimary
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isTransmitting -> "松开停止"
                            isConnected -> "按住说话"
                            else -> "未连接"
                        },
                        color = TextOnPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
