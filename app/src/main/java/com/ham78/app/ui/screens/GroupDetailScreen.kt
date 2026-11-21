package com.ham78.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.ApiClient
import com.ham78.app.network.DevModel
import com.ham78.app.ui.hamFieldColors
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Connected
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary

@Composable
fun GroupDetailScreen(serverHost: String, groupId: Int, groupName: String) {
    var members by remember { mutableStateOf<List<ApiClient.DeviceInGroup>>(emptyList()) }
    var title by remember { mutableStateOf(groupName) }
    var online by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val colors = hamFieldColors()

    LaunchedEffect(serverHost, groupId) {
        loading = true
        val result = ApiClient.getGroup(serverHost, groupId)
        loading = false
        result.fold(
            onSuccess = { group ->
                title = group.name.ifEmpty { groupName }
                online = group.onlineCount
                members = group.devmap.values.sortedWith(compareByDescending<ApiClient.DeviceInGroup> { it.isOnline }.thenBy { it.callsign })
                error = ""
            },
            onFailure = { error = it.message ?: "加载群组失败" }
        )
    }

    val shown = members.filter { device ->
        val q = query.trim().lowercase()
        q.isEmpty() || device.callsign.lowercase().contains(q) || device.dmrId.toString().contains(q)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("$title  ·  ${members.size} 台  ·  $online 在线", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索呼号或 DMR ID") },
            singleLine = true,
            colors = colors,
            modifier = Modifier.fillMaxWidth()
        )
        if (loading) {
            CircularProgressIndicator(color = BrandPurple, modifier = Modifier.padding(top = 24.dp))
        } else if (error.isNotEmpty()) {
            Text(error, color = TextSecondary, modifier = Modifier.padding(top = 16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { "${it.callsign}-${it.ssid}" }) { device ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${device.callsign}-${device.ssid}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val model = DevModel.name(device.devModel)
                            val dmr = if (device.dmrId != 0) "DMR ${device.dmrId}" else ""
                            val line = listOf(model, dmr).filter { it.isNotEmpty() }.joinToString("  ·  ")
                            if (line.isNotEmpty()) Text(line, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            if (device.isOnline) "在线" else "离线",
                            color = if (device.isOnline) Connected else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
