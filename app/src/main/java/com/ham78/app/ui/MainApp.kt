package com.ham78.app.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.ApiClient
import com.ham78.app.network.DevModel
import com.ham78.app.network.ServerConnection
import com.ham78.app.service.TalkService
import com.ham78.app.ui.screens.ChannelPickerSheet
import com.ham78.app.ui.screens.ChannelScreen
import com.ham78.app.ui.screens.PttScreen
import com.ham78.app.ui.screens.SpeakingBar
import com.ham78.app.ui.screens.ServerScreen
import com.ham78.app.ui.screens.SettingsScreen
import com.ham78.app.ui.screens.VoiceWaveformBars
import com.ham78.app.ui.theme.Background
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Disconnected
import com.ham78.app.ui.theme.PttTransmitting
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.ham78.app.ui.screens.DeviceAtScreen
import com.ham78.app.ui.screens.DeviceSettingsScreen
import com.ham78.app.ui.screens.GroupDetailScreen
import com.ham78.app.ui.screens.MonitorScreen
import com.ham78.app.ui.screens.MyDevicesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class MainExtra {
    data object Devices : MainExtra()
    data object Monitor : MainExtra()
    data class Group(val groupId: Int, val name: String) : MainExtra()
    data class At(val device: ApiClient.ManagedDevice) : MainExtra()
    data class DeviceSettings(val device: ApiClient.ManagedDevice) : MainExtra()
}

private const val LOBBY_NAME = "公共大厅"

/** 顶栏标题：在线时是正在通话的频道，否则告诉用户还没连上 */
private fun tunedChannelName(server: ServerConnection?): String = when {
    server == null || !server.isOnline -> "未连接"
    server.currentRoomId > 0 -> server.currentGroupName.ifEmpty { "频道 ${server.currentRoomId}" }
    else -> LOBBY_NAME
}

sealed class BottomNavItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Ptt : BottomNavItem("对讲", Icons.Filled.Mic, Icons.Outlined.Mic)
    object Channels : BottomNavItem("频道", Icons.Filled.Groups, Icons.Outlined.Groups)
    object Servers : BottomNavItem("服务器", Icons.Filled.Storage, Icons.Outlined.Storage)
}

/**
 * 主界面。
 * 顶栏始终是当前频道，点它就地换台；发射/接收只在顶栏标一次。
 * 对讲页用圆形按键。频道和服务器页留一条可按住的细条，换台时按键还在拇指附近。
 * 设置不放进底栏，它不是每次通话都要打开的地方。
 */
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
    var showSettings by remember { mutableStateOf(false) }
    var showChannelSheet by remember { mutableStateOf(false) }
    var showOnlineSheet by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf<List<MainExtra>>(emptyList()) }
    val topExtra = extra.lastOrNull()
    var avatarTick by remember { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            BottomNavItem.Ptt,
            BottomNavItem.Channels,
            BottomNavItem.Servers
        )
    }

    val serverConnections by talkService.serverConnections.collectAsState()
    val activeServerId by talkService.activeServerId.collectAsState()
    val isTransmitting by talkService.transmittingState.collectAsState()
    val liveSpeakers by talkService.liveSpeakers.collectAsState()
    val listenRooms by talkService.listenRooms.collectAsState()

    val activeServer = remember(serverConnections, activeServerId) {
        serverConnections.find { it.serverId == activeServerId }
    }

    val isConnected = remember(activeServer) {
        activeServer?.isOnline == true
    }
    val localAvatar = remember(avatarTick) { avatarFile(context).takeIf { it.exists() && it.length() > 0 } }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val host = activeServer?.serverHost
        val user = activeServer?.userInfo
        scope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { compressJpeg(context, uri, avatarFile(context)) }
            } catch (_: Exception) {
                null
            }
            if (saved != null && !host.isNullOrEmpty() && user != null) {
                val dataUri = withContext(Dispatchers.IO) {
                    val bytes = saved.readBytes()
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
                ApiClient.updateAvatar(host, user, dataUri)
            }
            avatarTick++
        }
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

    // 同时收听的频道：对讲页只在真有收听时露一行，点一下回到频道页管理
    val speakingBars = remember(liveSpeakers, serverConnections, roomLists) {
        liveSpeakers.values.map { speaker ->
            val conn = serverConnections.find { it.serverId == speaker.serverId }
            val roomName = when {
                speaker.roomId <= 0 -> LOBBY_NAME
                speaker.roomId == conn?.currentRoomId && conn.currentGroupName.isNotEmpty() ->
                    conn.currentGroupName
                else -> roomLists[speaker.serverId]?.find { it.id == speaker.roomId }?.name
                    ?: "频道 ${speaker.roomId}"
            }
            val server = if (serverConnections.size > 1 && conn != null) "${conn.name} · " else ""
            SpeakingBar(
                key = speaker.serverId,
                title = "$server$roomName · ${DevModel.who(speaker.callsign, speaker.ssid, speaker.devModel)}",
                durationMs = speaker.durationMs,
                opus = speaker.opus,
                dmrId = speaker.dmrId,
                mdcLabel = speaker.mdcLabel
            )
        }
    }
    val speakerLines = speakingBars.map { it.title }

    val listenLines = remember(listenRooms, serverConnections, roomLists) {
        listenRooms.map { (serverId, roomId) ->
            val serverName = serverConnections.find { it.serverId == serverId }?.name?.ifBlank { null }
                ?: "服务器"
            val roomName = roomLists[serverId]?.find { it.id == roomId }?.name ?: "频道 $roomId"
            "$serverName · $roomName"
        }
    }

    // 0 号是公共大厅，频道接口不一定把它列出来；补一行才能从这里回到大厅
    val pickerRooms = remember(roomLists, activeServerId) {
        val list = roomLists[activeServerId].orEmpty()
        if (list.any { it.id == 0 }) list
        else listOf(ApiClient.RoomInfo(id = 0, name = LOBBY_NAME)) + list
    }

    fun openChannelSheet() {
        val server = activeServer
        if (server == null || !server.isOnline) {
            showSettings = false
            selectedTab = 2
            return
        }
        if (server.serverId !in roomLists) refreshRoomList(server.serverId)
        showChannelSheet = true
    }

    val overlayTitle = when (topExtra) {
        MainExtra.Devices -> "我的设备"
        MainExtra.Monitor -> "实时监听"
        is MainExtra.Group -> "群组详情"
        is MainExtra.At -> "AT 指令"
        is MainExtra.DeviceSettings -> "设备设置"
        null -> if (showSettings) "设置" else null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Background,
            topBar = {
                val onAir = isTransmitting || speakerLines.isNotEmpty()
                val onAirColor = if (isTransmitting) PttTransmitting else BrandPurple
                TopAppBar(
                    navigationIcon = {
                        if (overlayTitle != null) {
                            IconButton(onClick = {
                                if (extra.isNotEmpty()) extra = extra.dropLast(1)
                                else showSettings = false
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = TextPrimary
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                extra = emptyList()
                                showSettings = true
                            }) {
                                UserAvatar(
                                    url = activeServer?.userInfo?.avatar,
                                    localFile = localAvatar,
                                    size = 32.dp
                                )
                            }
                        }
                    },
                    title = {
                        if (overlayTitle != null) {
                            Text(
                                text = overlayTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClickLabel = "切换频道") { openChannelSheet() }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tunedChannelName(activeServer),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Icon(
                                        Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (speakerLines.isNotEmpty()) {
                                    Text(
                                        text = if (speakerLines.size == 1) speakerLines.first()
                                        else "${speakerLines.first()}  等${speakerLines.size}个房间",
                                        fontSize = 11.sp,
                                        color = BrandPurple,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else if (activeServer?.isOnline == true) {
                                    Text(
                                        text = buildString {
                                            append(activeServer.name)
                                            if (activeServer.callsign.isNotEmpty()) {
                                                append(" · ")
                                                append(activeServer.callsign)
                                            }
                                        },
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (overlayTitle == null && activeServer?.isOnline == true) {
                            TextButton(onClick = { showOnlineSheet = true }) {
                                Text("${activeServer.onlineCount} 在线", fontSize = 12.sp, color = BrandPurple)
                            }
                        }
                        if (overlayTitle == null && onAir) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = onAirColor.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        if (isTransmitting) "TX" else "RX",
                                        fontSize = 11.sp,
                                        color = onAirColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                    VoiceWaveformBars(isPlaying = true, color = onAirColor)
                                }
                            }
                        }
                        if (overlayTitle == null) {
                            IconButton(onClick = {
                                extra = emptyList()
                                showSettings = true
                            }) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = "设置",
                                    tint = TextSecondary
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                ) {
                    // 对讲页自己有圆形按键。其它页留一条细条，换频道时不用先切回对讲页才能说话
                    if (selectedTab != 0 || overlayTitle != null) {
                        PttControlBar(
                            isConnected = isConnected,
                            isTransmitting = isTransmitting,
                            channelName = tunedChannelName(activeServer),
                            onPress = { talkService.startTransmitting() },
                            onRelease = { talkService.stopTransmitting() }
                        )
                    }

                    NavigationBar(
                        containerColor = Surface,
                        tonalElevation = 0.dp
                    ) {
                        tabs.forEachIndexed { index, item ->
                            val selected = overlayTitle == null && selectedTab == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    showSettings = false
                                    extra = emptyList()
                                    selectedTab = index
                                },
                                icon = {
                                    Icon(
                                        if (selected) item.selectedIcon else item.unselectedIcon,
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
                val roomNames = roomLists[activeServerId].orEmpty().associate { it.id to it.name } + (0 to LOBBY_NAME)
                when {
                    topExtra is MainExtra.Devices -> MyDevicesScreen(
                        serverHost = activeServer?.serverHost.orEmpty(),
                        roomNames = roomNames,
                        onOpenAt = { extra = extra + MainExtra.At(it) },
                        onOpenSettings = { extra = extra + MainExtra.DeviceSettings(it) }
                    )
                    topExtra is MainExtra.At -> DeviceAtScreen(
                        serverHost = activeServer?.serverHost.orEmpty(),
                        seed = topExtra.device
                    )
                    topExtra is MainExtra.DeviceSettings -> DeviceSettingsScreen(
                        serverHost = activeServer?.serverHost.orEmpty(),
                        seed = topExtra.device
                    )
                    topExtra is MainExtra.Group -> GroupDetailScreen(
                        serverHost = activeServer?.serverHost.orEmpty(),
                        groupId = topExtra.groupId,
                        groupName = topExtra.name
                    )
                    topExtra is MainExtra.Monitor -> MonitorScreen(
                        serverHost = activeServer?.serverHost.orEmpty(),
                        token = activeServer?.serverHost?.let { ApiClient.getTokenForServer(it) }.orEmpty(),
                        callsign = activeServer?.callsign.orEmpty(),
                        currentGroupId = activeServer?.currentRoomId ?: 0,
                        onJoin = { roomId ->
                            val serverId = activeServer?.serverId.orEmpty()
                            if (serverId.isNotEmpty()) {
                                scope.launch { talkService.joinRoom(serverId, roomId) }
                            }
                        }
                    )
                    showSettings -> SettingsScreen(
                        onLogout = onLogout,
                        avatarUrl = activeServer?.userInfo?.avatar.orEmpty(),
                        localAvatar = localAvatar,
                        onChangeAvatar = { avatarPicker.launch("image/*") },
                        onOpenDevices = {
                            showSettings = false
                            extra = listOf(MainExtra.Devices)
                        },
                        onOpenGroup = {
                            val server = activeServer ?: return@SettingsScreen
                            showSettings = false
                            extra = listOf(MainExtra.Group(server.currentRoomId, tunedChannelName(server)))
                        },
                        onOpenMonitor = {
                            showSettings = false
                            extra = listOf(MainExtra.Monitor)
                        }
                    )
                    selectedTab == 0 -> {
                        // 仅在对讲页收集高频变化的状态（每条新消息都会变），
                        // 避免顶层收集导致整个 Scaffold/底栏随之重组
                        val textMessages by talkService.textMessages.collectAsState()
                        val playingVoiceClipId by talkService.playingVoiceClipId.collectAsState()
                        PttScreen(
                            activeServer = activeServer,
                            isTransmitting = isTransmitting,
                            messages = textMessages,
                            onSendMessage = { text ->
                                scope.launch { talkService.sendTextMessageToActive(text) }
                            },
                            onSendLocation = {
                                scope.launch { talkService.uploadLocationToActive() }
                            },
                            onPressPtt = { talkService.startTransmitting() },
                            onReleasePtt = { talkService.stopTransmitting() },
                            listenLines = listenLines,
                            speakingBars = speakingBars,
                            onOpenListen = {
                                showSettings = false
                                selectedTab = 1
                            },
                            onReplayVoice = { clipId ->
                                talkService.replayVoice(clipId)
                            },
                            playingVoiceClipId = playingVoiceClipId,
                            talkService = talkService
                        )
                    }

                    selectedTab == 1 -> ChannelScreen(
                        serverConnections = serverConnections,
                        roomLists = roomLists,
                        listenRooms = listenRooms,
                        onJoinRoom = { serverId, roomId ->
                            scope.launch { talkService.joinRoom(serverId, roomId) }
                        },
                        onListen = { serverId, roomId ->
                            scope.launch { talkService.listenRoom(serverId, roomId) }
                        },
                        onStopListen = { serverId ->
                            talkService.stopListening(serverId)
                        },
                        onRefresh = { serverId ->
                            refreshRoomList(serverId)
                        },
                        liveSpeakers = liveSpeakers.values.toList(),
                        onOpenGroup = { connection ->
                            extra = listOf(
                                MainExtra.Group(
                                    connection.currentRoomId,
                                    connection.currentGroupName.ifEmpty { LOBBY_NAME }
                                )
                            )
                        },
                        onTuned = {
                            showSettings = false
                            selectedTab = 0
                        }
                    )

                    selectedTab == 2 -> ServerScreen(
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
                }
            }
        }

        if (showOnlineSheet && activeServer != null) {
            val devices = activeServer.onlineDevices
            ModalBottomSheet(
                onDismissRequest = { showOnlineSheet = false },
                containerColor = Surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        text = tunedChannelName(activeServer),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "${devices.size} 台在线",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    if (devices.isEmpty()) {
                        Text("这个频道现在没有在线设备", color = TextSecondary, fontSize = 14.sp)
                    } else {
                        devices.forEach { device ->
                            Text(
                                text = buildString {
                                    append(DevModel.who(device.callsign, device.ssid, device.devModel))
                                    if (device.dmrId != 0) append("    DMR ${device.dmrId}")
                                },
                                color = TextPrimary,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                    TextButton(onClick = {
                        showOnlineSheet = false
                        extra = listOf(MainExtra.Group(activeServer.currentRoomId, tunedChannelName(activeServer)))
                    }) {
                        Text("群组详情", color = BrandPurple)
                    }
                }
            }
        }

        if (showChannelSheet && activeServer != null) {
            ChannelPickerSheet(
                serverName = activeServer.name,
                rooms = pickerRooms,
                currentRoomId = activeServer.currentRoomId,
                isLoading = activeServer.serverId !in roomLists,
                onDismiss = { showChannelSheet = false },
                onPick = { roomId ->
                    val serverId = activeServer.serverId
                    val alreadyHere = roomId == activeServer.currentRoomId
                    showChannelSheet = false
                    if (!alreadyHere && serverId.isNotEmpty()) {
                        scope.launch { talkService.joinRoom(serverId, roomId) }
                    }
                },
                onOpenAll = {
                    showChannelSheet = false
                    showSettings = false
                    selectedTab = 1
                }
            )
        }
    }
}

/**
 * 脉冲缩放动画：仅在 PTT 发射时启动。
 * 无条件运行的无限动画会让整个 MainApp 每帧重组（60fps 耗电大户），
 * 放到最小重组作用域并按需创建，空闲时零动画开销。
 */
@Composable
internal fun rememberPulseScale(active: Boolean): Float {
    // 动画时钟单独放进一个组合：空闲时不创建，发射时才跑，避免整页 60fps 重组
    return if (active) pulsingScale() else 1f
}

@Composable
private fun pulsingScale(): Float {
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

/**
 * 按住才发射，松手一定停。对讲页的圆键和其它页的细条共用这一套，避免两处各写一次松手逻辑。
 */
internal fun Modifier.holdToTalk(
    enabled: Boolean,
    haptic: HapticFeedback,
    onPress: () -> Unit,
    onRelease: () -> Unit
): Modifier = pointerInput(enabled) {
    awaitPointerEventScope {
        while (true) {
            awaitFirstDown(requireUnconsumed = false)
            if (!enabled) {
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
}

@Composable
private fun PttControlBar(
    isConnected: Boolean,
    isTransmitting: Boolean,
    channelName: String,
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
                .holdToTalk(isConnected, haptic, onPress, onRelease),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isConnected) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "PTT",
                    modifier = Modifier.size(if (isImeVisible) 18.dp else 22.dp),
                    tint = TextOnPrimary
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isTransmitting -> "松开停止"
                        isConnected -> if (isImeVisible) "按住说话" else "$channelName · 按住说话"
                        else -> "未连接"
                    },
                    color = TextOnPrimary,
                    fontSize = if (isImeVisible) 13.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
