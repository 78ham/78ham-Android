package com.ham78.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ham78.app.ui.theme.BrandPurple
import com.ham78.app.ui.theme.Divider
import com.ham78.app.ui.theme.SurfaceCard
import com.ham78.app.ui.theme.TextPrimary
import com.ham78.app.ui.theme.TextSecondary
import java.io.File
import java.io.FileOutputStream

@Composable
fun hamFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandPurple,
    unfocusedBorderColor = Divider,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = BrandPurple,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary
)

/** 有头像就显示，没有或加载失败用默认的人像。 */
@Composable
fun UserAvatar(
    url: String?,
    localFile: File?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val model: Any? = when {
        localFile != null && localFile.exists() && localFile.length() > 0 -> localFile
        !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) -> url
        else -> null
    }
    var failed by remember(model) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(SurfaceCard),
        contentAlignment = Alignment.Center
    ) {
        if (model != null && !failed) {
            AsyncImage(
                model = model,
                contentDescription = "头像",
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                onError = { _ -> failed = true }
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = "默认头像",
                tint = BrandPurple,
                modifier = Modifier.size(size * 0.62f)
            )
        }
    }
}

fun avatarFile(context: Context): File = File(context.filesDir, "avatar.jpg")

/** 把选中的照片压到 512KB 以内，和注册页的执照限制一致。 */
fun compressJpeg(context: Context, uri: Uri, dest: File, maxBytes: Int = 512 * 1024): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 1280) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: throw IllegalArgumentException("无法读取图片")
    var quality = 80
    dest.parentFile?.mkdirs()
    while (quality >= 40) {
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (dest.length() <= maxBytes) break
        quality -= 20
    }
    bitmap.recycle()
    if (dest.length() > maxBytes) {
        dest.delete()
        throw IllegalArgumentException("图片过大，请换一张")
    }
    return dest
}
