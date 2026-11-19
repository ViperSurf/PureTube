package com.puretube.app.ui.screens.settings

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.puretube.app.PureTubeApp
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.launch

/**
 * Settings → Account (v2.0): optional YouTube sign-in via the session
 * cookies the user already owns. Two routes:
 * (a) PRIMARY — paste the Cookie header (must include SAPISID); it is
 *     validated against YouTube (account_menu) before anything is kept.
 * (b) EXPERIMENTAL — an in-app WebView Google sign-in; after login the
 *     cookies are read from youtube.com. Google blocks embedded sign-in
 *     on some accounts/devices, hence the label.
 *
 * Security: the header is stored ONLY in Keystore-encrypted storage
 * (see CookieStore), is never part of settings export/import, and is
 * never logged. No Google password is ever collected by the app —
 * the WebView talks to Google directly. Sign out wipes the store.
 */
@Composable
fun AccountSectionBlock(app: PureTubeApp) {
    val scope = rememberCoroutineScope()
    val account by app.accountRepository.state.collectAsState()
    var cookieDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }

    fun attemptSignIn(header: String) {
        if (busy) return
        busy = true
        status = "Validating with YouTube…"
        scope.launch {
            app.accountRepository.signInWithCookies(header)
                .onSuccess { state ->
                    status = "Signed in as ${state.name}."
                    cookieDraft = ""
                }
                .onFailure { e ->
                    status = e.message ?: "Sign-in failed."
                }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PureBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            "Account — YouTube (optional)",
            color = Color.White, fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (account.signedIn) {
            Row {
                if (account.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = account.avatarUrl,
                        contentDescription = account.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(account.name, color = Color.White, fontSize = 15.sp)
                    if (account.handle.isNotBlank()) {
                        Text(account.handle, color = PureTextSecondary, fontSize = 12.sp)
                    }
                    Text("Signed in", color = PureTextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = "Syncing from your account…"
                        scope.launch {
                            app.syncManager.syncNow()
                                .onSuccess { report -> status = report.summary() }
                                .onFailure { e ->
                                    status = e.message ?: "Sync failed."
                                }
                            busy = false
                        }
                    }
                ) { Text("Sync now", fontSize = 12.sp) }
                Button(
                    enabled = !busy,
                    onClick = {
                        app.accountRepository.signOut()
                        status = "Signed out. The stored session was wiped."
                    }
                ) { Text("Sign out", fontSize = 12.sp) }
            }
            Text(
                "Sync is pull-only: your playlists, history, subscriptions and " +
                    "Liked videos are mirrored into this device. Likes, subscribes " +
                    "and comments you make in PureTube go to YouTube directly.",
                color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            Text("Not signed in", color = PureTextSecondary, fontSize = 13.sp)
            Text(
                "Signing in is optional — everything works signed out. When signed " +
                    "in, Like/Dislike, Subscribe and comment posting act on your real " +
                    "YouTube account, and Sync can mirror your playlists, history, " +
                    "subscriptions and Liked videos into the local library. This uses " +
                    "your session cookies (unofficial session use — YouTube may expire " +
                    "them at any time). The cookies are stored only in encrypted, " +
                    "Keystore-backed storage on this device, never in settings exports.",
                color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = cookieDraft,
                onValueChange = { cookieDraft = it },
                placeholder = {
                    Text("Paste Cookie header (must include SAPISID)…", fontSize = 12.sp)
                },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Button(
                    enabled = !busy && cookieDraft.isNotBlank(),
                    onClick = { attemptSignIn(cookieDraft) }
                ) { Text("Validate & sign in", fontSize = 12.sp) }
                Button(
                    enabled = !busy,
                    onClick = { showWebView = true }
                ) { Text("WebView sign-in (experimental)", fontSize = 12.sp) }
            }
            Text(
                "How to get the header: on a computer, open youtube.com signed in, " +
                    "open DevTools → Network, click any request, and copy the full " +
                    "Cookie request header. PureTube never asks for your Google password.",
                color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (status.isNotBlank()) {
            Text(
                status, color = PureTextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    if (showWebView) {
        YouTubeSignInWebViewDialog(
            onDismiss = { showWebView = false },
            onCookiesCaptured = { header ->
                showWebView = false
                attemptSignIn(header)
            }
        )
    }
}

/**
 * Experimental in-app Google sign-in. The WebView loads YouTube; the
 * user signs in with Google directly (the app never sees the password);
 * "Use cookies" then reads the youtube.com cookies and hands them to
 * the same validation flow as the paste route.
 */
@Composable
private fun YouTubeSignInWebViewDialog(
    onDismiss: () -> Unit,
    onCookiesCaptured: (String) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var note by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                "Sign in with Google (experimental)",
                color = Color.White, fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            Text(
                "Google sometimes blocks sign-in inside embedded browsers. " +
                    "If that happens here, use the paste-cookies route instead.",
                color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/126.0.0.0 Safari/537.36"
                        webViewClient = WebViewClient()
                        loadUrl("https://www.youtube.com/")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            if (note.isNotBlank()) {
                Text(note, color = Color(0xFFFF8080), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie("https://www.youtube.com")
                    }.getOrNull().orEmpty()
                    if (cookies.contains("SAPISID=")) {
                        onCookiesCaptured(cookies)
                    } else {
                        note = "No SAPISID cookie yet — finish signing in on the page first."
                    }
                }) { Text("Use cookies from this page", fontSize = 12.sp) }
                TextButton(onClick = {
                    runCatching { webViewRef?.destroy() }
                    onDismiss()
                }) { Text("Close", fontSize = 12.sp) }
            }
        }
    }
}
