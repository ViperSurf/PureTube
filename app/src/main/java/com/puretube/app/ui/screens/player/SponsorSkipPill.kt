package com.puretube.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puretube.app.ui.theme.PureBorder

/** Non-blocking "Skipped Sponsor - Undo" pill shown briefly after an auto-skip. */
@Composable
fun SponsorSkipPill(
    category: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color(0xE6000000), RoundedCornerShape(20.dp))
            .border(1.dp, PureBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Text("Skipped ${category.replaceFirstChar { it.uppercase() }}", color = Color.White, fontSize = 13.sp)
        TextButton(onClick = onUndo) { Text("Undo", fontSize = 13.sp) }
    }
}
