package com.puretube.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.puretube.app.PureTubeApp
import com.puretube.app.domain.DeviceCodeGrant
import com.puretube.app.domain.DevicePollResult
import com.puretube.app.ui.theme.PureBorder
import com.puretube.app.ui.theme.PureTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Account: optional YouTube sign-in (v2.1 routes).
 *
 * PRIMARY — device code (the TV-client flow): the app shows a short
 * code; the user opens youtube.com/activate on ANY device (this
 * phone's browser is fine — no computer, no DevTools) and approves.
 * The app polls until approved, then stores the OAuth REFRESH token in
 * Keystore-encrypted storage. This replaces the v2.0 WebView route,
 * which Google blocks ("This browser or app may not be secure") — the
 * WebView route is removed entirely.
 *
 * FALLBACK — paste the session Cookie header (must include SAPISID);
 * it is validated against YouTube (account_menu) before anything is
 * kept, exactly as in v2.0.
 *
 * Tokens/cookies are stored ONLY as Keystore ciphertext, are never
 * part of settings export/import, and are never logged. Sign out
 * wipes the store.
 */
@Composable
fun AccountSectionBlock(app: PureTubeApp) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val account by app.accountRepository.state.collectAsState()
    var cookieDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deviceFlow by remember { mutableStateOf<DeviceCodeGrant?>(null) }

    fun attemptCookieSignIn(header: String) {
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

    // Device-code polling loop: runs while a code is on screen.
    LaunchedEffect(deviceFlow) {
        val flow = deviceFlow ?: return@LaunchedEffect
        var intervalSeconds = flow.intervalSeconds.coerceAtLeast(5L)
        val deadline = System.currentTimeMillis() + flow.expiresInSeconds * 1000L
        while (deviceFlow == flow) {
            delay(intervalSeconds * 1000L)
            if (System.currentTimeMillis() > deadline) {
                status = "That code expired — start again to get a fresh one."
                deviceFlow = null
                break
            }
            val outcome = app.accountRepository.pollDeviceSignIn(flow)
                .getOrElse { e ->
                    status = e.message ?: "Sign-in check failed."
                    deviceFlow = null
                    break
                }
            when (outcome) {
                is DevicePollResult.Pending ->
                    status = "Waiting for approval… enter the code at youtube.com/activate."
                is DevicePollResult.SlowDown -> {
                    intervalSeconds += 5
                    status = "Waiting for approval…"
                }
                is DevicePollResult.Approved -> {
                    status = "Signed in as ${app.accountRepository.state.value.name}."
                    deviceFlow = null
                }
                is DevicePollResult.Denied -> {
                    status = "Sign-in was denied on the other device. Nothing was stored."
                    deviceFlow = null
                }
                is DevicePollResult.Expired -> {
                    status = "That code expired — start again to get a fresh one."
                    deviceFlow = null
                }
                is DevicePollResult.Failed -> {
                    status = outcome.message
                    deviceFlow = null
                }
            }
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
            fontWeight = FontWeight.Medium
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
                        status = "Syncing with your account…"
                        scope.launch {
                            // Make sure the OAuth token is fresh first.
                            runCatching { app.accountRepository.freshAccessToken() }
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
                "Sync is two-way: it first pulls your playlists, history, " +
                    "subscriptions and Liked videos into this device, then pushes " +
                    "your local playlists up — creating the ones YouTube doesn't " +
                    "have and adding only the videos they're missing. Likes, " +
                    "subscribes and comments post to YouTube the moment you tap " +
                    "them. Videos you watch while signed in are also registered " +
                    "in your YouTube history (best effort).",
                color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            Text("Not signed in", color = PureTextSecondary, fontSize = 13.sp)
            Text(
                "Signing in is optional — everything works signed out. When signed " +
                    "in, Like/Dislike, Subscribe and comment posting act on your real " +
                    "YouTube account, and Sync keeps playlists in step both ways. " +
                    "Sign-in is unofficial (the public YouTube TV client flow) — " +
                    "Google may revoke it at any time. Tokens are stored only in " +
                    "encrypted, Keystore-backed storage on this device, never in " +
                    "settings exports. PureTube never asks for your Google password.",
                color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            val flow = deviceFlow
            if (flow == null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = "Asking Google for a sign-in code…"
                        scope.launch {
                            app.accountRepository.beginDeviceSignIn()
                                .onSuccess { code ->
                                    deviceFlow = code
                                    status = "Enter the code at youtube.com/activate."
                                }
                                .onFailure { e ->
                                    status = e.message ?: "Could not get a sign-in code."
                                }
                            busy = false
                        }
                    }
                ) { Text("Sign in with a code (recommended)", fontSize = 12.sp) }
                Text(
                    "How it works: PureTube shows a short code. Open " +
                        "youtube.com/activate in any browser — this phone's browser " +
                        "is fine, no computer needed — sign in with Google there, " +
                        "type the code, and approve. PureTube notices on its own.",
                    color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    "Your code:",
                    color = PureTextSecondary, fontSize = 12.sp
                )
                Text(
                    flow.userCode,
                    color = Color.White, fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.youtube.com/activate")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Open youtube.com/activate", fontSize = 12.sp) }
                    TextButton(onClick = {
                        deviceFlow = null
                        status = "Sign-in cancelled. Nothing was stored."
                    }) { Text("Cancel", fontSize = 12.sp) }
                }
                Text(
                    "Waiting for approval… this updates on its own once you approve. " +
                        "The code expires in about ${flow.expiresInSeconds / 60} minutes.",
                    color = PureTextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Fallback — paste cookies",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
            OutlinedTextField(
                value = cookieDraft,
                onValueChange = { cookieDraft = it },
                placeholder = {
                    Text("Paste Cookie header (must include SAPISID)…", fontSize = 12.sp)
                },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Button(
                    enabled = !busy && cookieDraft.isNotBlank(),
                    onClick = { attemptCookieSignIn(cookieDraft) }
                ) { Text("Validate & sign in", fontSize = 12.sp) }
            }
            Text(
                "How to get the header: on a computer, open youtube.com signed in, " +
                    "open DevTools → Network, click any request, and copy the full " +
                    "Cookie request header.",
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
}
