package com.ham78.app.ui.screens

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
 * 频道页：按服务器分组的频道列表，每个服务器可折叠展开
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    serverConnections: List<ServerConnection>,
    roomLists: Map<String, List<ApiClient.RoomInfo>>,
    onJoinRoom: (serverId: String, roomId: Int) -> Unit,
    onRefresh: (serverId: String) -> Unit
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
                            onJoinRoom = onJoinRoom,
                            onRefresh = { onRefresh(conn.serverId) }
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
    onJoinRoom: (serverId: String, roomId: Int) -> Unit,
    onRefresh: () -> Unit
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
                        filteredRooms.forEach { room ->
                            ChannelItem(
                                room = room,
                                isCurrentRoom = room.id == connection.currentRoomId,
                                onClick = {
                                    if (room.id != connection.currentRoomId) {
                                        onJoinRoom(connection.serverId, room.id)
                                    }
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
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentRoom) BrandPurple.copy(alpha = 0.16f) else SurfaceElevated
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
                        if (isCurrentRoom) {
                            ChannelPulseIndicator()
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = room.name,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrentRoom) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrentRoom) BrandPurple else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                            text = "通话中",
                            fontSize = 12.sp,
                            color = BrandPurple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                TextButton(onClick = onClick) {
                    Text("加入", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 频道活跃呼吸脉冲绿灯
 */
@Composable
private fun ChannelPulseIndicator() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(ServerOnline.copy(alpha = alpha))
    )
}
