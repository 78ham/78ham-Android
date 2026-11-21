package com.ham78.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.ApiClient
import com.ham78.app.network.DevModel
import com.ham78.app.network.LiveSpeaker
import com.ham78.app.network.ServerConnection
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Divider
import com.ham78.app.ui.theme.ServerOffline
import com.ham78.app.ui.theme.ServerOnline
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.SurfaceElevated
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary

/**
 * 频道页：按服务器分组的频道列表，每个服务器可折叠展开。
 * 点一行就是加入这个频道并回到对讲页。旁边的「只听」是只收不发，留在本页继续选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    serverConnections: List<ServerConnection>,
    roomLists: Map<String, List<ApiClient.RoomInfo>>,
    listenRooms: Map<String, Int> = emptyMap(),
    onJoinRoom: (serverId: String, roomId: Int) -> Unit,
    onListen: (serverId: String, roomId: Int) -> Unit = { _, _ -> },
    onStopListen: (serverId: String) -> Unit = {},
    onRefresh: (serverId: String) -> Unit,
    onTuned: () -> Unit = {},
    liveSpeakers: List<LiveSpeaker> = emptyList(),
    onOpenGroup: (ServerConnection) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "频道列表",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${serverConnections.count { it.isOnline }} 台在线 · " +
                            "${roomLists.values.sumOf { it.size }} 个频道",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                IconButton(
                    onClick = { serverConnections.filter { it.isOnline }.forEach { onRefresh(it.serverId) } },
                    enabled = serverConnections.any { it.isOnline }
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新全部频道",
                        tint = if (serverConnections.any { it.isOnline }) BrandPurple
                        else TextSecondary.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索频道...", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandPurple.copy(alpha = 0.5f),
                    unfocusedBorderColor = Divider,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (serverConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextSecondary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "请先连接服务器",
                            fontSize = 16.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(serverConnections, key = { it.serverId }) { conn ->
                        ServerChannelSection(
                            connection = conn,
                            rooms = roomLists[conn.serverId] ?: emptyList(),
                            isLoading = !roomLists.containsKey(conn.serverId) && conn.isOnline,
                            searchQuery = searchQuery,
                            listeningRoomId = listenRooms[conn.serverId],
                            onJoinRoom = onJoinRoom,
                            onListen = onListen,
                            onStopListen = { onStopListen(conn.serverId) },
                            onRefresh = { onRefresh(conn.serverId) },
                            onTuned = onTuned,
                            liveSpeakers = liveSpeakers.filter { it.serverId == conn.serverId }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ServerChannelSection(
    connection: ServerConnection,
    rooms: List<ApiClient.RoomInfo>,
    isLoading: Boolean,
    searchQuery: String,
    listeningRoomId: Int? = null,   // 该服务器上收听中的房间号；null = 没有收听
    onJoinRoom: (serverId: String, roomId: Int) -> Unit,
    onListen: (serverId: String, roomId: Int) -> Unit = { _, _ -> },
    onStopListen: () -> Unit = {},
    onRefresh: () -> Unit,
    onTuned: () -> Unit = {},
    liveSpeakers: List<LiveSpeaker> = emptyList()
) {
    // 默认收起；选中房间所在的服务器组自动展开，展示"当前"标记
    var expanded by rememberSaveable(connection.serverId) { mutableStateOf(false) }

    val filteredRooms = remember(rooms, searchQuery) {
        if (searchQuery.isEmpty()) rooms
        else rooms.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.id.toString().contains(searchQuery)
        }
    }

    // 房间选择恢复/切换后，自动展开当前房间所在的服务器组
    androidx.compose.runtime.LaunchedEffect(connection.currentRoomId) {
        if (connection.currentRoomId > 0) expanded = true
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column {
            // 服务器折叠头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(20.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (connection.isOnline) ServerOnline else ServerOffline)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = connection.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (connection.isOnline) {
                                val currentRoom = rooms.find { it.id == connection.currentRoomId }
                                buildString {
                                    append("${rooms.size} 个频道")
                                    if (currentRoom != null) append(" · 当前: ${currentRoom.name}")
                                    else if (connection.currentRoomId > 0) append(" · 当前: 频道 ${connection.currentRoomId}")
                                }
                            } else {
                                "未连接"
                            },
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (connection.isOnline) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新该服务器频道",
                            tint = BrandPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(color = Divider.copy(alpha = 0.5f))

                if (connection.isOnline) {
                    Text(
                        text = "群组详情",
                        color = BrandPurple,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clickable { onOpenGroup(connection) }
                    )
                }

                if (connection.onlineDevices.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = connection.currentGroupName.ifEmpty { "当前频道" } + " · 在线设备",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        connection.onlineDevices.take(12).forEach { device ->
                            Text(
                                text = DevModel.who(device.callsign, device.ssid, device.devModel),
                                fontSize = 13.sp,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (connection.onlineDevices.size > 12) {
                            Text(
                                text = "还有 ${connection.onlineDevices.size - 12} 台",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                when {
                    !connection.isOnline -> Text(
                        text = "未连接，无法获取频道列表",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    isLoading && rooms.isEmpty() -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = BrandPurple
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("加载频道中...", fontSize = 13.sp, color = TextSecondary)
                    }
                    filteredRooms.isEmpty() -> Text(
                        text = if (searchQuery.isEmpty()) "没有频道" else "没有找到频道",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    else -> Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 收听中的服务器组提示条：一键停听
                        if (listeningRoomId != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BrandPurple.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "收听中 · 频道 $listeningRoomId",
                                        fontSize = 12.sp,
                                        color = BrandPurple,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onStopListen) {
                                        Text("停听", fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }

                        filteredRooms.forEach { room ->
                            val isCurrent = room.id == connection.currentRoomId
                            val isListening = room.id == listeningRoomId && !isCurrent
                            ChannelItem(
                                room = room,
                                isCurrentRoom = isCurrent,
                                isListening = isListening,
                                speaker = liveSpeakers.find { it.roomId == room.id },
                                onClick = {
                                    if (!isCurrent) onJoinRoom(connection.serverId, room.id)
                                    onTuned()
                                },
                                onListenClick = {
                                    // 收听中的频道点耳朵 = 停止收听；否则开始收听
                                    if (isListening) onStopListen()
                                    else onListen(connection.serverId, room.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelItem(
    room: ApiClient.RoomInfo,
    isCurrentRoom: Boolean,
    onClick: () -> Unit,
    isListening: Boolean = false,        // 收听中（只收不发）
    speaker: LiveSpeaker? = null,        // 这个房间此刻正在说话的人
    onListenClick: () -> Unit = {}       // 点「只听 / 停听」：开始或停止只收不发
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentRoom -> BrandPurple.copy(alpha = 0.16f)
                isListening -> BrandPurple.copy(alpha = 0.08f)
                else -> SurfaceElevated
            }
        ),
        border = if (isCurrentRoom) BorderStroke(
            1.5.dp, BrandPurple.copy(alpha = 0.6f)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isCurrentRoom) BrandPurple.copy(alpha = 0.25f)
                            else Surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isCurrentRoom) BrandPurple else TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = room.name,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrentRoom) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrentRoom) BrandPurple else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isListening) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("收听中", fontSize = 11.sp, color = BrandPurple)
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${room.memberCount} 成员",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = " · ID: ${room.id}",
                            fontSize = 12.sp,
                            color = TextSecondary.copy(alpha = 0.6f)
                        )
                    }
                    if (speaker != null) {
                        Text(
                            text = "说话中 · ${DevModel.who(speaker.callsign, speaker.ssid, speaker.devModel)}",
                            fontSize = 12.sp,
                            color = ServerOnline,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isCurrentRoom) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BrandPurple.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ServerOnline)
                        )
                        Text(
                            text = "当前",
                            fontSize = 12.sp,
                            color = BrandPurple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onListenClick) {
                        Text(
                            text = if (isListening) "停听" else "只听",
                            fontSize = 13.sp,
                            color = if (isListening) BrandPurple else TextSecondary
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "加入",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 从对讲页点开的换台列表：只列当前这台服务器的频道。
 * 点一行就加入；多台服务器的收听仍去频道页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPickerSheet(
    serverName: String,
    rooms: List<ApiClient.RoomInfo>,
    currentRoomId: Int,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPick: (roomId: Int) -> Unit,
    onOpenAll: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        contentColor = TextPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = TextSecondary.copy(alpha = 0.45f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "切换频道",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = serverName,
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            if (isLoading && rooms.size <= 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = BrandPurple
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载频道中...", fontSize = 13.sp, color = TextSecondary)
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(rooms, key = { it.id }) { room ->
                    val current = room.id == currentRoomId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (current) BrandPurple.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(enabled = !current) { onPick(room.id) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = room.name,
                                fontSize = 15.sp,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                color = if (current) BrandPurple else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (room.memberCount > 0) {
                                Text(
                                    text = "${room.memberCount} 人",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        if (current) {
                            Text(
                                text = "当前",
                                fontSize = 13.sp,
                                color = BrandPurple,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = onOpenAll,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("全部服务器的频道", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
