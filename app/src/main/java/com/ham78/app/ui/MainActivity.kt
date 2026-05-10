package com.ham78.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.service.TalkService
import com.ham78.app.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var talkService: TalkService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TalkService.LocalBinder
            talkService = binder.getService()
            serviceBound = true
            setContent {
                MainScreenWrapper()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            talkService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent(this, TalkService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // 在 Compose 拦截之前处理硬件按键（PTT）
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            android.util.Log.d("MainActivity", "dispatchKeyEvent: keyCode=${event.keyCode}(${android.view.KeyEvent.keyCodeToString(event.keyCode)}) action=${event.action}")
        }
        val service = talkService
        if (service != null && event != null && service.handleKeyEvent(event)) {
            android.util.Log.d("MainActivity", "KeyEvent handled by TalkService")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreenWrapper() {
        val service = talkService

        if (service == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        MainScreen(
            talkService = service,
            onLogout = {
                stopService(Intent(this, TalkService::class.java))
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    talkService: TalkService,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("ham78_settings", Context.MODE_PRIVATE)

    var callsign by remember { mutableStateOf(prefs.getString("callsign", "未知") ?: "未知") }
    var dmrId by remember { mutableStateOf(prefs.getInt("dmr_id", 0)) }
    var currentRoom by remember { mutableStateOf(0) }
    var isTransmitting by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var chatMessages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var showRoomPicker by remember { mutableStateOf(false) }
    var onlineCount by remember { mutableStateOf(0) }
    var roomList by remember { mutableStateOf(listOf<com.ham78.app.network.ApiClient.RoomInfo>()) }
    var currentGroupName by remember { mutableStateOf("") }
    var isReceiving by remember { mutableStateOf(false) }

    val connectionState by talkService.connectionState.collectAsState()
    val lastCallsign by talkService.lastReceivedCallsign.collectAsState()
    val currentRoomId by talkService.currentRoomId.collectAsState()
    val isLoggedIn by talkService.isLoggedIn.collectAsState()
    val serviceOnlineCount by talkService.onlineCount.collectAsState()
    val serviceGroupName by talkService.currentGroupName.collectAsState()

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(connectionState) {
        isConnected = connectionState.name == "CONNECTED"
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            kotlinx.coroutines.delay(500)
            talkService.loadRoomList()
        }
    }

    LaunchedEffect(currentRoomId) {
        currentRoom = currentRoomId
    }

    LaunchedEffect(serviceOnlineCount) {
        onlineCount = serviceOnlineCount
    }

    LaunchedEffect(serviceGroupName) {
        currentGroupName = serviceGroupName
    }

    LaunchedEffect(Unit) {
        while (true) {
            isTransmitting = talkService.isTransmitting()
            isReceiving = talkService.isReceiving()
            kotlinx.coroutines.delay(200)
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            talkService.roomList.collect { rooms ->
                roomList = rooms
            }
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            talkService.receivedMessages.collect { message ->
                val newMsg = ChatMessage(
                    id = System.currentTimeMillis().toString(),
                    callsign = message.callsign,
                    ssid = message.ssid,
                    content = message.content,
                    timestamp = message.timestamp,
                    isSelf = false
                )
                chatMessages = (listOf(newMsg) + chatMessages).take(100)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column {
                            Text(
                                text = callsign,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isConnected) "已连接" else "未连接",
                                fontSize = 11.sp,
                                color = if (isConnected) Connected else Disconnected
                            )
                        }
                    }
                },
                actions = {
                    if (!isConnected) {
                        IconButton(onClick = {
                            scope.launch {
                                talkService.loginAndConnect()
                            }
                        }) {
                            Icon(Icons.Filled.Call, contentDescription = "连接", tint = Connected)
                        }
                    }
                    IconButton(onClick = {
                        val intent = android.content.Intent(context, com.ham78.app.ui.SettingsActivity::class.java)
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = onLogout) {
                        Text("退出", fontSize = 12.sp)
                    }
                }
            )

            // 频道信息栏 — 简洁大字显示，方便手台操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRoomPicker = true }
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentGroupName.ifEmpty { if (currentRoom > 0) "频道 $currentRoom" else "点击选择频道" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "ID: ${if (currentRoom > 0) currentRoom else "--"}  |  在线: $onlineCount",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                Text(
                    text = "切换 >",
                    fontSize = 14.sp,
                    color = BrandPurple,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 聊天消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                reverseLayout = true
            ) {
                items(chatMessages.reversed()) { msg ->
                    ChatBubble(msg)
                }
            }

            // 发射/接收状态指示
            if (isTransmitting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Error.copy(alpha = 0.1f))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正在发射...",
                        color = Error,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isReceiving) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandPurple.copy(alpha = 0.1f))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "接收中: $lastCallsign",
                        color = BrandPurple,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // PTT 按钮 — 扁平椭圆形，方便手台操作
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .scale(if (isTransmitting) pulseScale else 1f)
                        .clip(RoundedCornerShape(32.dp))
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
                                    if (started) {
                                        isTransmitting = true
                                    }

                                    waitForUpOrCancellation()

                                    talkService.stopTransmitting()
                                    isTransmitting = false
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
                            modifier = Modifier.size(28.dp),
                            tint = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTransmitting) "松开停止" else if (isConnected) "按住说话" else "未连接",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // 频道选择对话框 — 大字体、大间距，方便手台操作
        if (showRoomPicker) {
            RoomPickerDialog(
                rooms = roomList,
                currentRoomId = currentRoom,
                onDismiss = { showRoomPicker = false },
                onRoomSelected = { roomId ->
                    currentRoom = roomId
                    talkService.joinRoom(roomId)
                    showRoomPicker = false
                }
            )
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.isSelf) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isSelf) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(BrandPurple),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.callsign.firstOrNull()?.toString() ?: "?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (message.isSelf) BrandPurple else SurfaceElevated
                )
                .padding(12.dp)
                .widthIn(max = 250.dp)
        ) {
            if (!message.isSelf) {
                Text(
                    text = "${message.callsign}-${message.ssid}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
            }

            Text(
                text = message.content,
                color = if (message.isSelf) TextPrimary else TextSecondary,
                fontSize = 14.sp
            )

            Text(
                text = message.timestamp,
                fontSize = 10.sp,
                color = if (message.isSelf) TextPrimary.copy(alpha = 0.7f) else TextSecondary
            )
        }
    }
}

@Composable
fun RoomPickerDialog(
    rooms: List<com.ham78.app.network.ApiClient.RoomInfo>,
    currentRoomId: Int,
    onDismiss: () -> Unit,
    onRoomSelected: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择频道", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            if (rooms.isEmpty()) {
                Text("加载中...", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    itemsIndexed(rooms) { index, room ->
                        val isSelected = room.id == currentRoomId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRoomSelected(room.id) }
                                .background(
                                    if (isSelected) BrandPurple.copy(alpha = 0.1f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = room.name,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) BrandPurple else TextPrimary
                                )
                                Text(
                                    text = "ID: ${room.id}  |  成员: ${room.memberCount}",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "当前",
                                    fontSize = 13.sp,
                                    color = BrandPurple,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (index < rooms.size - 1) {
                            Divider(color = Divider, thickness = 0.5.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", fontSize = 16.sp)
            }
        }
    )
}

data class ChatMessage(
    val id: String,
    val callsign: String,
    val ssid: Int,
    val content: String,
    val timestamp: String,
    val isSelf: Boolean = false
)
