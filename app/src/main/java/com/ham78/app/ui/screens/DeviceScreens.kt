package com.ham78.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.ApiClient
import com.ham78.app.network.AtCatalog
import com.ham78.app.network.AtCommand
import com.ham78.app.network.AtInput
import com.ham78.app.network.DevModel
import com.ham78.app.ui.hamFieldColors
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Connected
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MyDevicesScreen(
    serverHost: String,
    roomNames: Map<Int, String>,
    onOpenAt: (ApiClient.ManagedDevice) -> Unit,
    onOpenSettings: (ApiClient.ManagedDevice) -> Unit
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<ApiClient.ManagedDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<ApiClient.ManagedDevice?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        val result = ApiClient.getMyDevices(serverHost)
        loading = false
        result.fold(
            onSuccess = { devices = it; error = "" },
            onFailure = { error = it.message ?: "加载设备失败" }
        )
    }

    LaunchedEffect(serverHost) { reload() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("当前账号下的设备。在线的排在前面。", color = TextSecondary, fontSize = 13.sp)
        if (loading && devices.isEmpty()) {
            CircularProgressIndicator(color = BrandPurple)
        }
        if (error.isNotEmpty()) {
            Text(error, color = TextSecondary)
            TextButton(onClick = { scope.launch { reload() } }) { Text("重试", color = BrandPurple) }
        }
        devices.forEach { dev ->
            val kind = AtCatalog.manageKind(dev.devModel)
            val canAt = dev.hasAt || kind == "at" || kind == "at-legacy"
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            dev.name.ifBlank { "${dev.callsign}-${dev.ssid}" },
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(if (dev.isOnline) "在线" else "离线", color = if (dev.isOnline) Connected else TextSecondary, fontSize = 12.sp)
                    }
                    Text(DevModel.name(dev.devModel).ifEmpty { "型号 ${dev.devModel}" }, color = TextSecondary, fontSize = 12.sp)
                    val group = roomNames[dev.groupId] ?: if (dev.groupId == 0) "公共大厅" else "#${dev.groupId}"
                    Text("群组 $group", color = TextSecondary, fontSize = 12.sp)
                    val ip = AtCatalog.valueOf(dev.atmap, "WIFI_IP")
                        ?: dev.parm["local_ipaddr"]?.toString()?.takeIf { it.isNotEmpty() }
                    if (!ip.isNullOrEmpty()) Text("IP $ip", color = TextSecondary, fontSize = 12.sp)
                    if (dev.version.isNotEmpty()) Text(dev.version, color = TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAt) {
                            TextButton(onClick = { onOpenAt(dev) }) { Text("AT 指令", color = BrandPurple) }
                        }
                        if (kind == "at-legacy") {
                            TextButton(onClick = { onOpenSettings(dev) }) { Text("设备设置", color = BrandPurple) }
                        }
                        TextButton(onClick = { picker = dev }) { Text("切换群组", color = TextSecondary) }
                        if (!canAt) Text("该设备类型不支持远程配置", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }

    picker?.let { dev ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text("切换群组") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val rooms = (listOf(0 to "公共大厅") + roomNames.map { it.key to it.value })
                        .distinctBy { it.first }
                    rooms.forEach { (id, name) ->
                        Text(
                            text = if (id == dev.groupId) "$name  · 当前" else name,
                            color = if (id == dev.groupId) BrandPurple else TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picker = null
                                    scope.launch {
                                        val result = ApiClient.moveDeviceToGroup(serverHost, dev, id)
                                        Toast.makeText(
                                            context,
                                            if (result.isSuccess) "已切换群组" else "切换失败，群组可能不允许加入",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        reload()
                                    }
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } }
        )
    }
}

@Composable
fun DeviceAtScreen(serverHost: String, seed: ApiClient.ManagedDevice) {
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf(seed) }
    var atmap by remember { mutableStateOf(seed.atmap) }
    var inputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var expanded by remember { mutableStateOf("link") }
    var confirm by remember { mutableStateOf<AtCommand?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    val colors = hamFieldColors()

    fun snapshot(): String = device.version + "|" + atmap.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }

    suspend fun exec(key: String, data: String, silent: Boolean = false) {
        if (!device.isOnline) {
            log = log + "设备离线，无法发送"
            return
        }
        pending = key
        if (!silent) log = (log + "发送 AT+$key=$data").takeLast(30)
        val prev = snapshot()
        val type = if (data == "?" || key == "READ") 1 else 2
        var fresh: ApiClient.ManagedDevice? = null
        ApiClient.sendDeviceAt(serverHost, device.callsign, device.ssid, type, "AT+$key", data)
            .getOrNull()?.let { if (snapshotOf(it) != prev) fresh = it }
        for (attempt in 0 until 4) {
            if (fresh != null) break
            delay(600)
            ApiClient.getManagedDevice(serverHost, device.callsign, device.ssid).getOrNull()?.let { got ->
                device = device.copy(isOnline = got.isOnline, version = got.version.ifEmpty { device.version })
                if (!got.isOnline) return@let
                if (snapshotOf(got) != prev) fresh = got
            }
        }
        pending = null
        val reply = fresh
        if (reply == null) {
            if (!silent) log = (log + "等待设备回复超时").takeLast(30)
            if (atmap.isEmpty()) unsupported = true
            return
        }
        unsupported = false
        device = device.copy(isOnline = reply.isOnline, version = reply.version.ifEmpty { device.version }, hasAt = true)
        atmap = atmap + reply.atmap
        if (!silent) log = (log + "已收到回复").takeLast(30)
    }

    LaunchedEffect(seed.callsign, seed.ssid) {
        ApiClient.getManagedDevice(serverHost, seed.callsign, seed.ssid).getOrNull()?.let {
            device = it
            atmap = it.atmap
            if (it.hasAt) unsupported = false
        }
        if (device.isOnline) exec("READ", "123", silent = true)
        else if (!device.hasAt) unsupported = true
    }

    val categories = AtCatalog.visible(device.devModel, atmap)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "${device.callsign}-${device.ssid}  ·  ${if (device.isOnline) "在线" else "离线"}",
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        if (device.version.isNotEmpty()) Text(device.version, color = TextSecondary, fontSize = 12.sp)
        Text("指令经服务器转发，回复是异步的，保存后会等设备回传。", color = TextSecondary, fontSize = 12.sp)
        if (unsupported) Text("这台设备没有返回 AT 数据，可能不支持远程配置。", color = TextSecondary, fontSize = 13.sp)
        categories.forEach { cat ->
            Text(
                cat.name,
                color = BrandPurple,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (expanded == cat.id) "" else cat.id }
                    .padding(vertical = 6.dp)
            )
            if (expanded == cat.id) {
                if (cat.hint.isNotEmpty()) Text(cat.hint, color = TextSecondary, fontSize = 12.sp)
                cat.commands.forEach { cmd ->
                    AtRow(
                        cmd = cmd,
                        value = AtCatalog.valueOf(atmap, cmd.key).orEmpty(),
                        draft = inputs[cmd.key] ?: AtCatalog.valueOf(atmap, cmd.key).orEmpty(),
                        busy = pending == cmd.key,
                        colors = colors,
                        onDraft = { inputs = inputs + (cmd.key to it) },
                        onRun = { value ->
                            if (cmd.confirm.isNotEmpty()) confirm = cmd
                            else scope.launch { exec(cmd.key, value) }
                        }
                    )
                }
            }
        }
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            log.forEach { Text(it, color = TextSecondary, fontSize = 12.sp) }
        }
    }

    confirm?.let { cmd ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(cmd.label) },
            text = { Text(cmd.confirm) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { exec(cmd.key, cmd.actionValue) }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun AtRow(
    cmd: AtCommand,
    value: String,
    draft: String,
    busy: Boolean,
    colors: androidx.compose.material3.TextFieldColors,
    onDraft: (String) -> Unit,
    onRun: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(cmd.label, color = TextPrimary, fontSize = 14.sp)
        if (cmd.desc.isNotEmpty()) Text(cmd.desc, color = TextSecondary, fontSize = 11.sp)
        when (cmd.input) {
            AtInput.READONLY -> Text(value.ifEmpty { "—" }, color = TextSecondary, fontSize = 13.sp)
            AtInput.SWITCH -> Row(verticalAlignment = Alignment.CenterVertically) {
                val on = value.trim().matches(Regex("(?i)ON|1|TRUE|ENABLE"))
                Switch(checked = on, onCheckedChange = { if (!busy) onRun(if (it) "ON" else "OFF") }, enabled = !busy)
                Text(if (on) "开" else "关", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            AtInput.ACTION -> Button(
                onClick = { onRun(cmd.actionValue) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = TextOnPrimary)
            ) { Text(if (busy) "执行中" else cmd.label) }
            AtInput.SELECT -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cmd.options.forEach { option ->
                    val selected = value.equals(option, ignoreCase = true)
                    OutlinedButton(onClick = { if (!busy) onRun(option) }, enabled = !busy) {
                        Text(option, color = if (selected) BrandPurple else TextSecondary)
                    }
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = colors
                )
                TextButton(onClick = { if (!busy && draft.isNotBlank()) onRun(draft) }, enabled = !busy) {
                    Text(if (busy) "..." else "保存", color = BrandPurple)
                }
            }
        }
    }
}

@Composable
fun DeviceSettingsScreen(serverHost: String, seed: ApiClient.ManagedDevice) {
    var parm by remember { mutableStateOf(seed.parm.mapValues { it.value?.toString().orEmpty() }) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = hamFieldColors()
    val context = LocalContext.current

    LaunchedEffect(seed.callsign) {
        ApiClient.getManagedDevice(serverHost, seed.callsign, seed.ssid).getOrNull()?.let { got ->
            parm = got.parm.mapValues { it.value?.toString().orEmpty() }
        }
    }

    fun form(extra: Map<String, String>): String {
        val base = listOf(
            "DMRID" to seed.dmrId.toString(),
            "callsign" to seed.callsign,
            "ssid" to seed.ssid.toString()
        )
        return (base + extra.entries.map { it.key to it.value })
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("老型号的寄存器参数。改 IP 填错后设备会连不上服务器。", color = TextSecondary, fontSize = 13.sp)
        listOf(
            "local_ipaddr" to "本机 IP",
            "netmask" to "子网掩码",
            "gateway" to "网关",
            "dns_ipaddr" to "DNS",
            "dest_domainname" to "服务器地址"
        ).forEach { (key, label) ->
            OutlinedTextField(
                value = parm[key].orEmpty(),
                onValueChange = { parm = parm + (key to it) },
                label = { Text(label) },
                singleLine = true,
                colors = colors,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val result = ApiClient.changeDeviceParm(
                        serverHost,
                        form(mapOf(
                            "local_ipaddr" to parm["local_ipaddr"].orEmpty(),
                            "gateway" to parm["gateway"].orEmpty(),
                            "netmask" to parm["netmask"].orEmpty(),
                            "dns_ipaddr" to parm["dns_ipaddr"].orEmpty(),
                            "dest_domainname" to parm["dest_domainname"].orEmpty()
                        ))
                    )
                    busy = false
                    message = result.getOrElse { it.message ?: "保存失败" }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = TextOnPrimary)
        ) { Text("保存网络") }

        Text("射频", color = BrandPurple, fontWeight = FontWeight.Bold)
        listOf(
            "one_recive_freq" to "接收频率",
            "one_transmit_freq" to "发射频率",
            "one_recive_cxcss" to "接收亚音",
            "one_transmit_cxcss" to "发射亚音",
            "one_volume" to "音量",
            "one_sql_level" to "静噪",
            "one_mic_sensitivity" to "麦克风",
            "moto_channel" to "信道"
        ).forEach { (key, label) ->
            OutlinedTextField(
                value = parm[key].orEmpty(),
                onValueChange = { parm = parm + (key to it) },
                label = { Text(label) },
                singleLine = true,
                colors = colors,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val body = seed.parm.toMutableMap()
                    parm.forEach { (k, v) -> body[k] = v }
                    val result = ApiClient.changeDevice1w(serverHost, body)
                    busy = false
                    message = result.getOrElse { it.message ?: "保存失败" }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = TextOnPrimary)
        ) { Text("保存射频") }
        if (message.isNotEmpty()) Text(message, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun snapshotOf(device: ApiClient.ManagedDevice): String =
    device.version + "|" + device.atmap.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
