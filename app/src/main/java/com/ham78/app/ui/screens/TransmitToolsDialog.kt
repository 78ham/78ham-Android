package com.ham78.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.service.TalkService
import com.ham78.app.ui.theme.Background
import com.ham78.app.ui.theme.BrandBlue
import com.ham78.app.ui.theme.BrandCyan
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Divider
import com.ham78.app.ui.theme.PttTransmitting
import com.ham78.app.ui.theme.Surface
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/*
 * 发送工具面板：CW、DTMF、SSTV、TTS 播报、录音发送。
 * 一个 AlertDialog 承载五个标签页，每个标签页一个输入区 + 发送按钮。
 *
 * 调用示例（PttScreen 工具行）：
 *   var showTools by remember { mutableStateOf(false) }
 *   if (showTools) TransmitToolsDialog(talkService, onDismiss = { showTools = false })
 */

private enum class ToolTab(val label: String) {
    CW("CW"), DTMF("DTMF"), SSTV("SSTV"), TTS("播报"), MEMO("录音")
}

@Composable
fun TransmitToolsDialog(
    talkService: TalkService,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(ToolTab.CW) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Column {
                Text("发送工具", color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "信号会发到当前频道的所有成员",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // --- 标签行：五个工具切换 ---
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ToolTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            shape = SegmentedButtonDefaults.itemShape(index, ToolTab.entries.size),
                            label = { Text(tab.label, fontSize = 13.sp) },
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = BrandPurple.copy(alpha = 0.25f),
                                activeContentColor = BrandPurple,
                                inactiveContainerColor = Surface,
                                inactiveContentColor = TextSecondary
                            )
                        )
                    }
                }

                when (selectedTab) {
                    ToolTab.CW -> CwToolPanel(talkService)
                    ToolTab.DTMF -> DtmfToolPanel(talkService)
                    ToolTab.SSTV -> SstvToolPanel(talkService)
                    ToolTab.TTS -> TtsToolPanel(talkService)
                    ToolTab.MEMO -> MemoToolPanel(talkService)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = TextSecondary) }
        }
    )
}

// --- CW 面板 ---

@Composable
private fun CwToolPanel(talkService: TalkService) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var wpm by remember { mutableIntStateOf(15) }
    var sending by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it.uppercase() },
        label = { Text("CW 文本（A-Z 0-9 标点）") },
        modifier = Modifier.fillMaxWidth(),
        colors = outlinedColors()
    )

    Text("速度: $wpm WPM", fontSize = 13.sp, color = TextSecondary)
    androidx.compose.material3.Slider(
        value = wpm.toFloat(),
        onValueChange = { wpm = it.toInt() },
        valueRange = 5f..35f,
        steps = 29   // 5..35 每 1WPM 一档
    )

    SendButton(
        enabled = text.isNotBlank() && !sending,
        sending = sending,
        label = "发送 CW",
        color = BrandCyan
    ) {
        scope.launch {
            sending = true
            talkService.sendCw(text.trim(), wpm)
            sending = false
        }
    }
}

// --- DTMF 面板 ---

@Composable
private fun DtmfToolPanel(talkService: TalkService) {
    val scope = rememberCoroutineScope()
    var digits by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = digits,
        onValueChange = { digits = it.uppercase().filter { c -> c in "0123456789ABCD*#" } },
        label = { Text("DTMF 数字（0-9 * # A-D）") },
        modifier = Modifier.fillMaxWidth(),
        colors = outlinedColors()
    )

    Text("常用于中继台控制命令，如 *1234 连接节点", fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.7f))

    SendButton(
        enabled = digits.isNotBlank() && !sending,
        sending = sending,
        label = "发送 DTMF",
        color = BrandBlue
    ) {
        scope.launch {
            sending = true
            talkService.sendDtmf(digits.trim())
            sending = false
        }
    }
}

// --- SSTV 面板 ---

@Composable
private fun SstvToolPanel(talkService: TalkService) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sending by remember { mutableStateOf(false) }
    var pickedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 从相册选图 → 缩放到 320×240 → 调制发射
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                sending = true
                val original = context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
                val scaled = original?.let {
                    android.graphics.Bitmap.createScaledBitmap(it, 320, 240, true)
                }
                pickedBitmap = scaled
                if (scaled != null) {
                    val pixels = IntArray(320 * 240)
                    scaled.getPixels(pixels, 0, 320, 0, 0, 320, 240)
                    talkService.sendSstv(pixels)
                }
                sending = false
            }
        }
    }

    Text(
        "Robot36 制式：36 秒传一张 320×240 彩色图，接收方需 SSTV 解码软件",
        fontSize = 12.sp,
        color = TextSecondary
    )

    if (pickedBitmap != null) {
        // 预览选中的图片
        androidx.compose.foundation.Image(
            bitmap = pickedBitmap!!.asImageBitmap(),
            contentDescription = "待发送图片预览",
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Background, RoundedCornerShape(8.dp))
        )
    }

    SendButton(
        enabled = pickedBitmap != null && !sending,
        sending = sending,
        label = if (pickedBitmap == null) "选择图片" else "重新选择图片",
        color = BrandPurple,
        action = { pickImage.launch("image/*") }
    )
}

// --- TTS 播报面板 ---

@Composable
private fun TtsToolPanel(talkService: TalkService) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("播报文字") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        colors = outlinedColors()
    )

    SendButton(
        enabled = text.isNotBlank() && !sending,
        sending = sending,
        label = "合成并发送",
        color = BrandPurple
    ) {
        scope.launch {
            sending = true
            talkService.sendTts(text.trim())
            sending = false
        }
    }
}

// --- 录音面板 ---

@Composable
private fun MemoToolPanel(talkService: TalkService) {
    val scope = rememberCoroutineScope()
    val isRecording by talkService.isMemoRecording.collectAsState()
    val seconds by talkService.memoRecordSeconds.collectAsState()
    var sending by remember { mutableStateOf(false) }

    if (!isRecording && !sending) {
        // 空闲：开始录音按钮
        val scope = rememberCoroutineScope()
        Button(
            onClick = { scope.launch { talkService.startMemoRecording() } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PttTransmitting, contentColor = TextOnPrimary
            )
        ) {
            Text("开始录音（最长 60 秒）", fontWeight = FontWeight.Bold)
        }
    } else if (isRecording) {
        // 录音中：计时 + 完成/取消
        Text(
            "● 录音中 ${seconds}s / 60s",
            color = PttTransmitting,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        sending = true
                        talkService.stopMemoRecordingAndSend()
                        sending = false
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
            ) {
                Text(if (sending) "发送中..." else "完成并发送")
            }
            OutlinedButton(
                onClick = { talkService.cancelMemoRecording() },
                modifier = Modifier.weight(1f)
            ) {
                Text("取消", color = TextSecondary)
            }
        }
    }

    if (sending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandPurple)
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在发射...", fontSize = 13.sp, color = TextSecondary)
        }
    }
}

// --- 公共小组件 ---

/** 统一的发送按钮：sending 时显示进度圈禁用点击 */
@Composable
private fun SendButton(
    enabled: Boolean,
    sending: Boolean,
    label: String,
    color: Color,
    action: (() -> Unit)? = null
) {
    Button(
        onClick = { action?.invoke() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = TextOnPrimary,
            disabledContainerColor = color.copy(alpha = 0.3f)
        )
    ) {
        if (sending) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = TextOnPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** 统一的输入框配色 */
@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandPurple.copy(alpha = 0.5f),
    unfocusedBorderColor = Divider,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = BrandPurple
)
