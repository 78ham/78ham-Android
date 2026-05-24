package com.ham78.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.data.ServerConfig
import com.ham78.app.network.MessageStore
import com.ham78.app.network.ServerConnection
import com.ham78.app.service.TalkService
import com.ham78.app.ui.screens.*
import com.ham78.app.ui.theme.*
import kotlinx.coroutines.launch

// 底部导航项
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

/**
 * 主应用界面
 * 底部导航 + 浮动 PTT 按钮 + 顶部状态栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    talkService: TalkService,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            BottomNavItem.Servers,
            BottomNavItem.Channels,
            BottomNavItem.Messages,
            BottomNavItem.Settings
        )
    }

    // 服务状态
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

    // 频道列表
    var roomList by remember { mutableStateOf(listOf<com.ham78.app.network.ApiClient.RoomInfo>()) }

    // 轮询状态
    LaunchedEffect(Unit) {
        while (true) {
            isTransmitting = talkService.isTransmitting()
            isReceiving = talkService.isReceiving()
            kotlinx.coroutines.delay(200)
        }
    }

    // 登录后加载频道列表
    LaunchedEffect(activeServer?.isLoggedIn) {
        if (activeServer?.isLoggedIn == true && activeServerId.isNotEmpty()) {
            val rooms = talkService.loadRoomList(activeServerId)
            roomList = rooms
        }
    }

    LaunchedEffect(activeServerId) {
        if (activeServerId.isNotEmpty()) {
            val rooms = talkService.loadRoomList(activeServerId)
            roomList = rooms
        }
    }

    // PTT 动画
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
                // 顶部状态栏
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
                                Spacer(modifier = Modifier.width(8.dp))
                                if (activeServer != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (activeServer.isOnline) ServerOnline else ServerOffline)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
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
                        // 发射/接收状态
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
                // 底部导航
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
                                            is BottomNavItem.Messages -> {
                                                // 未读消息标记（可扩展）
                                            }
                                            is BottomNavItem.Servers -> {
                                                val onlineCount = serverConnections.count { it.isOnline }
                                                if (onlineCount > 0) {
                                                    Badge {
                                                        Text("$onlineCount")
                                                    }
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
            // 内容区域
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    0 -> ServerScreen(
                        serverConnections = serverConnections,
                        savedServers = loadSavedServers(context),
                        activeServerId = activeServerId,
                        onConnect = { config ->
                            scope.launch {
                                talkService.connectToServer(config)
                            }
                        },
                        onDisconnect = { serverId ->
                            talkService.disconnectFromServer(serverId)
                        },
                        onSwitchActive = { serverId ->
                            talkService.switchActiveServer(serverId)
                        },
                        onAddServer = { config ->
                            // 保存服务器配置
                            val repo = com.ham78.app.data.SettingsRepository(context)
                            repo.addServer(config)
                            // 自动连接
                            scope.launch {
                                talkService.connectToServer(config)
                            }
                        },
                        onRemoveServer = { serverId ->
                            // 从保存列表移除
                            val repo = com.ham78.app.data.SettingsRepository(context)
                            repo.removeServer(serverId)
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
                            scope.launch {
                                talkService.uploadLocationToActive()
                            }
                        }
                    )

                    3 -> SettingsScreen()
                }
            }
        }

        // 浮动 PTT 按钮（覆盖在底部导航上方）
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
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!isConnected) continue

                                val started = talkService.startTransmitting()
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
                    Spacer(modifier = Modifier.width(8.dp))
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

/**
 * 加载已保存的服务器列表
 */
private fun loadSavedServers(context: android.content.Context): List<ServerConfig> {
    val repo = com.ham78.app.data.SettingsRepository(context)
    return repo.loadServerList()
}
