package com.ham78.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ham78.app.network.ApiClient
import com.ham78.app.ui.compressJpeg
import com.ham78.app.ui.hamFieldColors
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Error
import com.ham78.app.ui.theme.TextOnPrimary
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用内注册。执照照片和表单一并提交，成功后等管理员审核，不直接登录。
 */
@Composable
fun RegisterScreen(
    serverHost: String,
    onBack: () -> Unit,
    onSubmitted: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = hamFieldColors()
    var callsign by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var mail by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            license = compressJpeg(context, uri, File(context.cacheDir, "license.jpg"))
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "图片处理失败"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("注册账号", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "提交操作证和电台执照，管理员一般 48 小时内审核。",
            color = TextSecondary,
            fontSize = 13.sp
        )
        OutlinedTextField(callsign, { callsign = it.uppercase() }, label = { Text("呼号") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("姓名") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("手机号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it }, label = { Text("地址") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mail, { mail = it }, label = { Text("邮箱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), colors = colors, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (license == null) "上传操作证和电台执照" else "已选择执照照片", color = TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("我确认持有有效执照，并同意提交审核", color = TextSecondary, fontSize = 13.sp)
        }
        if (error.isNotEmpty()) Text(error, color = Error, fontSize = 13.sp)
        Button(
            onClick = {
                val why = validate(callsign, name, phone, password, address, mail, license, agreed)
                if (why != null) {
                    error = why
                    return@Button
                }
                busy = true
                error = ""
                scope.launch {
                    val result = ApiClient.register(
                        serverHost,
                        mapOf(
                            "callsign" to callsign.trim(),
                            "name" to name.trim(),
                            "phone" to phone.trim(),
                            "password" to password,
                            "address" to address.trim(),
                            "mail" to mail.trim()
                        ),
                        license!!
                    )
                    busy = false
                    result.fold(
                        onSuccess = { onSubmitted(it) },
                        onFailure = { error = it.message ?: "注册失败" }
                    )
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = TextOnPrimary)
        ) { Text(if (busy) "提交中..." else "提交注册") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回登录", color = TextSecondary)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun validate(
    callsign: String,
    name: String,
    phone: String,
    password: String,
    address: String,
    mail: String,
    license: File?,
    agreed: Boolean
): String? {
    if (!agreed) return "请先确认执照并同意提交审核"
    if (!Regex("^[A-Z0-9]{5,6}$").matches(callsign.trim())) return "呼号只能是 5-6 位大写字母和数字"
    if (name.isBlank()) return "请输入姓名"
    if (!Regex("^\\d{11,}$").matches(phone.trim())) return "请输入 11 位以上数字的手机号"
    if (password.isBlank()) return "请输入密码"
    if (address.isBlank()) return "请输入地址"
    if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(mail.trim())) return "请输入有效的邮箱地址"
    if (license == null) return "请上传操作证和电台执照"
    return null
}

fun toast(context: android.content.Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
