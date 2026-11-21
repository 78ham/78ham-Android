package com.ham78.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.CallMonitor
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Connected
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary

/**
 * 实时监听：看全部房间谁在说话，点收听只听这一路混流，不改变对讲页当前频道。
 * 加入才会把这台手机切到那个房间。
 */
@Composable
fun MonitorScreen(
    serverHost: String,
    token: String,
    callsign: String,
    currentGroupId: Int,
    onJoin: (Int) -> Unit
) {
    var connected by remember { mutableStateOf(false) }
    var rooms by remember { mutableStateOf<List<CallMonitor.MonitorRoom>>(emptyList()) }
    var stats by remember { mutableStateOf(CallMonitor.MonitorStats()) }
    val scope = rememberCoroutineScope()
    val monitor = remember(serverHost, token, callsign, currentGroupId) {
        CallMonitor(serverHost, token, callsign, currentGroupId) { on, list, st ->
            // WebSocket 回调不在主线程，界面状态要回到主线程再写
            scope.launch {
                connected = on
                rooms = list
                stats = st
            }
        }
    }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            text = buildString {
                append(if (connected) "已连接" else "正在连接")
                append("  ·  在线 ${stats.devices}")
                append("  ·  收听 ${stats.subs}")
                append("  ·  连接 ${stats.clients}")
            },
            color = if (connected) Connected else TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        if (token.isEmpty()) {
            Text("未登录也可以收听公共房间。加入房间需要先登录。", color = TextSecondary, fontSize = 12.sp)
        }
        if (rooms.isEmpty()) {
            Text(if (connected) "暂无房间" else "正在连接服务器...", color = TextSecondary, modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rooms, key = { it.roomKey }) { room ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = if (room.subscribed || room.active) BorderStroke(1.dp, BrandPurple.copy(alpha = 0.7f)) else null
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "#${room.roomId} ${room.name}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${room.typeName}  ·  在线 ${room.online}  ·  ${if (room.active && room.speakers.isNotEmpty()) room.speakers else "空闲"}",
                                color = if (room.active) BrandPurple else TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { monitor.subscribe(room.roomKey, !room.subscribed) }) {
                                    Text(if (room.subscribed) "收听中" else "收听", color = BrandPurple, fontSize = 12.sp)
                                }
                                if (room.isCurrent) {
                                    Text("当前", color = Connected, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                                } else {
                                    TextButton(onClick = { onJoin(room.roomId) }, enabled = token.isNotEmpty()) {
                                        Text("加入", color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
