package com.ham78.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ham78.app.ui.theme.TextSecondary

/**
 * OIDC 登录页。认证完成后服务器会把浏览器带到带 token 的回调地址，
 * 这里从地址里取出 token，不把回调页留给用户看。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OidcWebScreen(
    url: String,
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消", color = TextSecondary)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    val gate = object {
                        var done = false
                        fun take(raw: String): Boolean {
                            if (done) return true
                            val uri = Uri.parse(raw)
                            val err = uri.getQueryParameter("oidc_error")
                            if (!err.isNullOrEmpty()) {
                                done = true
                                onError(err)
                                return true
                            }
                            val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("oidc_token")
                            val callback = raw.contains("oidc-callback") || raw.contains("oidc_token")
                            if (!token.isNullOrEmpty() && callback) {
                                done = true
                                onToken(token)
                                return true
                            }
                            return false
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            gate.take(request.url.toString())

                        @Deprecated("API < 24")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = gate.take(url)

                        override fun onPageFinished(view: WebView, url: String) {
                            gate.take(url)
                        }
                    }
                    loadUrl(url)
                }
            }
        )
    }
}
